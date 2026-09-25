# ADR-0016: Knowing Which Words Are the User's — On-Device Owner Voice

- Status: Proposed. Reverses README "Speaker Handling: no voice biometrics for the MVP" for
  the account owner only.
- Date: 2026-09-25

## Context

The report is only useful if it gets attribution right:

- "I'll send the deck Friday" is **my** to-do.
- "Can you send the deck?" followed by "Sure" is also **my** to-do, accepted in one word.
- "I'll get back to you" from someone else goes under **waiting on others**.

Diarization alone gives anonymous clusters (Speaker A/B) and can't tell which one is the user.
Proximity or loudness fails as soon as the phone is in a pocket. Recognizing the user's voice
is a voiceprint, which is biometric data under BIPA (Illinois), CUBI (Texas), Washington law,
and GDPR Art. 9. So it has to be consented, minimal, and local.

## Decision

1. **Enroll only the account owner. Never build a voiceprint for anyone else.**
   - Other people are anonymous per conversation ("Speaker 2").
   - A user can name one ("Speaker 2 = Alex"). That name is a text label on that
     conversation, not a voice match.
   - The app never recognizes other people across days.
2. **The owner embedding stays on the device.**
   - It's encrypted with a Keystore-backed key.
   - It's never uploaded, never in backups, and never in API or MCP output.
   - Enrolling needs an explicit biometric-consent screen that states the purpose and the
     retention period.
   - Enrolling and re-enrolling need a device unlock, so someone else holding the phone
     can't enroll.
   - The user can delete it at any time. It's also deleted on sign-out, account deletion,
     and after 3 years without use (a published retention schedule; legal review required).
3. **Enrollment:**
   - About 45 s of prompted reading, done in 3 parts.
   - Quality checks cover speech duration, SNR, and clipping.
   - Enrollment can be redone at any time.
   - Automatic adaptation is off by default. When on, it learns only from turns the user
     confirmed, with a cap and rollback, so the voiceprint can't drift or be poisoned.
4. **Hybrid pipeline (the voiceprint stays on the phone):**
   - During night processing (ADR-0015), a small on-device speaker-embedding model (an
     ECAPA/ResNet-class ONNX model; size and speed to be measured) scores 1.5 s windows
     (0.75 s hop) of speech against the owner centroid.
   - Scores are smoothed into spans labeled `ME`, `NOT_ME`, or `UNSURE`, each with a
     confidence.
   - Only these spans (timestamps, class, and confidence) are uploaded with the clips.
   - Server-side diarization clusters each conversation. A cluster becomes **Me** when the
     overlap-weighted vote of `ME` spans passes a threshold. Other clusters become Speaker
     2, 3, and so on.
   - Short turns under ~1 s ("yes", "sure") get their label from their cluster, so they
     don't rely on per-window scores that are unreliable at that length.
   - If diarization is unavailable, the per-window labels are used directly, and overlap
     marks a turn `UNSURE`.
5. **Not enrolled:** everything works, and speakers are "Unknown". The report shows
   "Someone" instead of guessing. The app nudges enrollment once, at the first report.

## Attribution in extraction and the report

- Transcripts reach extraction as speaker-labeled turns (`me` / `speaker_n` / `unknown`),
  including the turn before each acceptance.
- Extracted actions carry these fields:

  | Field | Values |
  | --- | --- |
  | `owner` | `me`, a speaker label, or `unknown` |
  | `direction` | `i_committed`, `asked_of_me`, `i_asked`, `they_committed` |
  | `attribution_confidence` | a confidence score |

- Report sections:
  - **Your to-dos:** `i_committed`, plus `asked_of_me` when accepted.
  - **Waiting on others:** `they_committed` and `i_asked`.
  - **Not sure who:** anything `UNSURE`, with a one-tap "Me / Them" fix.
- These are additive fields, so the report contract bumps to a minor version (v1.1) with
  defaults. Old consumers keep working.
- Flipping a turn's speaker is stored as a correction (ADR-0004: evidence is immutable,
  derived output is correctable). It re-derives the affected items and doesn't touch the
  transcript.

## Error policy

Two kinds of mistake matter:

- **Someone else's promise labeled as mine** creates a false to-do, which TODO treats as a
  high-severity failure.
- **My promise labeled as someone else's** means a to-do goes missing.

Thresholds are set on a labeled set recorded in real conditions (pocket, café, car, call on
speaker). Anything inside the `UNSURE` band goes to "Not sure who" instead of forcing a guess.
Targets are set once the baseline is measured, and are tracked like extraction precision and
recall (Phase 2 AI evaluation).

## Edge cases designed for

- **Cold, hoarse, whispering, shouting, or a different language:** more `UNSURE`. The
  embeddings are largely language-independent. If `UNSURE` stays high for 3 days, the app
  prompts a re-enroll.
- **A family member with a similar voice:** more false `ME`. The user can re-enroll and
  corrections are one tap. The failure rate on similar voices is reported during evaluation.
- **The user's own voice from a video or voicemail:** labeled Me. This is accepted and rare.
- **Speakerphone:** the far end is labeled Other. Calls are normally an interruption anyway
  (EDGE_CASES Open).
- **Overlapping speech:** the turn is flagged as overlap and its attribution goes to "Not
  sure who".
- **One-word acceptances:** attributed through the cluster and the previous turn's request.
- **Someone else enrolls on the user's phone:** blocked by the device-unlock requirement.
- **A new phone:** no voiceprint by design, because it isn't backed up. The user re-enrolls
  in 45 s.

## Alternatives considered

- **Upload the voiceprint and verify on the server:** simpler, but it puts biometric data on
  our servers and raises legal exposure. Rejected.
- **Ask the user each day which speaker they are:** annoying, and doesn't scale to many
  conversations. Rejected.
- **Loudness or proximity heuristics:** break in a pocket or bag. Rejected as the primary
  signal, though usable as a weak feature.
- **Name recognition for other people:** biometric data about non-consenting people.
  Rejected permanently.
