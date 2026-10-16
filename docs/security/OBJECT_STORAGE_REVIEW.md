# Object-storage path isolation and presigned URL review

TODO.md Phase 8 Security asks to "verify object-storage path isolation and signed URL
scope/TTL tests" as a named control, reviewed together rather than scattered across
per-endpoint tests. The controls and their tests already existed before this review; this
document is the single place that states what the control actually is, cites exactly what
proves it, and says plainly what is still residual risk. See `docs/security/THREAT_MODEL.md`
T4/T5/T6 for the threats this control answers.

## The control, as a whole

1. **Every object key lives under exactly one user's prefix.** `user_prefix(user_id)` ==
   `users/<uuid>/`; every key-constructing function (`audio_key`, and `export.py`'s
   `_object_key`) builds its key by appending to that prefix — there is no code path that
   accepts a caller-supplied key directly. (`app/storage.py`)
2. **Deleting a prefix is refused unless it matches exactly one user.** `S3ObjectStore.
   delete_prefix` checks the prefix against `_USER_PREFIX` (a UUID-shaped `users/<uuid>/`
   regex, full match) before issuing a single S3 call — not `users/`, not two users joined,
   not a trailing-newline-smuggled variant. A bug that constructed a broader prefix raises
   instead of deleting across users.
3. **A presigned PUT is scoped to one key, one size, one checksum, and a bounded TTL.**
   `presign_put` signs `Bucket`+`Key`+`ContentLength`+`ChecksumSHA256` into the URL itself (S3
   presigning binds these into the signature, so they cannot be changed after the fact) and
   accepts `expires_in` only within `Settings.upload_url_ttl_seconds`'s bound (`ge=60,
   le=MAX_UPLOAD_URL_TTL_SECONDS` = 3600s, `app/config.py`). The key is never issued until
   `app/api/uploads.py`'s `_create` has already verified the caller owns the upload row (or is
   creating a fresh one under their own `principal.user_id`) — a presign is a consequence of an
   ownership check, never a substitute for one.
4. **A presigned GET (export) is scoped to one key, forces a download disposition, and is
   equally TTL-bounded.** `presign_get` signs `Bucket`+`Key`+`ResponseContentDisposition`+
   `ResponseCacheControl: no-store` into the URL; `export.py`'s `settings.export_url_ttl_seconds`
   has the identical bound. The key it signs is always `_object_key(owner.user_id)` — the
   caller cannot ask for any other key, so there is no "scope" to escalate even if the caller
   tried.
5. **Finalization re-verifies size and checksum independently of the presign.** `complete_upload`
   calls `store.stat()` and rejects (deleting the bad object) on any mismatch with what the
   client claimed at `POST /v1/uploads` time — a presigned URL's embedded constraints are a
   client-side convenience the store enforces, not the only enforcement.

## What proves it

- Path isolation: `tests/test_storage.py::test_keys_are_isolated_under_the_user_prefix`,
  `test_delete_prefix_refuses_anything_but_exactly_one_user` (7 cases: empty, `users/`, a
  newline-smuggled id, a double slash, a different top-level prefix, a bare id with no trailing
  slash, and a sub-path under a user — all refused).
- Presigned PUT scope/TTL: `test_presigned_put_pins_size_and_checksum` (asserts the exact key
  path, `X-Amz-Expires`, and the signed-header list contains `content-length` and
  `x-amz-checksum-sha256`).
- Presigned GET scope/TTL: `test_presigned_get_scopes_the_key_and_sets_a_download_filename`
  (asserts the exact key path, `X-Amz-Expires`, `response-content-disposition`, and
  `response-cache-control`).
- Ownership-before-presign: `tests/test_authorization_matrix.py::
  test_an_uploads_resource_404s_for_a_different_user` (a second account with full, correct
  scopes still can't reach the first account's upload); `tests/test_uploads.py::
  test_uploads_are_invisible_to_other_users` covers the same property with more upload-specific
  detail.
- Finalize re-verification: `tests/test_uploads.py`'s checksum/size-mismatch cases (the
  module this review doesn't re-litigate; see that file for the full set).

## Residual risk (not closed by this review, and not expected to be)

- A presigned URL, once issued, is valid for its full TTL (up to `MAX_UPLOAD_URL_TTL_SECONDS`
  = 3600s) even if the token that requested it is revoked a second later — presigning is a
  property of the S3-compatible protocol, not a capability this app can revoke mid-flight. The
  TTL bound exists precisely to keep this window small; it is a mitigated, not eliminated, risk
  (matches `THREAT_MODEL.md` T5's documented gap).
- This review covers the object-storage boundary only. It does not re-review the upload
  protocol's idempotency/conflict handling (`THREAT_MODEL.md` T4) or account-deletion ordering
  (T6) beyond citing that they exist; those have their own dedicated test files.
