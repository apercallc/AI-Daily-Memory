# ADR-0012: Daily Rhythm — Sleep Schedule, Quiet Times, Memory Days, Daily Report

- Status: Accepted (report generation itself arrives with Phases 1–2)
- Date: 2026-09-25

## Context

The intended use is: start recording in the morning, carry the phone all day, and review a
report of what mattered the next morning. People don't want recording (or battery use)
overnight, they want recurring times that are never recorded (lunch, the gym, a weekly 1:1),
and "today" has to match how they live, not the calendar: a 00:30 conversation belongs to
the evening before.

Constraints: capture must never start without a user action (ADR-0010), a privacy pause must
never be undone automatically (AGENTS.md rule 1), and the phone must not be kept awake to run
a schedule.

## Decision

1. **Daily rhythm** = optional sleep windows (one for every night, or weeknights + Friday/
   Saturday nights) + named quiet windows (days of week, local wall-clock times) + two
   switches: *end the day at bedtime* (default on) and *morning reminder* (default on).
   Windows follow the phone's current time zone. Pure model and planner in `core/recording/
   schedule` (DST, midnight crossing, overlaps, travel covered by tests).
2. **Actions** on schedule transitions:
   - quiet window starts → `ScheduledPause`; ends → `ScheduledResume`;
   - bedtime → `ScheduledStop` (ends the session and the day), or a pause if *end the day*
     is off;
   - wake time after an ended day → a **reminder notification** with Start. Capture never
     starts by itself.
3. **Schedule vs. user.** A pause carries `pausedBySchedule`. Only such a pause is resumed
   automatically; pausing during quiet time makes it the user's pause; resuming during quiet
   time is honored and not re-paused (transitions are edge-triggered). Schedule commands are
   never *rejected* (they're ignored when they don't apply), so a schedule tick never shows an
   error the user didn't cause. The mark survives process death via the journal cause, without
   a schema change.
4. **Timing without keeping the phone awake.** While recording, the service checks the
   schedule on a short timer (the CPU is already awake for audio), so pauses land on time.
   Otherwise inexact, idle-allowed alarms wake the app; a late resume only means recording
   starts a little later. After sleep, reboot, or a clock change, catch-up applies only the
   *last* missed action. A changed schedule that says "quiet" right now pauses immediately.
5. **Memory day.** A day turns over at the midpoint of the sleep window (23:00–07:00 → 03:00);
   nights without a schedule turn at 04:00. A session's memory day is fixed when it starts
   (`session_info.memory_day`), so later schedule edits never move history.
6. **Sessions** are the unit inside a day: every start/stop is one, automatically titled by
   part of day ("Afternoon 2") and renamable (validated: ≤60 characters, no control or bidi
   characters).
7. **Daily report.** Due at bedtime (or 04:00 without a schedule), delivered at the next wake
   time (or 08:00). Content follows the versioned contract `contracts/daily-report.v1.schema.json`
   (see `docs/product/daily-report-example.md`): at a glance, action items, commitments (both
   directions), decisions, ideas, open questions, highlights, people/topics, per-session
   timeline, and what was *not* recorded and why. Every extracted item cites evidence; times
   are in the report's zone, plus the local zone for sessions recorded while travelling.
   Markdown export escapes all model text (untrusted input).

## Consequences

- Battery: nothing runs overnight when the day ends at bedtime; no exact-alarm permission.
- The report depends on transcription and extraction (Phases 1–2); until then the app shows
  sessions and capture coverage only, and never claims a report exists.
- Export to Google Drive starts as the Android share sheet with the Markdown report (no
  server-side Drive integration or OAuth scopes until there's a measured need).
- The report contract is what the future API/MCP `get_daily_report` returns (Phase 5).
