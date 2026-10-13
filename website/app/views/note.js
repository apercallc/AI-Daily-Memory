import { api, ApiError, describe } from "../api.js";
import { confirmBox, promptName } from "../dialogs.js";
import { h, notice, prettyDay, spinner } from "../dom.js";
import { itemCard } from "./item.js";
import { LIBRARY_KEY, marked } from "./notes.js";
import { clockTime, count, fileName, searchTerms, transcriptText } from "./notes-lib.js";

function backHref() {
  try { return sessionStorage.getItem(LIBRARY_KEY) || "#/notes"; } catch { return "#/notes"; }
}

export async function noteView(sessionId) {
  const body = h("div");
  body.append(spinner("Opening your note…"));
  load();
  return body;

  async function load() {
    let detail;
    let folders;
    try {
      [detail, folders] = await Promise.all([api(`/notes/${sessionId}`), api("/folders").then((f) => f.folders)]);
    } catch (e) {
      const gone = e instanceof ApiError && e.status === 404;
      body.replaceChildren(h("p", null, h("a", { href: backHref() }, "← All notes")),
        gone ? h("div", { class: "card empty" }, h("h3", null, "That note isn't here"),
          h("p", null, "It may have been deleted on your phone, or the link is out of date."))
          : notice("error", describe(e)),
        gone ? null : h("button", { onclick: load }, "Try again"));
      return;
    }
    draw(detail, folders);
  }

  function draw({ note, items, lines }, folders) {
    const status = h("div", { "aria-live": "polite" });
    const say = (kind, message) => status.replaceChildren(notice(kind, message));
    const redraw = () => draw({ note, items, lines }, folders);

    async function patch(change) {
      try {
        Object.assign(note, await api(`/notes/${sessionId}`, { method: "PATCH", body: change }));
        redraw();
      } catch (e) {
        say("error", describe(e));
      }
    }

    function renameForm() {
      const input = h("input", { type: "text", id: "note-title", maxlength: 120, value: note.title, "aria-label": "Note title" });
      const form = h("form", { class: "row", onsubmit: (event) => { event.preventDefault(); patch({ title: input.value }); } },
        h("div", { class: "grow" }, input),
        h("button", { class: "primary", type: "submit" }, "Save"),
        note.renamed ? h("button", { type: "button", onclick: () => patch({ title: "" }) }, "Use automatic title") : null,
        h("button", { type: "button", onclick: redraw }, "Cancel"));
      form.addEventListener("keydown", (event) => { if (event.key === "Escape") redraw(); });
      head.replaceChildren(form);
      input.focus();
      input.select();
    }

    async function moveTo(value, select) {
      if (value === "new") {
        let made = null;
        const saved = await promptName({ title: "New folder", label: "Folder name", action: "Create and move", submit: async (name) => {
          try { made = await api("/folders", { method: "POST", body: { name } }); return null; } catch (e) { return describe(e); }
        } });
        if (!saved || !made) { select.value = note.folder_id || "none"; return; }
        folders = [...folders, made];
        return patch({ folder_id: made.id });
      }
      return patch({ folder_id: value === "none" ? null : value });
    }

    const select = h("select", { id: "note-folder", onchange: () => moveTo(select.value, select) },
      h("option", { value: "none", selected: !note.folder_id }, "No folder"),
      folders.map((f) => h("option", { value: f.id, selected: f.id === note.folder_id }, f.name)),
      h("option", { value: "new" }, "New folder…"));

    const head = h("div", { class: "note-head" },
      h("h1", { class: "mt0" }, note.title),
      h("button", { class: "small", type: "button", onclick: renameForm }, "Rename"));

    const facts = [prettyDay(note.memory_day), note.started_at ? clockTime(Date.parse(note.started_at)) : null,
      note.word_count ? count(note.word_count, "word") : null].filter(Boolean).join(" · ");

    async function removeNote() {
      const yes = await confirmBox({
        title: "Remove this note from your account?",
        body: "This deletes the note, its highlights and its transcript from this account. Your phone keeps its own copy and will not send it again.",
        action: "Remove note",
        danger: true,
      });
      if (!yes) return;
      try {
        await api(`/notes/${sessionId}`, { method: "DELETE" });
      } catch (e) {
        say("error", describe(e));
        return;
      }
      location.hash = backHref();
    }

    const actions = h("div", { class: "row mt12" },
      h("button", { type: "button", "aria-pressed": String(note.starred), onclick: () => patch({ starred: !note.starred }) }, note.starred ? "★ Starred" : "☆ Star"),
      h("div", { class: "inline-field" }, h("label", { for: "note-folder", class: "plainlabel" }, "Folder"), select),
      h("button", { type: "button", onclick: () => patch({ archived: !note.archived }) }, note.archived ? "Restore from archive" : "Archive"),
      h("button", { type: "button", class: "danger", onclick: removeNote }, "Remove…"));

    body.replaceChildren(
      h("p", { class: "m0" }, h("a", { href: backHref() }, "← All notes")),
      head,
      h("p", { class: "faint m0" }, facts, note.archived ? h("span", { class: "chip" }, "Archived") : null),
      actions, status,
      highlightsSection(items, note),
      transcriptSection(note, lines, say));
    document.title = `${note.title} — AI Daily Memory`;
  }
}

