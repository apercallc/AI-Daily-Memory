"""Temporary audio in S3-compatible object storage (ADR-0003, ADR-0009).

Clients upload directly with a presigned PUT that pins the size and SHA-256: the store itself
rejects a body that doesn't match, and the finalize step re-checks both before anything is
processed. Keys are always `users/{user_id}/...` so isolation and deletion work by prefix.
"""

import asyncio
import base64
import binascii
import re
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any, Protocol

from botocore.config import Config
from botocore.exceptions import ClientError
from botocore.session import get_session

from app.config import Settings

# Exactly one user's prefix: `users/<uuid>/`. Anything broader could delete other users' data.
_USER_PREFIX = re.compile(r"users/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/")
_MISSING_CODES = frozenset({"404", "NoSuchKey", "NotFound"})


class ObjectMissingError(Exception):
    """The object isn't in the store (deleted, expired by lifecycle, or never uploaded)."""


class DeleteIncompleteError(Exception):
    """Some objects under a prefix could not be deleted; the caller must retry."""

    def __init__(self, failed: int, codes: frozenset[str]) -> None:
        super().__init__(f"{failed} objects not deleted")
        self.failed = failed
        self.codes = codes


@dataclass(frozen=True)
class PresignedPut:
    url: str
    headers: dict[str, str]
    expires_at: datetime


@dataclass(frozen=True)
class StoredObject:
    size: int
    # Lowercase hex, or None when the store didn't report a checksum.
    sha256: str | None


class ObjectStore(Protocol):
    def presign_put(
        self,
        key: str,
        *,
        byte_size: int,
        sha256: str,
        expires_in: int,
    ) -> PresignedPut: ...

    async def stat(self, key: str) -> StoredObject | None: ...

    async def read(self, key: str) -> bytes:
        """Raises ObjectMissingError if there is no such object."""
        ...

    async def delete(self, key: str) -> None: ...

    async def delete_prefix(self, prefix: str) -> int: ...


def user_prefix(user_id: uuid.UUID) -> str:
    return f"users/{user_id}/"


def audio_key(user_id: uuid.UUID, upload_id: uuid.UUID) -> str:
    return f"{user_prefix(user_id)}audio/{upload_id}.aac"


def hex_to_b64(sha256_hex: str) -> str:
    return base64.b64encode(bytes.fromhex(sha256_hex)).decode()


def b64_to_hex(value: str) -> str | None:
    try:
        raw = base64.b64decode(value, validate=True)
    except (binascii.Error, ValueError):
        return None
    return raw.hex() if len(raw) == 32 else None


class S3ObjectStore:
    """Works with AWS S3, Cloudflare R2, and MinIO. botocore is synchronous, so network calls
    run in a worker thread; presigning is local computation."""

    def __init__(
        self,
        *,
        endpoint_url: str | None,
        region: str,
        bucket: str,
        access_key_id: str,
        secret_access_key: str,
        client: Any = None,
    ) -> None:
        self.bucket = bucket
        self.client: Any = client or get_session().create_client(
            "s3",
            endpoint_url=endpoint_url,
            region_name=region,
            aws_access_key_id=access_key_id,
            aws_secret_access_key=secret_access_key,
            config=Config(
                signature_version="s3v4",
                s3={"addressing_style": "path"},
                # A hung object store must fail fast with a clear error, not run to the request
                # timeout; the phone retries.
                connect_timeout=5,
                read_timeout=20,
                retries={"max_attempts": 2, "mode": "standard"},
            ),
        )

    def presign_put(
        self,
        key: str,
        *,
        byte_size: int,
        sha256: str,
        expires_in: int,
    ) -> PresignedPut:
        checksum = hex_to_b64(sha256)
        url = self.client.generate_presigned_url(
            "put_object",
            Params={
                "Bucket": self.bucket,
                "Key": key,
                "ContentLength": byte_size,
                "ChecksumSHA256": checksum,
            },
            ExpiresIn=expires_in,
        )
        return PresignedPut(
            url=url,
            headers={
                "Content-Length": str(byte_size),
                "x-amz-checksum-sha256": checksum,
            },
            expires_at=datetime.now(UTC) + timedelta(seconds=expires_in),
        )

    async def stat(self, key: str) -> StoredObject | None:
        try:
            head = await asyncio.to_thread(
                self.client.head_object,
                Bucket=self.bucket,
                Key=key,
                ChecksumMode="ENABLED",
            )
        except ClientError as e:
            if _is_missing(e):
                return None
            raise
        checksum = head.get("ChecksumSHA256")
        return StoredObject(
            size=int(head["ContentLength"]),
            sha256=b64_to_hex(checksum) if isinstance(checksum, str) else None,
        )

    async def read(self, key: str) -> bytes:
        try:
            response = await asyncio.to_thread(self.client.get_object, Bucket=self.bucket, Key=key)
        except ClientError as e:
            if _is_missing(e):
                raise ObjectMissingError(key) from e
            raise
        body: bytes = await asyncio.to_thread(response["Body"].read)
        return body

    async def delete(self, key: str) -> None:
        await asyncio.to_thread(self.client.delete_object, Bucket=self.bucket, Key=key)

    async def delete_prefix(self, prefix: str) -> int:
        """Deletes every object under [prefix] (account deletion). Returns the count."""
        if not _USER_PREFIX.fullmatch(prefix):
            raise ValueError("refusing to delete outside a single user's prefix")
        deleted = 0
        failed = 0
        codes: set[str] = set()
        token: str | None = None
        while True:
            params: dict[str, Any] = {"Bucket": self.bucket, "Prefix": prefix}
            if token:
                params["ContinuationToken"] = token
            page = await asyncio.to_thread(self.client.list_objects_v2, **params)
            keys = [{"Key": item["Key"]} for item in page.get("Contents", [])]
            if keys:
                result = await asyncio.to_thread(
                    self.client.delete_objects,
                    Bucket=self.bucket,
                    Delete={"Objects": keys, "Quiet": True},
                )
                # DeleteObjects answers 200 even when keys fail; the list is the only signal.
                errors = result.get("Errors", [])
                failed += len(errors)
                codes |= {str(e.get("Code", "unknown")) for e in errors}
                deleted += len(keys) - len(errors)
            if not page.get("IsTruncated"):
                if failed:
                    raise DeleteIncompleteError(failed, frozenset(codes))
                return deleted
            token = page.get("NextContinuationToken")


def _is_missing(error: ClientError) -> bool:
    return error.response.get("Error", {}).get("Code") in _MISSING_CODES


def object_store_from(settings: Settings) -> ObjectStore | None:
    """The configured store, or None when storage isn't set up (uploads then return 503)."""
    if not (settings.s3_bucket and settings.s3_access_key_id and settings.s3_secret_access_key):
        return None
    return S3ObjectStore(
        endpoint_url=settings.s3_endpoint_url,
        region=settings.s3_region,
        bucket=settings.s3_bucket,
        access_key_id=settings.s3_access_key_id.get_secret_value(),
        secret_access_key=settings.s3_secret_access_key.get_secret_value(),
    )
