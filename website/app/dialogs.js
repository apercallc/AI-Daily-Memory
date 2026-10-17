// Small modal prompts built on <dialog>, so focus is trapped and Escape closes them for free.
import { h } from "./dom.js";

function open(dialog, focus) {
  document.body.append(dialog);
  dialog.addEventListener("close", () => dialog.remove());
  dialog.showModal();
  focus?.focus();
}

// Asks for a name (a folder, say). `submit(name)` does the work and returns an error sentence to
// keep the dialog open, or null when it worked. Resolves true if it worked, false if cancelled.
export function promptName({ title, label, initial = "", action = "Save", submit }) {
  return new Promise((resolve) => {
    let done = false;
    const input = h("input", { type: "text", id: "dlg-name", maxlength: 60, value: initial, autocomplete: "off", required: true });
    const status = h("div", { "aria-live": "polite" });
    const save = h("button", { class: "primary", type: "submit" }, action);
    const finish = (result) => { done = true; resolve(result); dialog.close(); };
    const dialog = h("dialog", { "aria-labelledby": "dlg-title" },
      h("form", {
        onsubmit: async (event) => {
          event.preventDefault();
          const name = input.value.trim();
          if (!name) { status.replaceChildren(h("p", { class: "notice error", role: "alert" }, "Give it a name.")); return; }
          save.disabled = true;
          const problem = await submit(name);
          save.disabled = false;
          if (problem) { status.replaceChildren(h("p", { class: "notice error", role: "alert" }, problem)); input.focus(); return; }
          finish(true);
        },
      },
      h("h2", { id: "dlg-title", class: "mt0" }, title),
      h("label", { for: "dlg-name" }, label), input, status,
      h("div", { class: "row end mt16" }, h("button", { type: "button", onclick: () => finish(false) }, "Cancel"), save)));
    dialog.addEventListener("close", () => { if (!done) resolve(false); });
    open(dialog, input);
    input.select();
  });
}

// A yes/no question. Resolves true only on the confirm button.
// Initial focus goes to Cancel, never the confirm/danger button: a stray Enter or Space right
// after the dialog opens (keyboard or switch-access input) must not fire the destructive action.
export function confirmBox({ title, body, action, danger = false }) {
  return new Promise((resolve) => {
    let answer = false;
    const yes = h("button", { class: danger ? "danger" : "primary", onclick: () => { answer = true; dialog.close(); } }, action);
    const no = h("button", { onclick: () => dialog.close() }, "Cancel");
    const dialog = h("dialog", { "aria-labelledby": "dlg-title" },
      h("h2", { id: "dlg-title", class: "mt0" }, title), h("p", null, body),
      h("div", { class: "row end mt16" }, no, yes));
    dialog.addEventListener("close", () => resolve(answer));
    open(dialog, no);
  });
}
