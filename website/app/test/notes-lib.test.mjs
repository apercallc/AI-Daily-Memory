// Run with: node --test website/app/test
import assert from "node:assert/strict";
import test from "node:test";
import { addDays } from "../dom.js";
import {
  count, fileName, highlight, listParams, noteFacts, parseState, searchTerms, stateToHash, transcriptText, viewTitle,
} from "../views/notes-lib.js";

const FOLDER = "0f6a2a7e-1c3b-4c5d-8e9f-a1b2c3d4e5f6";

test("an empty or unknown address gives the default library", () => {
  assert.deepEqual(parseState("#/notes"), { view: "all", q: "", sort: "newest", range: "all" });
  assert.deepEqual(parseState("#/notes?view=nonsense&sort=random&range=5"), { view: "all", q: "", sort: "newest", range: "all" });
});

test("state survives a round trip through the address, leaving out defaults", () => {
  const state = { view: FOLDER, q: "invoice marta", sort: "title", range: "30" };
  assert.deepEqual(parseState(stateToHash(state)), state);
  assert.equal(stateToHash({ view: "all", q: "", sort: "newest", range: "all" }), "#/notes");
  assert.equal(stateToHash({ view: "starred", q: "", sort: "newest", range: "all" }), "#/notes?view=starred");
});

test("search text in the address is capped", () => {
  assert.equal(parseState(`#/notes?q=${"x".repeat(500)}`).q.length, 200);
});

test("each library view asks the API for the right slice", () => {
  const base = { q: "", sort: "newest", range: "all" };
  const ask = (view, extra = {}) => listParams({ ...base, view, ...extra }, { limit: 30, offset: 0, today: "2026-10-13" });
  assert.equal(ask("all").get("folder"), null);
  assert.equal(ask("starred").get("starred"), "true");
  assert.equal(ask("archive").get("archived"), "true");
  assert.equal(ask("unfiled").get("folder"), "none");
  assert.equal(ask(FOLDER).get("folder"), FOLDER);
  assert.equal(ask("all", { q: "  oat milk " }).get("q"), "oat milk");
  assert.equal(ask("all", { q: "   " }).get("q"), null);
  assert.equal(ask("all", { range: "7" }).get("from"), addDays("2026-10-13", -7));
  assert.equal(ask("all", { sort: "title" }).get("sort"), "title");
});

test("search words split on whitespace and stop at six", () => {
  assert.deepEqual(searchTerms("  one  two\tthree "), ["one", "two", "three"]);
  assert.equal(searchTerms("a b c d e f g h").length, 6);
});

test("highlighting marks every match in any case and keeps the rest", () => {
  const pieces = highlight("Send the Contract to Priya, then the contract again", ["contract"]);
  assert.deepEqual(pieces.filter((p) => p.hit).map((p) => p.text), ["Contract", "contract"]);
  assert.equal(pieces.map((p) => p.text).join(""), "Send the Contract to Priya, then the contract again");
});

test("overlapping and repeated search words merge into one mark", () => {
  const pieces = highlight("invoices", ["invoice", "voice", "invoice"]);
  assert.deepEqual(pieces, [{ text: "invoice", hit: true }, { text: "s", hit: false }]);
});

test("no search words, or none found, leaves the text whole", () => {
  assert.deepEqual(highlight("plain", []), [{ text: "plain", hit: false }]);
  assert.deepEqual(highlight("plain", ["zebra"]), [{ text: "plain", hit: false }]);
});

test("markup-looking text is just text to highlight", () => {
  const pieces = highlight("<img src=x onerror=alert(1)> hello", ["hello"]);
  assert.equal(pieces[0].text, "<img src=x onerror=alert(1)> ");
  assert.equal(pieces[0].hit, false);
});

test("counts and facts read naturally", () => {
  assert.equal(count(1, "item"), "1 item");
  assert.equal(count(2, "item"), "2 items");
  assert.equal(noteFacts({ item_count: 0, word_count: 0 }), "");
  assert.equal(noteFacts({ item_count: 1, word_count: 340 }), "1 item · 340 words");
});

test("view titles name built-in views and folders, with a fallback", () => {
  const folders = [{ id: FOLDER, name: "Work" }];
  assert.equal(viewTitle("all", folders), "All notes");
  assert.equal(viewTitle("archive", folders), "Archive");
  assert.equal(viewTitle(FOLDER, folders), "Work");
  assert.equal(viewTitle("deadbeef-0000-0000-0000-000000000000", folders), "Folder");
});

test("a copied transcript has the title, the day and one line per spoken line", () => {
  const text = transcriptText({ title: "Sprint planning", memory_day: "2026-10-10" }, [
    { start_epoch_ms: Date.UTC(2026, 9, 10, 12, 0), speaker: "A", text: "Hello" },
    { start_epoch_ms: Date.UTC(2026, 9, 10, 12, 1), speaker: null, text: "World" },
  ]);
  const lines = text.split("\n");
  assert.equal(lines[0], "Sprint planning");
  assert.equal(lines[1], "2026-10-10");
  assert.match(lines[3], /A: Hello$/);
  assert.match(lines[4], /: World$/);
});

test("download names are safe and carry the day", () => {
  assert.equal(fileName({ title: "Q3 plan: ship it!", memory_day: "2026-10-10" }), "2026-10-10-q3-plan-ship-it.txt");
  assert.equal(fileName({ title: "???", memory_day: "2026-10-10" }), "2026-10-10-note.txt");
  assert.ok(fileName({ title: "x".repeat(300), memory_day: "2026-10-10" }).length < 80);
});
