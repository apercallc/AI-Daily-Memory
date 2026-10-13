// The only place that talks to the server. The session token lives in sessionStorage (gone when
// the tab closes) unless the person chose to stay signed in, then in localStorage.
const KEY = "adm.session";

function read() {
  try {
    return sessionStorage.getItem(KEY) || localStorage.getItem(KEY);
  } catch {
    return null; // storage can be blocked; the page then works for this tab only
  }
}
let memoryToken = null;

export const session = {
  get token() {
    return memoryToken || read();
  },
  get signedIn() {
    return Boolean(this.token);
  },
  set(token, stay) {
    memoryToken = token;
    try {
      sessionStorage.removeItem(KEY);
      localStorage.removeItem(KEY);
      (stay ? localStorage : sessionStorage).setItem(KEY, token);
    } catch {
      /* kept in memory only */
    }
  },
  clear() {
    memoryToken = null;
    try {
      sessionStorage.removeItem(KEY);
      localStorage.removeItem(KEY);
    } catch {
      /* nothing stored */
    }
  },
};

export class ApiError extends Error {
  constructor(status, code, message, retryAfter) {
    super(message);
    this.status = status;
    this.code = code;
    this.retryAfter = retryAfter;
  }
}

// What a person should read for each server code. Anything unknown gets the generic line.
const MESSAGES = {
  invalid_email: "That doesn't look like an email address.",
  weak_password: "Use at least 12 characters.",
  email_taken: "That email already has an account. Sign in instead.",
  invalid_credentials: "That email and password don't match.",
  password_required: "That password isn't right.",
  too_many_requests: "Too many attempts. Wait a few minutes and try again.",
  quota_exceeded: "You've reached today's limit for questions. It resets at midnight UTC.",
  too_many_keys: "You have 10 keys. Revoke one you don't use before making another.",
  token_not_found: "That sign-in was already removed.",
  item_not_found: "That item no longer exists.",
  invalid_link: "That link has expired or was already used. Ask for a new one.",
  email_unavailable: "Email isn't set up on this server, so this isn't available.",
  already_subscribed: "You already have a subscription.",
  billing_unavailable: "Subscriptions aren't available right now.",
  billing_provider_error: "Couldn't reach the payment provider. Try again in a moment.",
  no_subscription: "There's no subscription to manage.",
  database_unavailable: "The service is busy right now. Try again in a moment.",
  storage_unavailable: "Storage is busy right now. Try again in a moment.",
};

export function describe(error) {
  if (error instanceof ApiError) {
    if (MESSAGES[error.code]) return MESSAGES[error.code];
    if (error.status === 429) return MESSAGES.too_many_requests;
    if (error.status >= 500) return "The service had a problem. Try again in a moment.";
    if (error.status === 0) return "Couldn't reach the server. Check your connection and try again.";
    return error.message || "Something went wrong.";
  }
  return "Something went wrong.";
}

let onSignedOut = () => {};
export function whenSignedOut(callback) {
  onSignedOut = callback;
}

export async function api(path, { method = "GET", body, auth = true, raw = false } = {}) {
  const headers = { Accept: "application/json" };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (auth && session.token) headers.Authorization = `Bearer ${session.token}`;
  let response;
  try {
    response = await fetch(`/v1${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), cache: "no-store" });
  } catch {
    throw new ApiError(0, "unreachable", "Couldn't reach the server.");
  }
  if (response.status === 204) return null;
  if (response.ok) return raw ? response : response.json();
  let code = `http_${response.status}`;
  let message = "";
  try {
    const envelope = (await response.json()).error;
    code = envelope.code || code;
    message = envelope.message || "";
  } catch {
    /* an HTML error page from a proxy: the status alone says enough */
  }
  // A stale or revoked session on an authenticated call: back to the sign-in screen.
  if (response.status === 401 && auth && code === "unauthorized") {
    session.clear();
    onSignedOut();
  }
  throw new ApiError(response.status, code, message, Number(response.headers.get("Retry-After")) || undefined);
}
