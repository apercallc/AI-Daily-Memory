import hashlib
import io
import uuid
from urllib.parse import parse_qs, urlparse

import pytest
from botocore.response import StreamingBody
from botocore.stub import Stubber

from app.config import Settings
from app.main import object_store_from
from app.storage import (
    ObjectMissingError,
    S3ObjectStore,
    audio_key,
    b64_to_hex,
    hex_to_b64,
    user_prefix,
)

pytestmark = pytest.mark.anyio

SHA = hashlib.sha256(b"clip").hexdigest()


@pytest.fixture
def store() -> S3ObjectStore:
    return S3ObjectStore(
        endpoint_url="https://account.r2.example",
        region="auto",
        bucket="aidm-audio",
        access_key_id="test-key-id",
        secret_access_key="test-secret-value",  # noqa: S106 - fake credential for signing
    )


def test_keys_are_isolated_under_the_user_prefix() -> None:
    user, upload = uuid.uuid4(), uuid.uuid4()

    assert audio_key(user, upload) == f"users/{user}/audio/{upload}.aac"
    assert audio_key(user, upload).startswith(user_prefix(user))


def test_checksum_encoding_round_trips_and_rejects_garbage() -> None:
    assert b64_to_hex(hex_to_b64(SHA)) == SHA
    assert b64_to_hex("not base64!") is None
    assert b64_to_hex("YWJj") is None  # valid base64, wrong length


def test_presigned_put_pins_size_and_checksum(store: S3ObjectStore) -> None:
    signed = store.presign_put("users/u/audio/a.aac", byte_size=4, sha256=SHA, expires_in=900)

    url = urlparse(signed.url)
    query = parse_qs(url.query)
    assert url.path == "/aidm-audio/users/u/audio/a.aac"
    assert query["X-Amz-Expires"] == ["900"]
    signed_headers = query["X-Amz-SignedHeaders"][0].split(";")
    assert "content-length" in signed_headers
    assert "x-amz-checksum-sha256" in signed_headers
    assert signed.headers == {
        "Content-Length": "4",
        "x-amz-checksum-sha256": hex_to_b64(SHA),
    }


async def test_stat_reports_size_and_checksum(store: S3ObjectStore) -> None:
    with Stubber(store.client) as stub:
        stub.add_response(
            "head_object",
            {"ContentLength": 4, "ChecksumSHA256": hex_to_b64(SHA)},
            {"Bucket": "aidm-audio", "Key": "k", "ChecksumMode": "ENABLED"},
        )
        stub.add_response("head_object", {"ContentLength": 4}, None)
        stub.add_client_error("head_object", service_error_code="404", http_status_code=404)
        stub.add_client_error(
            "head_object", service_error_code="AccessDenied", http_status_code=403
        )

        first = await store.stat("k")
        no_checksum = await store.stat("k")
        missing = await store.stat("k")
        with pytest.raises(Exception, match="AccessDenied"):
            await store.stat("k")

    assert first is not None
    assert (first.size, first.sha256) == (4, SHA)
    assert no_checksum is not None
    assert no_checksum.sha256 is None
    assert missing is None


async def test_read_and_delete(store: S3ObjectStore) -> None:
    with Stubber(store.client) as stub:
        stub.add_response(
            "get_object",
            {"Body": StreamingBody(io.BytesIO(b"clip"), 4)},
            {"Bucket": "aidm-audio", "Key": "k"},
        )
        stub.add_response("delete_object", {}, {"Bucket": "aidm-audio", "Key": "k"})

        assert await store.read("k") == b"clip"
        await store.delete("k")


async def test_delete_prefix_pages_through_everything(store: S3ObjectStore) -> None:
    prefix = f"users/{uuid.uuid4()}/"
    with Stubber(store.client) as stub:
        stub.add_response(
            "list_objects_v2",
            {
                "Contents": [{"Key": f"{prefix}1"}, {"Key": f"{prefix}2"}],
                "IsTruncated": True,
                "NextContinuationToken": "t",
            },
            {"Bucket": "aidm-audio", "Prefix": prefix},
        )
        stub.add_response("delete_objects", {}, None)
        stub.add_response(
            "list_objects_v2",
            {"IsTruncated": False},
            {"Bucket": "aidm-audio", "Prefix": prefix, "ContinuationToken": "t"},
        )

        assert await store.delete_prefix(prefix) == 2


@pytest.mark.parametrize(
    "prefix",
    [
        "",
        "users/",
        "users//",
        "other/abc/",
        f"users/{uuid.uuid4()}",
        f"users/{uuid.uuid4()}/audio/",
    ],
)
async def test_delete_prefix_refuses_anything_but_exactly_one_user(
    store: S3ObjectStore,
    prefix: str,
) -> None:
    with pytest.raises(ValueError, match="refusing"):
        await store.delete_prefix(prefix)


async def test_a_missing_object_reads_as_object_missing(store: S3ObjectStore) -> None:
    with Stubber(store.client) as stub:
        stub.add_client_error("get_object", service_error_code="NoSuchKey", http_status_code=404)
        stub.add_client_error("get_object", service_error_code="SlowDown", http_status_code=503)

        with pytest.raises(ObjectMissingError):
            await store.read("k")
        with pytest.raises(Exception, match="SlowDown"):
            await store.read("k")


def test_store_is_configured_only_with_complete_credentials() -> None:
    assert object_store_from(Settings(app_env="test")) is None
    configured = object_store_from(
        Settings(
            app_env="test",
            s3_bucket="b",
            s3_access_key_id="id",  # type: ignore[arg-type]
            s3_secret_access_key="value",  # type: ignore[arg-type]  # noqa: S106 - fake
        ),
    )
    assert isinstance(configured, S3ObjectStore)
