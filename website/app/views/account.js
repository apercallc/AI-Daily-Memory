import { api, describe, session } from "../api.js";
import { confirmBox } from "../dialogs.js";
import { h, notice, prettyTime, spinner } from "../dom.js";

export async function accountView({ me, signOut }) {
  const caps = await api("/capabilities", { auth: false }).catch(() => ({ email: false, billing: false }));
  const parts = [
    h("h1", null, "Account"),
    h("p", { class: "lede" }, me.email ? `Signed in as ${me.email}.` : "Signed in."),
    verifyBanner(me, caps),
    caps.billing ? await planCard() : null,
    phoneCard(me),
    await signInsCard(),
    await keysCard(),
    passwordCard(),
    exportCard(),
    deleteCard(signOut),
  ];
  return h("div", null, parts);
}

function verifyBanner(me, caps) {
  if (!caps.email || me.email_verified || !me.email) return null;
  const status = h("div", { "aria-live": "polite" });
  return h("div", { class: "notice warn" }, h("strong", null, "Confirm your email. "),
    "It lets you reset your password if you forget it. ",
    h("button", { class: "link", onclick: async () => {
      try { await api("/me/verify", { method: "POST" }); status.replaceChildren(notice("ok", "Sent. Check your inbox.")); } catch (e) { status.replaceChildren(notice("error", describe(e))); }
    } }, "Send the email"), status);
}

async function planCard() {
  const status = h("div", { "aria-live": "polite" });
  let b;
  try { b = await api("/billing"); } catch (e) { return section("Plan", notice("error", describe(e))); }
  const go = async (path) => {
    try {
      const { url } = await api(path, { method: "POST" });
      if (url.startsWith("https://")) location.assign(url);
    } catch (e) { status.replaceChildren(notice("error", describe(e))); }
  };
  const pro = b.plan === "pro";
  return section("Plan",
    h("p", { class: "mt0" }, h("strong", null, pro ? "Pro" : "Free"),
      pro && b.renews_at ? ` — ${b.cancels_at_period_end ? "ends" : "renews"} ${prettyTime(b.renews_at)}` : ""),
    h("p", { class: "muted" }, `Questions today: ${b.asks_used} of ${b.ask_limit}.`),
    pro ? h("button", { onclick: () => go("/billing/portal") }, "Manage or cancel")
        : h("button", { class: "primary", onclick: () => go("/billing/checkout") }, "Upgrade to Pro"),
    status);
}

function section(title, ...children) {
  return h("section", { class: "card" }, h("h2", { class: "mt0" }, title), ...children);
}

function phoneCard() {
  return section("Connect your phone",
    h("ol", { class: "olist" },
      h("li", null, "Install AI Daily Memory on your Android phone from the ", h("a", { href: "/#get-it" }, "download page"), "."),
      h("li", null, "Open it and go to Settings → Account & server."),
      h("li", null, "Choose Sign in and use this same email and password."),
      h("li", null, "Leave Sync on under Settings → Daily reports. Turn on “Also sync full transcripts” if you want to read and search the words here."),
    ),
    h("p", { class: "faint" }, "Your phone turns speech into text itself. Once connected, it sends your notes here, never audio, so you can read and search them on the web."));
}

async function signInsCard() {
  const body = h("div");
  async function load() {
    body.replaceChildren(spinner("Loading…"));
    try {
      const rows = (await api("/tokens")).filter((t) => t.kind === "device");
      body.replaceChildren(table(["Name", "Last used", ""], rows.map((t) => [
        `${t.name}${t.current ? " (this browser)" : ""}`, prettyTime(t.last_used_at),
        t.current ? "" : h("button", { class: "small danger", onclick: async () => {
          if (!(await confirmBox({ title: `Sign out “${t.name}”?`, body: "That device will need to sign in again.", action: "Sign out", danger: true }))) return;
          try { await api(`/tokens/${t.id}`, { method: "DELETE" }); } catch (e) { body.prepend(notice("error", describe(e))); return; }
          load();
        } }, "Sign out"),
      ])));
    } catch (e) { body.replaceChildren(notice("error", describe(e))); }
  }
  await load();
  return section("Signed-in devices", h("p", { class: "muted mt0" }, "Your phone and every browser that is signed in. Remove one you don't recognise or have lost."), body);
}

