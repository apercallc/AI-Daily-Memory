// Run with: node --test website/app/test
//
// dialogs.js builds real DOM nodes (it imports `h` from dom.js, which calls
// document.createElement). There's no browser or jsdom in this test setup, so this file
// defines just enough of a fake DOM — elements, attributes, event listeners, focus tracking,
// and a no-op showModal/close pair — for confirmBox() to run under node:test. It does not
// model native <dialog> focus-trapping or Escape-triggered 'cancel' events (those are the
// browser's own behavior, untouched by this fix); it only proves confirmBox's own contract:
// which button is focused first, and what each button resolves to.
import assert from "node:assert/strict";
import test from "node:test";

class FakeNode {}

class FakeText extends FakeNode {
  constructor(text) {
    super();
    this.nodeType = 3;
    this.data = text;
  }
  get textContent() {
    return this.data;
  }
}

class FakeElement extends FakeNode {
  constructor(tag) {
    super();
    this.tagName = tag.toUpperCase();
    this.children = [];
    this.attrs = {};
    this.listeners = {};
    this.className = "";
    this.parentNode = null;
    this.disabled = false;
  }
  setAttribute(key, value) {
    this.attrs[key] = value;
  }
  addEventListener(type, fn) {
    (this.listeners[type] ||= []).push(fn);
  }
  dispatchEvent(type, event) {
    for (const fn of [...(this.listeners[type] || [])]) fn(event);
  }
  append(...nodes) {
    for (const n of nodes) {
      n.parentNode = this;
      this.children.push(n);
    }
  }
  remove() {
    if (this.parentNode) this.parentNode.children = this.parentNode.children.filter((c) => c !== this);
    this.parentNode = null;
  }
  focus() {
    globalThis.document.activeElement = this;
  }
  click() {
    this.dispatchEvent("click", { currentTarget: this });
  }
  get textContent() {
    return this.children.map((c) => c.textContent ?? "").join("");
  }
  // Minimal <dialog> behavior: open/close just toggle state and fire the 'close' listeners
  // that dialogs.js relies on (native focus-trapping and Escape-as-cancel are the browser's
  // own job and aren't modeled here).
  showModal() {
    this.open = true;
  }
  close() {
    this.open = false;
    this.dispatchEvent("close");
  }
}

globalThis.Node = FakeNode;
globalThis.document = {
  activeElement: null,
  createElement: (tag) => new FakeElement(tag),
  createTextNode: (text) => new FakeText(text),
  body: new FakeElement("body"),
};

function findByText(root, tag, text) {
  for (const child of root.children || []) {
    if (child.tagName === tag.toUpperCase() && child.textContent === text) return child;
    const found = findByText(child, tag, text);
    if (found) return found;
  }
  return null;
}

const { confirmBox } = await import("../dialogs.js");

test("confirmBox focuses Cancel first, never the danger/confirm button", () => {
  document.activeElement = null;
  const before = document.body.children.length;
  const promise = confirmBox({ title: "Delete it?", body: "Sure?", action: "Delete", danger: true });
  const dialog = document.body.children[before];
  const cancelBtn = findByText(dialog, "button", "Cancel");
  const deleteBtn = findByText(dialog, "button", "Delete");

  assert.equal(document.activeElement, cancelBtn, "initial focus must land on Cancel");
  assert.notEqual(document.activeElement, deleteBtn);

  // Visual/tab order is unchanged: Cancel still comes before the danger button in the DOM.
  const row = dialog.children.at(-1);
  assert.deepEqual([row.children[0], row.children[1]], [cancelBtn, deleteBtn]);

  // Tab can still reach the confirm button and activate it.
  deleteBtn.click();
  return promise.then((answer) => assert.equal(answer, true));
});

test("confirmBox resolves false when Cancel is activated (and on a native close, e.g. Escape)", async () => {
  const before = document.body.children.length;
  const promise = confirmBox({ title: "Sign out device?", body: "Sure?", action: "Sign out" });
  const dialog = document.body.children[before];
  const cancelBtn = findByText(dialog, "button", "Cancel");
  cancelBtn.click();
  assert.equal(await promise, false);

  // A native Escape calls the dialog's own close(), bypassing both buttons — confirmBox must
  // still resolve false rather than hang or default to "confirmed".
  const before2 = document.body.children.length;
  const promise2 = confirmBox({ title: "Revoke key?", body: "Sure?", action: "Revoke", danger: true });
  const dialog2 = document.body.children[before2];
  dialog2.close();
  assert.equal(await promise2, false);
});
