// A tiny DOM builder. Text always goes in as a text node, never as HTML, so nothing a person or a
// transcript contains can become markup.
export function h(tag, props, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(props || {})) {
    if (value === false || value == null) continue;
    if (key === "class") el.className = value;
    else if (key.startsWith("on") && typeof value === "function") el.addEventListener(key.slice(2), value);
    else if (key === "dataset") Object.assign(el.dataset, value);
    else if (value === true) el.setAttribute(key, "");
    else el.setAttribute(key, String(value));
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const child of children.flat(Infinity)) {
    if (child == null || child === false) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

export function clear(el) {
  while (el.firstChild) el.removeChild(el.firstChild);
  return el;
}

export function notice(kind, message) {
  return h("div", { class: `notice ${kind}`, role: kind === "error" ? "alert" : "status" }, message);
}

export function spinner(label) {
  return h("span", { role: "status" }, h("span", { class: "spinner", "aria-hidden": "true" }), " ", label);
}

// Local calendar day as YYYY-MM-DD (the API's memory days are plain dates).
export function localDay(date = new Date()) {
  const p = (n) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())}`;
}

export function addDays(day, delta) {
  const [y, m, d] = day.split("-").map(Number);
  return localDay(new Date(y, m - 1, d + delta));
}

export function prettyDay(day) {
  const [y, m, d] = day.split("-").map(Number);
  const date = new Date(y, m - 1, d);
  const today = localDay();
  if (day === today) return "Today";
  if (day === addDays(today, -1)) return "Yesterday";
  return date.toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric", year: y === new Date().getFullYear() ? undefined : "numeric" });
}

export function prettyTime(iso) {
  if (!iso) return "never";
  return new Date(iso).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

// "Tue, Oct 13" for a plain day, without "Today"/"Yesterday" words (used on cards that already say when).
export function shortDay(day) {
  const [y, m, d] = day.split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined, { weekday: "short", month: "short", day: "numeric", year: y === new Date().getFullYear() ? undefined : "numeric" });
}
