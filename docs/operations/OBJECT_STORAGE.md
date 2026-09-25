# Object storage for temporary audio

Audio clips live in object storage only between upload and transcription (ADR-0003). The app
deletes them itself; the bucket configuration below is the backstop when the app can't.

## Bucket

- One private bucket (for example `aidm-audio`); no public access, no website hosting.
- Keys are always `users/<user-uuid>/audio/<upload-uuid>.aac` (`app/storage.py`).
- Credentials: an access key scoped to this bucket only (object read/write/delete, list).
- The store must support SHA-256 checksums on `PutObject` and return them from `HeadObject`
  with `ChecksumMode=ENABLED`. Without that, uploads fail closed with
  `integrity_unverifiable` (verify on MinIO and R2 before rollout; TODO Phase 1).

## Lifecycle rule (backstop)

Expire objects under `users/` **8 days** after creation. That is one day past the 7-day
maximum hold (ADR-0014, `app/retention.MAX_HOLD`), so it only deletes what the app's own sweep
missed, and never audio the app still expects to process.

Cloudflare R2 (dashboard → bucket → Settings → Object lifecycle rules):

- Rule name: `temporary-audio-backstop`
- Prefix: `users/`
- Action: delete objects 8 days after upload

AWS S3 / MinIO (JSON):

```json
{
  "Rules": [
    {
      "ID": "temporary-audio-backstop",
      "Filter": { "Prefix": "users/" },
      "Status": "Enabled",
      "Expiration": { "Days": 8 },
      "AbortIncompleteMultipartUpload": { "DaysAfterInitiation": 1 }
    }
  ]
}
```

## What the app deletes, and when

| Case | Who deletes | When |
| --- | --- | --- |
| Clip transcribed and the transcript committed | transcription handler | immediately after the commit |
| Transcription dead-lettered | worker dead-letter hook, else the sweep | immediately / next sweep (5 min) |
| Upload never completed | retention sweep | URL TTL + 1 h |
| Received but not processed within 7 days | retention sweep | 7 days after receipt |
| Integrity check failed | complete endpoint | immediately; the phone uploads again |
| Account deletion | `S3ObjectStore.delete_prefix` (exactly one `users/<uuid>/`) | with the account |

Every sweep deletion happens before the row is marked `failed`, so a failed delete leaves the
row for the next sweep instead of hiding audio that still exists.
