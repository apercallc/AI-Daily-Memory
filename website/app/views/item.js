import { api, describe } from "../api.js";
import { h, notice, prettyDay } from "../dom.js";

export const KIND_LABELS = {
  decision: "Decision", commitment: "Commitment", action: "Action", idea: "Idea", question: "Open question",
};

function meta(item) {
  const bits = [];
  if (item.kind === "commitment" && item.counterpart) bits.push(item.direction === "i_owe" ? `You owe ${item.counterpart}` : `${item.counterpart} owes you`);
  else if (item.owner && item.owner !== "me") bits.push(`Owner: ${item.owner}`);
  if (item.due) bits.push(`Due ${prettyDay(item.due)}`);
  if (item.confidence !== "confirmed") bits.push(item.confidence === "likely" ? "Likely" : "Possible");
  if (item.corrected) bits.push("Your wording");
  return bits.join(" · ");
}

// One memory item with its actions: edit your own wording, dismiss/restore, and see the evidence.
// `onChange(updated)` lets the list decide whether a dismissed item should leave the view.
export function itemCard(initial, { onChange, showDay = false, noteLink = false } = {}) {
  let item = initial;
  const card = h("article", { class: "item" });

  async function patch(body) {
    return api(`/memory/items/${item.id}`, { method: "PATCH", body });
  }

  function draw({ message, editing = false, evidence } = {}) {
    card.className = `item${item.dismissed ? " dismissed" : ""}`;
    card.replaceChildren(...[
      h("div", { class: "top" },
        h("span", { class: `pill ${item.kind}` }, KIND_LABELS[item.kind] || item.kind),
        h("p", { class: "text" }, item.text),
      ),
      meta(item) || showDay ? h("div", { class: "meta" }, [showDay ? prettyDay(item.memory_day) : null, showDay && meta(item) ? " · " : null, meta(item)]) : null,
      editing ? editor() : null,
      message ? notice("error", message) : null,
      evidence ? evidenceBlock(evidence) : null,
      editing ? null : h("div", { class: "actions" },
        h("button", { class: "small", onclick: () => draw({ editing: true }) }, "Edit wording"),
        h("button", { class: "small", onclick: toggleDismiss }, item.dismissed ? "Restore" : "Not relevant"),
        h("button", { class: "small", "aria-expanded": evidence ? "true" : "false", onclick: () => (evidence ? draw() : showEvidence()) }, "Evidence"),
        noteLink ? h("a", { class: "btn small", href: `#/notes/${item.session_id}` }, "Open note") : null,
      ),
    ].filter(Boolean));
  }

  function editor() {
    const area = h("textarea", { id: `edit-${item.id}`, maxlength: 280, "aria-label": "Your wording" }, item.text);
    queueMicrotask(() => area.focus());
    return h("div", { class: "stack mt10" }, area,
      h("div", { class: "row" },
        h("button", { class: "primary small", onclick: async () => {
          const text = area.value.trim();
          if (!text) return draw({ editing: true, message: "Write something, or restore the original." });
          try { item = await patch({ text }); onChange?.(item); draw(); } catch (e) { draw({ editing: true, message: describe(e) }); }
        } }, "Save"),
        item.corrected ? h("button", { class: "small", onclick: async () => {
          try { item = await patch({ clear_text: true }); onChange?.(item); draw(); } catch (e) { draw({ editing: true, message: describe(e) }); }
        } }, "Restore original") : null,
        h("button", { class: "small", onclick: () => draw() }, "Cancel")),
      h("p", { class: "field-hint" }, "Your wording is saved next to the original. The original and its evidence are never changed."));
  }

  async function toggleDismiss() {
    try { item = await patch({ dismissed: !item.dismissed }); onChange?.(item); draw(); } catch (e) { draw({ message: describe(e) }); }
  }

  async function showEvidence() {
    try {
      const lines = await api(`/memory/items/${item.id}/evidence`);
      draw({ evidence: lines });
    } catch (e) {
      draw({ message: describe(e) });
    }
  }

  function evidenceBlock(lines) {
    if (!lines.length) return h("p", { class: "faint" }, "No transcript is kept on the server for this item. The words stay on your phone, or were summarized there.");
    return h("div", null, lines.map((line) => h("blockquote", null, line.quote || line.text, line.speaker ? h("span", { class: "faint" }, ` — speaker ${line.speaker}`) : null)));
  }

  draw();
  return card;
}
