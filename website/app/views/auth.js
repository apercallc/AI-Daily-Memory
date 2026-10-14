import { api, describe, session } from "../api.js";
import { h, notice, clear } from "../dom.js";

export function authView({ onSignedIn }) {
  let mode = "signin";
  const form = h("div");

  function draw(message) {
    clear(form);
    const creating = mode === "signup";
    const email = h("input", { type: "email", id: "email", autocomplete: "email", required: true, inputmode: "email" });
    const password = h("input", { type: "password", id: "password", required: true, minlength: creating ? 12 : 1,
      autocomplete: creating ? "new-password" : "current-password" });
    const stay = h("input", { type: "checkbox", id: "stay" });
    const submit = h("button", { class: "primary", type: "submit" }, creating ? "Create account" : "Sign in");
    const error = h("div", { "aria-live": "polite" }, message ? notice("error", message) : null);

    const el = h("form", {
      novalidate: true,
      onsubmit: async (event) => {
        event.preventDefault();
        if (!email.value.trim() || !password.value) return draw("Enter your email and password.");
        if (creating && password.value.length < 12) return draw("Use at least 12 characters.");
        submit.disabled = true;
        submit.textContent = creating ? "Creating…" : "Signing in…";
        try {
          const { token } = await api(creating ? "/signup" : "/login", {
            method: "POST", auth: false,
            body: { email: email.value.trim(), password: password.value, device_name: "Web browser", client: "web" },
          });
          session.set(token, stay.checked);
          await onSignedIn();
        } catch (e) {
          draw(describe(e));
        }
      },
    },
      h("label", { for: "email" }, "Email"), email,
      h("label", { for: "password" }, "Password"), password,
      creating ? h("p", { class: "field-hint" }, "At least 12 characters. A passphrase of a few words works well.") : null,
      h("div", { class: "row mt14" }, stay, h("label", { for: "stay", class: "plainlabel" }, "Keep me signed in on this device")),
      error,
      h("div", { class: "row mt16" }, submit,
        h("button", { type: "button", class: "link", onclick: () => { mode = creating ? "signin" : "signup"; draw(); } },
          creating ? "I already have an account" : "Create an account")),
    );
    form.append(el);
    email.focus();
  }
  draw();

  // Offer password recovery only when this server can actually send the email.
  const forgot = h("p", { class: "mt8", hidden: true }, h("a", { href: "#/forgot" }, "Forgot your password?"));
  api("/capabilities", { auth: false }).then((c) => { forgot.hidden = !c.email; }).catch(() => {});
  return h("main", { id: "main" }, h("div", { class: "card narrow" },
    h("h1", null, "AI Daily Memory"),
    h("p", { class: "lede" }, "Review what your day added up to, correct it, and ask it questions."),
    form, forgot,
    h("p", { class: "faint mt18" },
      "Use the same email on your phone (Settings → Account & server) to connect it. Sync never includes audio. You choose whether transcript text syncs with this account; a transcription service you choose receives audio directly."),
  ));
}
