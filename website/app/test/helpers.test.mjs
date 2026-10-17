// Run with: node --test website/app/test
import assert from "node:assert/strict";
import test from "node:test";
import { addDays, localDay, prettyDay } from "../dom.js";
import { ApiError, describe } from "../api.js";

test("days step across month and year ends", () => {
  assert.equal(addDays("2026-03-01", -1), "2026-02-28");
  assert.equal(addDays("2026-12-31", 1), "2027-01-01");
  assert.equal(addDays("2028-02-28", 1), "2028-02-29");
});

test("localDay is a plain YYYY-MM-DD in the local calendar", () => {
  assert.equal(localDay(new Date(2026, 9, 5, 23, 59)), "2026-10-05");
  assert.match(localDay(), /^\d{4}-\d{2}-\d{2}$/);
});

test("today and yesterday read as words", () => {
  assert.equal(prettyDay(localDay()), "Today");
  assert.equal(prettyDay(addDays(localDay(), -1)), "Yesterday");
});

test("errors read as plain sentences, never raw codes", () => {
  assert.match(describe(new ApiError(401, "invalid_credentials", "")), /don't match/);
  assert.match(describe(new ApiError(429, "quota_exceeded", "")), /limit for questions/);
  assert.match(describe(new ApiError(429, "something_else", "")), /Too many/);
  assert.match(describe(new ApiError(503, "x", "")), /problem/);
  assert.match(describe(new ApiError(0, "unreachable", "")), /Couldn't reach/);
  assert.equal(describe(new Error("boom")), "Something went wrong.");
});

test("a known Retry-After tells a person the real wait, not just 'try later'", () => {
  assert.doesNotMatch(describe(new ApiError(429, "too_many_requests", "")), /Try again in/);
  assert.match(describe(new ApiError(429, "too_many_requests", "", 45)), /Try again in 45 seconds\.$/);
  assert.match(describe(new ApiError(429, "too_many_requests", "", 125)), /Try again in 2 minutes\.$/);
  assert.match(describe(new ApiError(503, "database_unavailable", "", 15)), /Try again in 15 seconds\.$/);
  assert.match(describe(new ApiError(429, "quota_exceeded", "", 7260)), /Try again in 2 hours\.$/);
});
