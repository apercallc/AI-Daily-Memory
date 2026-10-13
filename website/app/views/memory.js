import { api, describe } from "../api.js";
import { h, notice, prettyDay, spinner } from "../dom.js";
import { itemCard, KIND_LABELS } from "./item.js";

const PAGE = 30;

export async function memoryView() {
  const state = { q: "", kind: "", days: "all", showDismissed: false, offset: 0 };
  const list = h("div", { "aria-live": "polite" });
  const more = h("div", { class: "row end mt14" });
  const count = h("p", { class: "faint" });

  const search = h("input", { type: "search", id: "q", placeholder: "Search your items", "aria-label": "Search your items" });
  const kind = h("select", { id: "kind", "aria-label": "Kind" }, h("option", { value: "" }, "All kinds"),
    Object.entries(KIND_LABELS).map(([value, label]) => h("option", { value }, label)));
  const range = h("select", { id: "range", "aria-label": "When" },
    [["all", "Any time"], ["7", "Last 7 days"], ["30", "Last 30 days"], ["90", "Last 90 days"]].map(([v, l]) => h("option", { value: v }, l)));
  const dismissed = h("input", { type: "checkbox", id: "dismissed" });

  let timer;
  const refresh = () => { state.q = search.value.trim(); state.kind = kind.value; state.days = range.value; state.showDismissed = dismissed.checked; state.offset = 0; load(true); };
  search.addEventListener("input", () => { clearTimeout(timer); timer = setTimeout(refresh, 250); });
  for (const el of [kind, range, dismissed]) el.addEventListener("change", refresh);

  function query() {
    const p = new URLSearchParams({ limit: String(PAGE), offset: String(state.offset) });
    if (state.q) p.set("q", state.q);
    if (state.kind) p.set("kind", state.kind);
    if (state.days !== "all") { const d = new Date(); d.setDate(d.getDate() - Number(state.days)); p.set("from", d.toISOString().slice(0, 10)); }
    if (state.showDismissed) p.set("include_dismissed", "true");
    return p;
  }

  let lastDay = null;
  async function load(reset) {
    if (reset) { list.replaceChildren(spinner("Searching…")); lastDay = null; }
    more.replaceChildren();
    let page;
    try {
      page = await api(`/memory/items?${query()}`);
    } catch (e) {
      list.replaceChildren(notice("error", describe(e)));
      more.append(h("button", { onclick: () => load(reset) }, "Try again"));
      return;
    }
    if (reset) list.replaceChildren();
    if (reset && !page.items.length) {
      list.append(h("div", { class: "card empty" }, h("h3", null, state.q || state.kind ? "No matches" : "No memory yet"),
        h("p", null, state.q || state.kind ? "Try fewer words, or clear the filters." : "Decisions, actions and ideas appear here once summaries are on in the phone app and a day has synced. Your recordings are under Notes.")));
      count.textContent = "";
      return;
    }
    for (const item of page.items) {
      if (item.memory_day !== lastDay) { lastDay = item.memory_day; list.append(h("h2", { class: "day-head" }, prettyDay(item.memory_day))); }
      list.append(itemCard(item, { noteLink: true }));
    }
    state.offset += page.items.length;
    count.textContent = `${Math.min(state.offset, page.total)} of ${page.total}`;
    if (state.offset < page.total) more.append(h("button", { onclick: () => load(false) }, "Show more"));
  }
  await load(true);
  return h("div", null, h("h1", null, "Items"), h("p", { class: "lede" }, "Every decision, action and idea pulled from your recordings, across all your notes. Fix a wording, or mark what isn't relevant — the evidence behind it is never changed."),
    h("div", { class: "toolbar" }, h("div", { class: "grow" }, search), kind, range),
    h("div", { class: "row" }, dismissed, h("label", { for: "dismissed", class: "plainlabel" }, "Show items I marked not relevant"), count),
    list, more);
}