function highlightsSection(items, note) {
  const live = items.filter((i) => !i.dismissed);
  return h("section", { "aria-labelledby": "hl-h" },
    h("h2", { id: "hl-h" }, "Highlights", live.length ? h("span", { class: "faint" }, ` ${live.length}`) : null),
    items.length
      ? items.map((i) => itemCard(i))
      : h("div", { class: "card" }, h("p", { class: "m0 muted" },
        note.line_count
          ? "No summary for this recording. Decisions and action items appear here when you turn on summaries in the phone app (Settings → Summaries)."
          : "Nothing was pulled out of this recording.")));
}

function transcriptSection(note, lines, say) {
  if (lines === null) {
    return h("section", null, h("h2", null, "Transcript"),
      h("div", { class: "card" }, h("p", { class: "m0 muted" }, "This sign-in can't read transcripts. Sign in to the website to see them.")));
  }
  if (!lines.length) {
    return h("section", null, h("h2", null, "Transcript"),
      h("div", { class: "card" }, h("p", { class: "mt0" }, "The words from this recording are kept on your phone."),
        h("p", { class: "m0 muted" }, "To read them here, open the phone app → Settings → Daily reports and turn on “Also sync full transcripts”. The next sync will bring them over.")));
  }
  const terms = h("input", { type: "search", id: "in-note", placeholder: "Find in this transcript", "aria-label": "Find in this transcript" });
  const out = h("div", { class: "transcript" });
  const tally = h("p", { class: "faint m0", role: "status" });

  function draw() {
    const words = searchTerms(terms.value);
    const shown = words.length ? lines.filter((l) => words.every((w) => l.text.toLowerCase().includes(w.toLowerCase()))) : lines;
    tally.textContent = words.length ? `${count(shown.length, "line")} of ${lines.length} match` : count(lines.length, "line");
    out.replaceChildren(...shown.map((l) => h("div", { class: "tline" },
      h("span", { class: "time" }, clockTime(l.start_epoch_ms)),
      h("p", { class: "m0" }, l.speaker ? h("strong", null, `${l.speaker}: `) : null, marked(l.text, words)))));
    if (!shown.length) out.append(h("p", { class: "muted" }, "No lines match."));
  }
  terms.addEventListener("input", draw);
  draw();

  async function copy() {
    try {
      await navigator.clipboard.writeText(transcriptText(note, lines));
      say("ok", "Copied the transcript.");
    } catch {
      say("error", "Couldn't copy. Use Download instead.");
    }
  }

  function download() {
    const blob = new Blob([transcriptText(note, lines)], { type: "text/plain;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = h("a", { href: url, download: fileName(note) });
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  return h("section", { "aria-labelledby": "tr-h" },
    h("div", { class: "row between" }, h("h2", { id: "tr-h" }, "Transcript"),
      h("div", { class: "row" }, h("button", { class: "small", type: "button", onclick: copy }, "Copy"), h("button", { class: "small", type: "button", onclick: download }, "Download .txt"))),
    h("div", { class: "toolbar" }, h("div", { class: "grow" }, h("label", { for: "in-note", class: "sr-only" }, "Find in this transcript"), terms)),
    tally, out);
}
