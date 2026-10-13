# ADR-0027: A Notes Library in the Web App

- Status: Accepted
- Date: 2026-10-13
- Builds on: ADR-0024 (web app), ADR-0026 (the server stores synced notes). Amends neither.

## Context

The web app showed memory as a flat list of extracted items (decisions, actions, ideas) and a
day-by-day view. With transcription on the phone and summaries optional (ADR-0026), many people
sync words and few or no items, so an item list shows them almost nothing. People also had no
way to find a recording, name it, or keep related ones together.

## Decision

1. **The unit is the note: one recorded session.** `notes` holds one row per synced session, built
   on sync (and once, on first use, for people who synced earlier). It stores the derived title,
   previews, transcript text for search, and counts.
2. **Organization belongs to the person and survives sync.** Their own title, one folder, a star
   and an archive flag live on the note and are never written by sync. Folders are flat, named
   1 to 60 characters, unique ignoring case, at most 100 each. Deleting a folder unfiles its notes.
   Archive hides a note and deletes nothing.
3. **Search is plain and honest.** Every word must appear in the note's title, an item, or (with
   `transcript:read`) its transcript, matched case-insensitively with wildcards escaped. Results
   carry a snippet of where the words were found. No ranking model and no index beyond the user's
   own rows; revisit with Postgres full text if a library grows past what a scan handles.
4. **Spoken words follow the transcript scope.** A title or preview taken from the transcript is
   shown as "Recording" and kept out of search for tokens without `transcript:read`, so search
   cannot be used to read what the scope forbids.
5. **The web app opens on Notes.** A folder sidebar (All, Starred, Not in a folder, folders,
   Archive), a search box, sort and date range, select-many with move, star and archive, and a note
   page with highlights, a searchable transcript, copy and download. The view (folder, search,
   sort) lives in the address, so back and reload return to the same list. The earlier items list
   stays as "Items".
6. **Export includes the organization.** Folders and each note's title, folder, star and archive
   state are in the export document.

## Consequences

- Transcript text is stored twice on the server (the synced report and the note) while transcript
  sync is on. Turning transcript sync off stops new text but does not remove what was synced; that
  predates this change and is still open.
- Titles come from extraction or the first spoken line, so they can read oddly; renaming is one
  click. Phone-side titles are not synced yet.
- Notes cannot be deleted from the web. The phone is the source of truth and deleting there
  removes the note on the next sync.
