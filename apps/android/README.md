# AI Daily Memory — Android app

The phone app is the capture engine: a microphone foreground service records the day in
short encrypted segments, tracks capture health honestly, and (only when an account is
connected) uploads speech clips for processing. It works fully offline with no account —
that is the default, not a degraded mode.

## First run (no account needed)

1. **Microphone permission.** The app explains why before asking. Permanently denied →
   a Settings deep link; recording is refused (never silently fake).
2. **Notification permission.** The persistent recording indicator is a product
   requirement — the app asks because capture must be visible.
3. **Start recording.** The main screen shows honest state: it says "Recording" only when
   audio is actually flowing (the state machine enters RECORDING on the engine's
   `Started` signal, never directly from the button).
4. **Capture health.** "Last saved", "Only on this phone" (pending upload duration —
   expected and correct with no account), storage, interruptions. A red indicator means
   capture is unhealthy; the app never paints a problem green.
5. **Settings.** Sleep schedule and quiet times (they pause/resume the day around your
   hours), audio retention, storage-mode info, account.

## Connecting an account (optional)

Settings → Account: enter the server address and paste a device token. Create the account
first via `POST /v1/signup` (token shown once) or the operator CLI
(`python -m app.admin create-account`) — the app has no in-app signup form yet.
Connecting does not retro-activate backlog upload unless you opt in.

## Private device testing — install and upgrade

```bash
./gradlew :apps:android:assembleDebug
adb install -r apps/android/build/outputs/apk/debug/android-debug.apk
```

- Install phone and watch builds signed with the **same key**; the watch shares the phone
  applicationId for Data Layer pairing (ADR-0008).
- Upgrades must use the same signature; a signature change requires an uninstall (and
  loses local encrypted audio — Keystore keys are not transferable by design).
- To verify recovery behaviors: force-stop the process during recording, reboot the phone,
  deny the microphone mid-session, fill storage. Each must surface honestly (interruption,
  gap, or block) — never a false "Recording".

## Where the data lives on this phone

- Encrypted audio segments in `noBackupFilesDir` (Tink streaming AEAD, Keystore-wrapped
  key; excluded from backups and device transfer — see SECURITY.md).
- Metadata in `capture.db` (Room); transcript content sealed at rest with its own key.
- Deletion: Stop offers Keep or Delete; the retention setting governs acknowledged audio.
  Deletion is logical + key hygiene, not physical flash erasure — stated honestly in the UI.

## Debugging

Logs name events and exception types only — no audio, transcripts, tokens, or exception
messages (they can carry paths). Battery/thermal telemetry is available in debug builds
(`DebugTelemetry`).