async function keysCard() {
  const body = h("div");
  const created = h("div", { "aria-live": "polite" });
  const name = h("input", { type: "text", id: "keyname", maxlength: 80, placeholder: "e.g. My notes script", "aria-label": "Key name" });
  async function load() {
    try {
      const keys = await api("/api-keys");
      body.replaceChildren(keys.length ? table(["Name", "Last used", ""], keys.map((k) => [k.name, prettyTime(k.last_used_at),
        h("button", { class: "small danger", onclick: async () => {
          if (!(await confirmBox({ title: `Revoke “${k.name}”?`, body: "Anything using it stops working.", action: "Revoke", danger: true }))) return;
          try { await api(`/tokens/${k.id}`, { method: "DELETE" }); } catch (e) { body.prepend(notice("error", describe(e))); return; }
          load();
        } }, "Revoke")]))
        : h("p", { class: "faint" }, "No keys yet."));
    } catch (e) { body.replaceChildren(notice("error", describe(e))); }
  }
  await load();
  const make = h("button", { onclick: async () => {
    if (!name.value.trim()) return created.replaceChildren(notice("error", "Give the key a name so you can tell it apart later."));
    make.disabled = true;
    try {
      const key = await api("/api-keys", { method: "POST", body: { name: name.value.trim() } });
      name.value = "";
      created.replaceChildren(h("div", { class: "notice warn" }, h("strong", null, "Copy this key now. It is shown once."),
        h("div", { class: "secret", tabindex: "0" }, key.token),
        h("p", { class: "faint mt8" }, `Try it: curl -H "Authorization: Bearer KEY" ${location.origin}/v1/memory/items`)));
      await load();
    } catch (e) { created.replaceChildren(notice("error", describe(e))); } finally { make.disabled = false; }
  } }, "Create key");
  return section("API keys", h("p", { class: "muted mt0" }, "Let your own scripts and tools read your memory. Keys are read-only, can't read transcripts, and can be revoked at any time."),
    h("div", { class: "row" }, h("div", { class: "fl-240" }, name), make), created, h("div", { class: "mt12" }, body));
}

function passwordCard() {
  const status = h("div", { "aria-live": "polite" });
  const current = h("input", { type: "password", id: "cur", autocomplete: "current-password" });
  const next = h("input", { type: "password", id: "new", autocomplete: "new-password", minlength: 12 });
  return section("Change password",
    h("p", { class: "muted mt0" }, "Other browsers are signed out. Your phone stays connected."),
    h("form", { novalidate: true, onsubmit: async (event) => {
      event.preventDefault();
      if (next.value.length < 12) return status.replaceChildren(notice("error", "Use at least 12 characters."));
      try {
        await api("/me/password", { method: "POST", body: { current_password: current.value, new_password: next.value } });
        current.value = next.value = "";
        status.replaceChildren(notice("ok", "Password changed."));
      } catch (e) { status.replaceChildren(notice("error", e.code === "invalid_credentials" ? "Your current password isn't right." : describe(e))); }
    } },
      h("label", { for: "cur" }, "Current password"), current,
      h("label", { for: "new" }, "New password"), next,
      status, h("div", { class: "row mt12" }, h("button", { type: "submit" }, "Change password"))));
}

function exportCard() {
  const status = h("div", { "aria-live": "polite" });
  return section("Download your data",
    h("p", { class: "muted mt0" }, "Everything this account holds — memory with your corrections, daily reports, transcripts kept here, and sign-in history — as one JSON file. Recordings that were processed are already gone by design."),
    h("button", { onclick: async (event) => {
      const button = event.currentTarget;
      button.disabled = true;
      status.replaceChildren(spinner("Preparing your file…"));
      try {
        const response = await api("/export", { raw: true });
        const blob = await response.blob();
        const url = URL.createObjectURL(blob);
        const link = h("a", { href: url, download: `ai-daily-memory-export-${new Date().toISOString().slice(0, 10)}.json` });
        document.body.append(link);
        link.click();
        link.remove();
        setTimeout(() => URL.revokeObjectURL(url), 10_000);
        status.replaceChildren(notice("ok", "Your file is downloading."));
      } catch (e) { status.replaceChildren(notice("error", e.status === 429 ? "You can export three times an hour. Try again later." : describe(e))); }
      finally { button.disabled = false; }
    } }, "Download JSON"), status);
}

function deleteCard(signOut) {
  const dialog = h("dialog", { "aria-labelledby": "del-title" });
  const password = h("input", { type: "password", id: "del-pw", autocomplete: "current-password" });
  const status = h("div", { "aria-live": "polite" });
  dialog.append(h("h2", { id: "del-title", class: "mt0" }, "Delete your account?"),
    h("p", null, "This permanently deletes your memory, reports, transcripts, keys and sign-ins from this service. Recordings on your phone are not touched. It can't be undone."),
    h("label", { for: "del-pw" }, "Confirm with your password"), password, status,
    h("div", { class: "row end mt16" },
      h("button", { onclick: () => dialog.close() }, "Keep my account"),
      h("button", { class: "danger", onclick: async () => {
        try {
          await api("/me", { method: "DELETE", body: { password: password.value } });
          session.clear();
          await signOut();
        } catch (e) { status.replaceChildren(notice("error", e.code === "password_required" ? "That password isn't right." : describe(e))); }
      } }, "Delete everything")));
  return section("Delete account",
    h("p", { class: "muted mt0" }, "Removes everything this service holds about you: your notes, highlights and transcripts. Your phone keeps its own copy."),
    h("button", { class: "danger", onclick: () => { status.replaceChildren(); password.value = ""; dialog.showModal(); password.focus(); } }, "Delete my account…"), dialog);
}

function table(heads, rows) {
  return h("div", { class: "xscroll" }, h("table", null,
    h("thead", null, h("tr", null, heads.map((x) => h("th", { scope: "col" }, x)))),
    h("tbody", null, rows.map((r) => h("tr", null, r.map((c) => h("td", null, c)))))));
}
