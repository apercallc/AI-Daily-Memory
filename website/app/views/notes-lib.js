// The parts of the notes screens that are plain logic, kept apart from the DOM so they can be tested.
import { addDays, localDay } from "../dom.js";

export const SORTS = [
  ["newest", "Newest first"],
  ["oldest", "Oldest first"],
  ["title", "Title A–Z"],
  ["longest", "Longest first"],
];
export const RANGES = [
  ["all", "Any time"],
  ["7", "Last 7 days"],
  ["30", "Last 30 days"],
  ["90", "Last 90 days"],
];
export const BUILT_IN_VIEWS = ["all", "starred", "unfiled", "archive"];
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const DEFAULTS = { view: "all", q: "", sort: "newest", range: "all" };

export function isView(value) {
  return BUILT_IN_VIEWS.includes(value) || UUID.test(value || "");
}

// What the library shows is kept in the address (`#/notes?view=…&q=…`) so reloading, going back from
// a note and sharing the link all land on the same list.
export function parseState(hash) {
  const p = new URLSearchParams(hash.split("?")[1] || "");
  const sort = p.get("sort");
  const range = p.get("range");
  return {
    view: isView(p.get("view")) ? p.get("view") : DEFAULTS.view,
    q: (p.get("q") || "").slice(0, 200),
    sort: SORTS.some(([v]) => v === sort) ? sort : DEFAULTS.sort,
    range: RANGES.some(([v]) => v === range) ? range : DEFAULTS.range,
  };
}

export function stateToHash(state) {
  const p = new URLSearchParams();
  for (const key of Object.keys(DEFAULTS)) if (state[key] && state[key] !== DEFAULTS[key]) p.set(key, state[key]);
  const text = p.toString();
  return text ? `#/notes?${text}` : "#/notes";
}

// The API query for a state. `today` is injectable so tests don't depend on the date.
export function listParams(state, { limit, offset, today = localDay() }) {
  const p = new URLSearchParams({ limit: String(limit), offset: String(offset), sort: state.sort });
  if (state.q.trim()) p.set("q", state.q.trim());
  if (state.view === "starred") p.set("starred", "true");
  else if (state.view === "archive") p.set("archived", "true");
  else if (state.view === "unfiled") p.set("folder", "none");
  else if (state.view !== "all") p.set("folder", state.view);
  if (state.range !== "all") p.set("from", addDays(today, -Number(state.range)));
  return p;
}

export function searchTerms(q) {
  return q.split(/\s+/).filter(Boolean).slice(0, 6);
}

// Splits text into pieces, marking the ones that match a search word (any case). Nothing is ever
// treated as markup: callers turn these into text nodes and <mark> elements.
export function highlight(text, terms) {
  const words = [...new Set(terms.map((t) => t.toLowerCase()).filter(Boolean))];
  if (!words.length) return [{ text, hit: false }];
  const lower = text.toLowerCase();
  const spans = [];
  for (const word of words) {
    for (let at = lower.indexOf(word); at !== -1; at = lower.indexOf(word, at + word.length)) spans.push([at, at + word.length]);
  }
  spans.sort((a, b) => a[0] - b[0] || b[1] - a[1]);
  const merged = [];
  for (const span of spans) {
    const last = merged[merged.length - 1];
    if (last && span[0] <= last[1]) last[1] = Math.max(last[1], span[1]);
    else merged.push([...span]);
  }
  const pieces = [];
  let cursor = 0;
  for (const [from, to] of merged) {
    if (from > cursor) pieces.push({ text: text.slice(cursor, from), hit: false });
    pieces.push({ text: text.slice(from, to), hit: true });
    cursor = to;
  }
  if (cursor < text.length) pieces.push({ text: text.slice(cursor), hit: false });
  return pieces;
}

export function clockTime(epochMs) {
  return new Date(epochMs).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });
}

export function count(n, singular, plural = `${singular}s`) {
  return `${n.toLocaleString()} ${n === 1 ? singular : plural}`;
}

// "3 items · 340 words", leaving out what a note doesn't have.
export function noteFacts(note) {
  const bits = [];
  if (note.item_count) bits.push(count(note.item_count, "item"));
  if (note.word_count) bits.push(count(note.word_count, "word"));
  return bits.join(" · ");
}

export function viewTitle(view, folders) {
  if (view === "all") return "All notes";
  if (view === "starred") return "Starred";
  if (view === "unfiled") return "Not in a folder";
  if (view === "archive") return "Archive";
  return folders.find((f) => f.id === view)?.name || "Folder";
}

// A plain-text copy of a note: title, day, then one line per spoken line with its clock time.
export function transcriptText(note, lines) {
  const head = [note.title, note.memory_day, ""];
  const body = lines.map((l) => `${clockTime(l.start_epoch_ms)}${l.speaker ? ` ${l.speaker}` : ""}: ${l.text}`);
  return [...head, ...body, ""].join("\n");
}

export function fileName(note) {
  const slug = note.title.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 50);
  return `${note.memory_day}-${slug || "note"}.txt`;
}
