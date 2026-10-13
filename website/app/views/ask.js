import { api, describe } from "../api.js";
import { h, localDay, notice, spinner } from "../dom.js";
import { KIND_LABELS } from "./item.js";

const IDEAS = ["What did I decide yesterday?", "What did I promise this week?", "What open questions do I have?", "What are my action items from last week?"];

export async function askView() {
  const out = h("div", { "aria-live": "polite" });
  const box = h("textarea", { id: "question", maxlength: 500, placeholder: "What did I promise Sam?", "aria-label": "Your question" });
  const send = h("button", { class: "primary", type: "submit" }, "Ask");

  async function ask(question) {
    const text = question.trim();
    if (text.length < 3) return out.replaceChildren(notice("error", "Ask a question of at least a few words."));
    send.disabled = true;
    out.replaceChildren(spinner("Looking through your memory…"));
    try {
      const r = await api("/ask", { method: "POST", body: { question: text, today: localDay() } });
      out.replaceChildren(answerCard(r));
    } catch (e) {
      out.replaceChildren(notice("error", describe(e)));
    } finally {
      send.disabled = false;
    }
  }

  const form = h("form", { onsubmit: (event) => { event.preventDefault(); ask(box.value); } },
    h("label", { for: "question" }, "Ask your memory"), box,
    h("div", { class: "row mt10" }, send, h("span", { class: "faint" }, "Answers use only what's in your memory, and say so when there's nothing.")));
  box.addEventListener("keydown", (event) => { if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) { event.preventDefault(); ask(box.value); } });

  return h("div", null, h("h1", null, "Ask"), h("p", { class: "lede" }, "Ask about your days in plain words. Try a time (“yesterday”, “last week”), a person, or a kind of thing (decisions, promises)."),
    form,
    h("div", { class: "chips" }, IDEAS.map((idea) => h("button", { type: "button", onclick: () => { box.value = idea; ask(idea); } }, idea))),
    out);
}

function answerCard(r) {
  return h("section", { class: "answer", "aria-label": "Answer" },
    r.range_label ? h("p", { class: "faint" }, `Looking at ${r.range_label}`) : null,
    // Without a model the server's answer is a list of the same items shown below; keep its lead line.
    h("p", null, r.source === "evidence_only" && !r.abstained ? r.answer.split("\n")[0] : r.answer),
    r.items.length ? h("div", null, h("h3", { class: "mt14" }, `Based on ${r.items.length} ${r.items.length === 1 ? "item" : "items"}`),
      r.items.map((i) => h("div", { class: "item mt8" },
        h("div", { class: "top" }, h("span", { class: `pill ${i.kind}` }, KIND_LABELS[i.kind] || i.kind), h("p", { class: "text" }, i.text)),
        h("div", { class: "meta" }, h("a", { href: `#/today?day=${i.day}` }, i.day), i.counterpart ? ` · ${i.counterpart}` : "", i.due ? ` · due ${i.due}` : "")))) : null,
    r.notes?.length ? h("div", null, h("h3", { class: "mt14" }, `Mentioned in ${r.notes.length} ${r.notes.length === 1 ? "note" : "notes"}`),
      r.notes.map((n) => h("a", { class: "item mention", href: `#/notes/${n.session_id}` },
        h("strong", null, n.title), h("span", { class: "meta" }, ` · ${n.memory_day}`),
        n.snippet ? h("p", { class: "m0 muted" }, n.snippet) : null))) : null,
    r.abstained && !r.notes?.length ? h("p", { class: "faint" }, "Nothing was guessed. If you expected something here, look through your notes for the day.") : null,
    r.source === "evidence_only" && !r.abstained ? h("p", { class: "faint" }, "These are the matching items. A written answer isn't enabled on this server.") : null);
}
