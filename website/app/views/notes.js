import { api, describe } from "../api.js";
import { confirmBox, promptName } from "../dialogs.js";
import { h, notice, prettyDay, shortDay, spinner } from "../dom.js";
import {
  RANGES, SORTS, clockTime, count, highlight, listParams, noteFacts, parseState, searchTerms, stateToHash, viewTitle,
} from "./notes-lib.js";

const PAGE = 30;
export const LIBRARY_KEY = "adm.library";

// "/" jumps to the search box from anywhere on the page, like most note apps.
let slashBound = false;
function bindSlash() {
  if (slashBound) return;
  slashBound = true;
  document.addEventListener("keydown", (event) => {
    const box = document.getElementById("notes-q");
    if (event.key !== "/" || !box || event.metaKey || event.ctrlKey || event.altKey) return;
    const tag = document.activeElement?.tagName;
    if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || document.querySelector("dialog[open]")) return;
    event.preventDefault();
    box.focus();
  });
}

// Matches in a title or snippet, as text nodes and <mark>s, never as markup.
export function marked(text, terms) {
  return highlight(text, terms).map((piece) => (piece.hit ? h("mark", null, piece.text) : piece.text));
}

export async function notesView() {
  bindSlash();
  const state = parseState(location.hash);
  let folders = [];
  let totals = { all: 0, starred: 0, unfiled: 0, archived: 0 };
  let shown = 0;
  let total = 0;
  let lastDay = null;
  let ticket = 0;
  const selected = new Set();
  const cards = new Map();

  const side = h("nav", { class: "side", "aria-label": "Folders" });
  const title = h("h1", { class: "mt0", id: "notes-title" });
  const sub = h("p", { class: "faint m0", role: "status" });
  const tools = h("div", { class: "row" });
  const bulk = h("div", { class: "bulkbar", role: "region", "aria-label": "Selected notes" });
  const list = h("div");
  const more = h("div", { class: "row end mt14" });

  const search = h("input", { type: "search", id: "notes-q", value: state.q, placeholder: "Search notes and transcripts", "aria-label": "Search your notes", autocomplete: "off" });
  const searchClear = h("button", { class: "small search-clear", type: "button", "aria-label": "Clear search", hidden: !state.q,
    onclick: () => { search.value = ""; state.q = ""; searchClear.hidden = true; remember(); load(true); search.focus(); } }, "Clear");
  const searchControl = h("div", { class: "search-control" }, search, searchClear);
  const sort = h("select", { id: "notes-sort", "aria-label": "Sort notes" }, SORTS.map(([v, l]) => h("option", { value: v, selected: v === state.sort }, l)));
  const range = h("select", { id: "notes-range", "aria-label": "When" }, RANGES.map(([v, l]) => h("option", { value: v, selected: v === state.range }, l)));

  function remember() {
    const hash = stateToHash(state);
    history.replaceState(null, "", hash);
    try { sessionStorage.setItem(LIBRARY_KEY, hash); } catch { /* the back link then goes to the plain library */ }
  }

  // --- sidebar ---------------------------------------------------------------------------------

  function navItem(view, label, n) {
    return h("button", { class: "navitem", type: "button", "aria-current": state.view === view ? "true" : false, onclick: () => setView(view) },
      h("span", { class: "label" }, label), h("span", { class: "n" }, String(n)));
  }

  function drawSide() {
    side.replaceChildren(
      h("div", { class: "navgroup" },
        navItem("all", "All notes", totals.all),
        navItem("starred", "★ Starred", totals.starred),
        navItem("unfiled", "Not in a folder", totals.unfiled)),
      h("div", { class: "navgroup" },
        h("div", { class: "navhead" }, h("span", { id: "folders-h" }, "Folders"),
          h("button", { class: "small", type: "button", onclick: newFolder, "aria-label": "New folder" }, "+ New")),
        folders.length
          ? folders.map((f) => navItem(f.id, f.name, f.notes))
          : h("p", { class: "faint m0" }, "Make a folder to sort your notes, like “Work” or “Ideas”.")),
      h("div", { class: "navgroup" }, navItem("archive", "Archive", totals.archived)));
  }

  function drawHeader() {
    title.textContent = viewTitle(state.view, folders);
    const folder = folders.find((f) => f.id === state.view);
    tools.replaceChildren(...(folder ? [
      h("button", { class: "small", type: "button", onclick: () => renameFolder(folder) }, "Rename folder"),
      h("button", { class: "small danger", type: "button", onclick: () => deleteFolder(folder) }, "Delete folder"),
    ] : []));
  }

  async function refreshFolders() {
    let reloaded = false;
    try {
      const data = await api("/folders");
      folders = data.folders;
      totals = data.totals;
      if (state.view.length === 36 && !folders.some((f) => f.id === state.view)) { state.view = "all"; remember(); load(true); reloaded = true; }
    } catch (e) {
      sub.replaceChildren(describe(e));
    }
    drawSide();
    drawHeader();
    return reloaded;
  }

  function setView(view) {
    state.view = view;
    selected.clear();
    remember();
    drawSide();
    drawHeader();
    load(true);
  }

  async function createFolder(name) {
    try { return { folder: await api("/folders", { method: "POST", body: { name } }) }; } catch (e) { return { problem: describe(e) }; }
  }

  async function newFolder() {
    const saved = await promptName({ title: "New folder", label: "Folder name", action: "Create", submit: async (name) => (await createFolder(name)).problem || null });
    if (saved) await refreshFolders();
  }

  async function renameFolder(folder) {
    const saved = await promptName({ title: "Rename folder", label: "Folder name", initial: folder.name, action: "Rename", submit: async (name) => {
      try { await api(`/folders/${folder.id}`, { method: "PATCH", body: { name } }); return null; } catch (e) { return describe(e); }
    } });
    if (saved) refreshFolders();
  }

  async function deleteFolder(folder) {
    const yes = await confirmBox({ title: `Delete “${folder.name}”?`, body: "The notes inside are kept. They just move out of the folder.", action: "Delete folder", danger: true });
    if (!yes) return;
    try { await api(`/folders/${folder.id}`, { method: "DELETE" }); } catch (e) { sub.replaceChildren(describe(e)); return; }
    state.view = "all";
    remember();
    await refreshFolders();
    load(true);
  }

  // --- the list --------------------------------------------------------------------------------

  async function load(reset) {
    const mine = ++ticket;
    const terms = searchTerms(state.q);
    if (reset) {
      list.replaceChildren(spinner(state.q ? "Searching…" : "Loading your notes…"));
      lastDay = null; shown = 0; cards.clear();
    }
    more.replaceChildren();
    drawBulk();
    let page;
    try {
      page = await api(`/notes?${listParams(state, { limit: PAGE, offset: shown })}`);
    } catch (e) {
      if (mine !== ticket) return;
      list.replaceChildren(notice("error", describe(e)));
      more.append(h("button", { onclick: () => load(reset) }, "Try again"));
      return;
    }
    if (mine !== ticket) return;
    if (reset) list.replaceChildren();
    total = page.total;
    if (reset && !page.notes.length) { list.append(emptyState()); sub.textContent = state.q ? `No notes match “${state.q.trim()}”` : ""; return; }
    const grouped = state.sort === "newest" || state.sort === "oldest";
    for (const note of page.notes) {
      if (grouped && note.memory_day !== lastDay) { lastDay = note.memory_day; list.append(h("h2", { class: "day-head" }, prettyDay(note.memory_day))); }
      const card = noteCard(note, terms);
      cards.set(note.session_id, { note, card });
      list.append(card);
    }
    shown += page.notes.length;
    sub.textContent = `${count(total, "note")}${state.q.trim() ? ` matching “${state.q.trim()}”` : ""}${shown < total ? `, showing ${shown}` : ""}`;
    if (shown < total) more.append(h("button", { onclick: () => load(false) }, "Show more"));
  }

  function emptyState() {
    if (state.q.trim()) {
      return h("div", { class: "card empty" }, h("h3", null, "No notes match"),
        h("p", null, "Try fewer or different words, or widen the dates."),
        h("button", { onclick: () => { search.value = ""; state.q = ""; state.range = "all"; range.value = "all"; searchClear.hidden = true; remember(); load(true); } }, "Clear search"));
    }
    if (state.view === "all" && state.range === "all") {
      return h("div", { class: "card empty" }, h("h3", null, "Your notes will appear here"),
        h("p", null, "Your first phone recording becomes a note here. Audio is never included in account sync."),
        h("ol", { class: "olist" },
          h("li", null, "Install the Android app on your phone."),
          h("li", null, "In Settings → Account & server, sign in with this account."),
          h("li", null, "Leave Sync on in Settings → Daily reports. Full transcript sync is optional.")),
        h("p", null, h("a", { class: "btn", href: "#/account?section=phone" }, "Set up phone sync")));
    }
    const messages = {
      starred: ["Nothing starred yet", "Tap the star on a note to keep it here."],
      unfiled: ["Everything is in a folder", "Notes you haven't filed show up here."],
      archive: ["The archive is empty", "Archived notes are hidden from your library but never deleted."],
    };
    const [head, body] = messages[state.view] || (state.range !== "all"
      ? ["Nothing in that time", "Try a longer range."]
      : ["This folder is empty", "Select notes in All notes, then choose Move to… and this folder."]);
    return h("div", { class: "card empty" }, h("h3", null, head), h("p", null, body));
  }

  function folderName(id) {
    return folders.find((f) => f.id === id)?.name;
  }

  function noteCard(note, terms) {
    const pick = h("input", { type: "checkbox", checked: selected.has(note.session_id), "aria-label": `Select ${note.title}`,
      onchange: () => { if (pick.checked) selected.add(note.session_id); else selected.delete(note.session_id); drawBulk(); } });
    const star = h("button", { class: "star", type: "button", "aria-pressed": String(note.starred), "aria-label": note.starred ? `Remove star from ${note.title}` : `Star ${note.title}`, onclick: () => toggleStar(note, star) }, note.starred ? "★" : "☆");
    const when = [shortDay(note.memory_day), note.started_at ? clockTime(Date.parse(note.started_at)) : null].filter(Boolean).join(" · ");
    const facts = noteFacts(note);
    const where = note.folder_id && state.view !== note.folder_id ? folderName(note.folder_id) : null;
    const text = note.snippet || note.preview;
    return h("article", { class: `note-card${note.starred ? " starred" : ""}` },
      h("label", { class: "pick" }, pick),
      h("div", { class: "note-body" },
        h("h3", { class: "m0" }, h("a", { href: `#/notes/${note.session_id}` }, marked(note.title, terms))),
        h("p", { class: "facts" }, [when, facts].filter(Boolean).join(" · "), where ? h("span", { class: "chip" }, where) : null),
        text ? h("p", { class: `preview${note.snippet ? " snippet" : ""}` }, marked(text, terms)) : null),
      star);
  }

  async function toggleStar(note, button) {
    button.disabled = true;
    try {
      const updated = await api(`/notes/${note.session_id}`, { method: "PATCH", body: { starred: !note.starred } });
      Object.assign(note, updated);
      const entry = cards.get(note.session_id);
      if (state.view === "starred" && !note.starred) { entry?.card.remove(); cards.delete(note.session_id); }
      else if (entry) { const fresh = noteCard(note, searchTerms(state.q)); entry.card.replaceWith(fresh); entry.card = fresh; }
      refreshFolders();
    } catch (e) {
      button.disabled = false;
      sub.textContent = describe(e);
    }
  }

  // --- selecting and organizing many at once ---------------------------------------------------

  function drawBulk() {
    bulk.hidden = selected.size === 0;
    if (!selected.size) { bulk.replaceChildren(); return; }
    const move = h("select", { "aria-label": "Move selected notes to a folder", onchange: async () => {
      const value = move.value;
      move.value = "";
      if (value === "new") return moveToNewFolder();
      if (value) await change({ folder_id: value === "none" ? null : value });
    } },
      h("option", { value: "" }, "Move to…"), h("option", { value: "none" }, "No folder"),
      folders.map((f) => h("option", { value: f.id }, f.name)), h("option", { value: "new" }, "New folder…"));
    bulk.replaceChildren(
      h("strong", null, `${selected.size} selected`), move,
      state.view === "archive" ? null : h("button", { class: "small", type: "button", onclick: () => change({ starred: true }) }, "★ Star"),
      h("button", { class: "small", type: "button", onclick: () => change({ archived: state.view !== "archive" }) }, state.view === "archive" ? "Restore" : "Archive"),
      h("button", { class: "small", type: "button", onclick: () => { selected.clear(); load(true); } }, "Clear"));
  }

  async function moveToNewFolder() {
    let made = null;
    const saved = await promptName({ title: "New folder", label: "Folder name", action: "Create and move", submit: async (name) => {
      const result = await createFolder(name);
      made = result.folder;
      return result.problem || null;
    } });
    if (saved && made) await change({ folder_id: made.id });
  }

  async function change(patch) {
    try {
      await api("/notes/bulk", { method: "POST", body: { session_ids: [...selected], ...patch } });
    } catch (e) {
      sub.textContent = describe(e);
      return;
    }
    selected.clear();
    await refreshFolders();
    load(true);
  }

  // --- wiring ----------------------------------------------------------------------------------

  let timer;
  search.addEventListener("input", () => {
    searchClear.hidden = !search.value;
    clearTimeout(timer);
    timer = setTimeout(() => { state.q = search.value; remember(); load(true); }, 250);
  });
  search.addEventListener("keydown", (event) => { if (event.key === "Escape" && search.value) { search.value = ""; state.q = ""; searchClear.hidden = true; remember(); load(true); } });
  sort.addEventListener("change", () => { state.sort = sort.value; remember(); load(true); });
  range.addEventListener("change", () => { state.range = range.value; remember(); load(true); });

  drawSide();
  drawHeader();
  remember();
  // Folders first, so the cards can name the folder they are in. If a stale folder id (a
  // bookmark to a since-deleted folder) made refreshFolders() correct the view and reload
  // already, don't fetch the list a second time right behind it.
  const alreadyLoaded = await refreshFolders();
  if (!alreadyLoaded) await load(true);
  return h("div", { class: "library" },
    side,
    h("section", { class: "notes-main", "aria-labelledby": "notes-title" },
      h("div", { class: "notes-head" }, h("div", null, title, sub), tools),
      h("div", { class: "toolbar" }, h("div", { class: "grow" }, h("label", { for: "notes-q", class: "sr-only" }, "Search your notes"), searchControl),
        h("label", { for: "notes-sort", class: "sr-only" }, "Sort"), sort, h("label", { for: "notes-range", class: "sr-only" }, "When"), range),
      bulk, list, more));
}
