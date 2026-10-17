import { api, ApiError, describe } from "../api.js";
import { addDays, h, localDay, notice, prettyDay, spinner } from "../dom.js";
import { clockTime } from "./notes-lib.js";
import { itemCard, KIND_LABELS } from "./item.js";

const ORDER = ["decision", "commitment", "action", "idea", "question"];

export async function todayView() {
  const params = new URLSearchParams(location.hash.split("?")[1] || "");
  const dayParam = params.get("day");
  const dayValid = !dayParam || /^\d{4}-\d{2}-\d{2}$/.test(dayParam);
  let day = dayValid && dayParam ? dayParam : localDay();
  const invalidDayNotice = dayParam && !dayValid
    ? notice("warn", `“${dayParam}” isn't a date this app understands, so today is shown instead.`)
    : null;
  const body = h("div");
  const heading = h("h1");

  const go = (next) => { location.hash = next === localDay() ? "#/today" : `#/today?day=${next}`; };
  const nav = h("div", { class: "daynav" }, heading,
    h("button", { class: "small", onclick: () => go(addDays(day, -1)), "aria-label": "Previous day" }, "← Earlier"),
    h("button", { class: "small", onclick: () => go(localDay()), disabled: day === localDay() }, "Today"),
    h("button", { class: "small", onclick: () => go(addDays(day, 1)), disabled: day >= localDay(), "aria-label": "Next day" }, "Later →"));

  async function load() {
    heading.textContent = prettyDay(day);
    body.replaceChildren(spinner("Loading your day…"));
    let page;
    let report = null;
    let dayNotes = [];
    try {
      page = await api(`/memory/items?day=${day}&limit=100`);
      try {
        report = await api(`/reports/${day}`);
      } catch (e) {
        if (!(e instanceof ApiError && e.status === 404)) throw e;
      }
    } catch (e) {
      body.replaceChildren(notice("error", describe(e)), h("button", { onclick: load }, "Try again"));
      return;
    }
    let dayNotesError = null;
    try {
      dayNotes = (await api(`/notes?day=${day}&limit=50&sort=oldest`)).notes;
    } catch (e) {
      dayNotesError = e; // the day is still worth showing; the notes list just failed, distinctly from being empty
    }
    const nodes = [];
    if (dayNotes.length) {
      nodes.push(h("section", { class: "card", "aria-labelledby": "day-notes" },
        h("h2", { id: "day-notes", class: "mt0" }, dayNotes.length > 1 ? `${dayNotes.length} notes` : "1 note"),
        h("ul", { class: "olist plain" }, dayNotes.map((n) => h("li", null,
          h("a", { href: `#/notes/${n.session_id}` }, n.title),
          n.started_at ? h("span", { class: "faint" }, ` · ${clockTime(Date.parse(n.started_at))}`) : null)))));
    } else if (dayNotesError) {
      nodes.push(notice("error", `Couldn't load this day's notes. ${describe(dayNotesError)}`));
    }
    if (report?.summary || report?.headline) {
      nodes.push(h("section", { class: "card" }, report.headline ? h("h2", { class: "mt0" }, report.headline) : null,
        report.summary ? h("p", { class: "m0" }, report.summary) : null));
    }
    if (!page.items.length) {
      nodes.push(emptyState(day));
    } else {
      for (const kind of ORDER) {
        const items = page.items.filter((i) => i.kind === kind);
        if (!items.length) continue;
        nodes.push(h("h2", null, `${KIND_LABELS[kind]}${items.length > 1 ? "s" : ""}`, h("span", { class: "faint" }, ` ${items.length}`)),
          items.map((i) => itemCard(i, { noteLink: true, onChange: (updated) => { if (updated.dismissed) load(); } })));
      }
      if (page.total > page.items.length) nodes.push(h("p", { class: "faint" }, `Showing ${page.items.length} of ${page.total}. Use Memory to browse the rest.`));
    }
    body.replaceChildren(...nodes.flat());
  }
  await load();
  return h("div", null, invalidDayNotice, nav, body);
}

function emptyState(day) {
  const isToday = day === localDay();
  return h("div", { class: "card empty" },
    h("h3", null, isToday ? "Nothing here yet today" : "Nothing remembered for this day"),
    h("p", null, "Decisions and action items appear here once a day has synced and summaries are turned on in the phone app (Settings → Summaries). Your recordings themselves are under Notes."),
    h("p", null, h("a", { href: "#/notes" }, "Open your notes"), " · ", h("a", { href: "#/account" }, "How to connect your phone")),
    isToday ? null : h("p", { class: "faint" }, "Days with memory are listed under Memory."));
}
