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
  { path: "notes", label: "Notes", icon: "notes", view: notesRoute },
  { path: "today", label: "Today", icon: "today", view: todayView },
  { path: "memory", label: "Memory", icon: "memory", view: memoryView },
  { path: "ask", label: "Ask", icon: "ask", view: askView },
  { path: "account", label: "Settings", icon: "settings", view: accountView },
];

const NAV_ICONS = {
  notes: "M4 5.5h16v13H4z M8 9h8 M8 12.5h8 M8 16h5",
  today: "M12 7v5l3.5 2 M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z",
  memory: "M12 3.5 20.5 12 12 20.5 3.5 12 12 3.5Z M12 8v8 M8 12h8",
  ask: "M8 8.5a4 4 0 1 1 6.8 2.8c-1.2 1.1-2.8 1.7-2.8 3.7 M12 18h.01 M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z",
  settings: "M12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7Z M19.4 15a1.8 1.8 0 0 0 .36 1.98l.06.06-1.7 2.94-.08-.02a1.8 1.8 0 0 0-1.9.56l-.05.07h-3.4l-.05-.07a1.8 1.8 0 0 0-1.9-.56l-.08.02-1.7-2.94.06-.06A1.8 1.8 0 0 0 9.4 15l-.08-.04v-3.4l.08-.04a1.8 1.8 0 0 0-.36-1.98l-.06-.06 1.7-2.94.08.02a1.8 1.8 0 0 0 1.9-.56l.05-.07h3.4l.05.07a1.8 1.8 0 0 0 1.9.56l.08-.02 1.7 2.94-.06.06A1.8 1.8 0 0 0 19.4 11.5l.08.04v3.4l-.08.04Z",
};

function navLink(route, active) {
  return h("a", { href: `#/${route.path}`, "aria-current": active ? "page" : false },
    h("svg", { class: "nav-icon", viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", "stroke-width": "1.7", "stroke-linecap": "round", "stroke-linejoin": "round", "aria-hidden": "true" },
      h("path", { d: NAV_ICONS[route.icon] })),
    h("span", null, route.label));
}

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
      h("nav", { class: "tabs", "aria-label": "Main navigation" }, ROUTES.map((r) => navLink(r, r === route))),
      h("span", { class: "faint" }, me.email || ""),
      h("button", { class: "small", onclick: signOut }, "Sign out"))),
    h("nav", { class: "mobile-nav", "aria-label": "Main navigation" }, ROUTES.map((r) => navLink(r, r === route))),
    main,
    h("footer", { class: "app" }, "Your memory belongs to you. ",
      h("a", { href: "/privacy.html" }, "Privacy"), " · ", h("a", { href: "/terms.html" }, "Terms"), " · ",
      h("a", { href: "https://github.com/apercallc/AI-Daily-Memory" }, "Open source")),
  );
  const view = await route.view({ me, refreshMe: async () => { me = await api("/me"); return me; }, signOut });
  if (mine !== renderToken) return;
  main.append(view);
  main.classList.add("page-enter");
  if (mine === renderToken) main.focus({ preventScroll: true });
}

window.addEventListener("hashchange", render);
render();
