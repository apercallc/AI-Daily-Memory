import { api, session, whenSignedOut } from "./api.js";
import { clear, h } from "./dom.js";
import { authView } from "./views/auth.js";
import { noteView } from "./views/note.js";
import { notesView } from "./views/notes.js";
import { todayView } from "./views/today.js";
import { memoryView } from "./views/memory.js";
import { askView } from "./views/ask.js";
import { accountView } from "./views/account.js";
import { forgotView, resetView, verifyView } from "./views/recover.js";

const root = document.getElementById("root");

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// #/notes is the library; #/notes/<id> is one note.
function notesRoute() {
  const id = location.hash.replace(/^#\/?/, "").split("?")[0].split("/")[1] || "";
  return UUID.test(id) ? noteView(id.toLowerCase()) : notesView();
}

const ROUTES = [
  { path: "notes", label: "Notes", view: notesRoute },
  { path: "today", label: "Today", view: todayView },
  { path: "memory", label: "Items", view: memoryView },
  { path: "ask", label: "Ask", view: askView },
  { path: "account", label: "Account", view: accountView },
];

let me = null;
let renderToken = 0;

function currentRoute() {
  const path = (location.hash.replace(/^#\/?/, "").split(/[/?]/)[0] || "notes").toLowerCase();
  return ROUTES.find((r) => r.path === path) || ROUTES[0];
}

function signOutNow() {
  me = null;
  render();
}
whenSignedOut(signOutNow);

async function signOut() {
  try {
    await api("/tokens/current", { method: "DELETE" });
  } catch {
    /* the token may already be gone; the local copy is cleared either way */
  }
  session.clear();
  location.hash = "";
  signOutNow();
}

// Pages that work without a session: the emailed links and the forgot-password form.
function publicPage() {
  const [path, query = ""] = location.hash.replace(/^#\/?/, "").split("?");
  const token = new URLSearchParams(query).get("token") || "";
  if (path === "forgot") return { title: "Reset password", view: async () => forgotView() };
  if (path === "reset" && token) return { title: "New password", view: async () => resetView(token) };
  if (path === "verify" && token) return { title: "Confirm email", view: () => verifyView(token) };
  return null;
}

async function render() {
  const mine = ++renderToken;
  clear(root);
  const page = publicPage();
  if (page) {
    document.title = `${page.title} — AI Daily Memory`;
    root.append(await page.view());
    return;
  }
  if (!session.signedIn) {
    root.append(authView({ onSignedIn: async () => { me = null; location.hash = "#/notes"; await render(); } }));
    document.title = "Sign in — AI Daily Memory";
    return;
  }
  if (!me) {
    root.append(h("main", { id: "main" }, h("p", { role: "status" }, "Loading…")));
    try {
      me = await api("/me");
    } catch (error) {
      if (mine !== renderToken) return;
      // A 401 already cleared the session and re-rendered; anything else is a connection problem.
      if (!session.signedIn) return;
      clear(root);
      root.append(h("main", { id: "main" }, h("h1", null, "Can't load your account"),
        h("p", { class: "lede" }, "Check your connection, then try again."),
        h("button", { class: "primary", onclick: render }, "Try again"),
        h("button", { onclick: signOut }, "Sign out")));
      return;
    }
    if (mine !== renderToken) return;
    clear(root);
  }
  const route = currentRoute();
  document.title = `${route.label} — AI Daily Memory`;
  const main = h("main", { id: "main", tabindex: "-1" });
  root.append(
    h("header", { class: "bar" }, h("div", { class: "bar-in" },
      h("a", { class: "brand", href: "#/notes" }, h("span", { class: "dot", "aria-hidden": "true" }), "AI Daily Memory"),
      h("nav", { class: "tabs", "aria-label": "Main" }, ROUTES.map((r) =>
        h("a", { href: `#/${r.path}`, "aria-current": r === route ? "page" : false }, r.label))),
      h("span", { class: "faint" }, me.email || ""),
      h("button", { class: "small", onclick: signOut }, "Sign out"))),
    main,
    h("footer", { class: "app" }, "Your memory belongs to you. ",
      h("a", { href: "/privacy.html" }, "Privacy"), " · ", h("a", { href: "/terms.html" }, "Terms"), " · ",
      h("a", { href: "https://github.com/apercallc/AI-Daily-Memory" }, "Open source")),
  );
  main.append(await route.view({ me, refreshMe: async () => { me = await api("/me"); return me; }, signOut }));
  if (mine === renderToken) main.focus({ preventScroll: true });
}

window.addEventListener("hashchange", render);
render();
