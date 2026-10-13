import { api, describe } from "../api.js";
import { h, notice } from "../dom.js";

const back = () => h("a", { href: "#/" }, "Back to sign in");

function shell(...content) {
  return h("main", { id: "main" }, h("div", { class: "card narrow" }, ...content));
}

// Ask for a reset link. The server answers the same whether or not the address has an account.
export function forgotView() {
  const status = h("div", { "aria-live": "polite" });
  const email = h("input", { type: "email", id: "fe", autocomplete: "email", required: true });
  const send = h("button", { class: "primary", type: "submit" }, "Send reset link");
  return shell(
    h("h1", null, "Reset your password"),
    h("p", { class: "lede" }, "Enter your email and we'll send a link that works for one hour."),
    h("form", { novalidate: true, onsubmit: async (event) => {
      event.preventDefault();
      if (!email.value.includes("@")) return status.replaceChildren(notice("error", "Enter the email you signed up with."));
      send.disabled = true;
      try {
        await api("/password/forgot", { method: "POST", auth: false, body: { email: email.value.trim() } });
        status.replaceChildren(notice("ok", "If that address has an account, a link is on its way. Check your spam folder too."));
      } catch (e) {
        status.replaceChildren(notice("error", describe(e)));
        send.disabled = false;
      }
    } }, h("label", { for: "fe" }, "Email"), email, status,
    h("div", { class: "row mt16" }, send, back())));
}

// The page the emailed link opens: choose a new password.
export function resetView(token) {
  const status = h("div", { "aria-live": "polite" });
  const pw = h("input", { type: "password", id: "np", autocomplete: "new-password", minlength: 12, required: true });
  const done = h("div");
  return shell(
    h("h1", null, "Choose a new password"),
    h("form", { novalidate: true, onsubmit: async (event) => {
      event.preventDefault();
      if (pw.value.length < 12) return status.replaceChildren(notice("error", "Use at least 12 characters."));
      try {
        await api("/password/reset", { method: "POST", auth: false, body: { token, new_password: pw.value } });
        done.replaceChildren(notice("ok", "Password changed. Your other browsers were signed out."), h("p", null, back()));
        status.replaceChildren();
        event.target.hidden = true;
      } catch (e) { status.replaceChildren(notice("error", describe(e))); }
    } }, h("label", { for: "np" }, "New password"), pw,
    h("p", { class: "field-hint" }, "At least 12 characters."), status,
    h("div", { class: "row mt16" }, h("button", { class: "primary", type: "submit" }, "Change password"))), done);
}

export async function verifyView(token) {
  const result = h("div");
  try {
    await api("/verify", { method: "POST", auth: false, body: { token } });
    result.append(notice("ok", "Your email is confirmed."));
  } catch (e) {
    result.append(notice("error", describe(e)));
  }
  return shell(h("h1", null, "Confirm your email"), result, h("p", null, h("a", { href: "#/notes" }, "Continue")));
}
