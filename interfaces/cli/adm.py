#!/usr/bin/env python3
"""adm: ask your AI Daily Memory from a terminal. Uses only the public REST API (ADR-0021) with a
read-only API key from Account → API keys; standard library only.

    adm login                      save your key (asks for it, never echoes it)
    adm ask "What did I promise Sam?"
    adm day [today|yesterday|YYYY-MM-DD]
    adm search pricing deck
    adm decisions | tasks | commitments | ideas | questions   [--days N]
    add --json to any command for machine-readable output

Server: ADM_SERVER (default https://aidailymem.apercallc.com). Key: ADM_API_KEY, or the saved one.
"""

import argparse
import getpass
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, timedelta
from pathlib import Path

DEFAULT_SERVER = "https://aidailymem.apercallc.com"
CONFIG = Path(os.environ.get("ADM_CONFIG", Path.home() / ".config" / "adm" / "config.json"))
KINDS = {"decisions": "decision", "tasks": "action", "commitments": "commitment",
         "ideas": "idea", "questions": "question"}


class CliError(Exception):
    pass


def load_config() -> dict[str, str]:
    try:
        return json.loads(CONFIG.read_text())
    except (OSError, ValueError):
        return {}


def save_key(key: str) -> None:
    CONFIG.parent.mkdir(parents=True, exist_ok=True)
    # Created owner-only before the secret is written, so it is never readable by others.
    fd = os.open(CONFIG, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as handle:
        json.dump({"api_key": key}, handle)


def server() -> str:
    base = os.environ.get("ADM_SERVER", DEFAULT_SERVER).rstrip("/")
    if not base.startswith("https://") and not base.startswith("http://127.0.0.1") and not base.startswith("http://localhost"):
        raise CliError("ADM_SERVER must be an https address.")
    return base


def api(path: str, *, method: str = "GET", body: dict | None = None) -> dict | list:
    key = os.environ.get("ADM_API_KEY") or load_config().get("api_key")
    if not key:
        raise CliError("No API key. Run `adm login`, or set ADM_API_KEY.")
    request = urllib.request.Request(
        server() + "/v1" + path, method=method,
        data=None if body is None else json.dumps(body).encode(),
        headers={"Authorization": f"Bearer {key}", "Accept": "application/json",
                 **({"Content-Type": "application/json"} if body is not None else {})},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:  # noqa: S310 - https enforced above
            return json.load(response)
    except urllib.error.HTTPError as error:
        code = ""
        try:
            code = json.load(error)["error"]["code"]
        except (ValueError, KeyError, TypeError):
            pass
        hints = {401: "That key isn't valid (revoked or mistyped).", 429: "Rate or daily limit reached; try later.",
                 403: "That key can't do this (keys are read-only)."}
        raise CliError(hints.get(error.code, f"The server said {error.code} {code}".strip())) from None
    except (urllib.error.URLError, TimeoutError) as error:
        raise CliError(f"Couldn't reach {server()}.") from error


def resolve_day(text: str) -> str:
    today = date.today()
    words = {"today": today, "yesterday": today - timedelta(days=1)}
    if text in words:
        return words[text].isoformat()
    try:
        return date.fromisoformat(text).isoformat()
    except ValueError:
        raise CliError("Use today, yesterday, or a date like 2026-10-13.") from None


def line(item: dict) -> str:
    extra = []
    if item.get("counterpart"):
        extra.append(item["counterpart"])
    if item.get("due"):
        extra.append(f"due {item['due']}")
    suffix = f"  ({', '.join(extra)})" if extra else ""
    return f"{item.get('memory_day') or item.get('day')}  {item['kind']:<10} {item['text']}{suffix}"


def items(params: dict[str, str]) -> list[dict]:
    page = api("/memory/items?" + urllib.parse.urlencode({**params, "limit": "100"}))
    assert isinstance(page, dict)
    return page["items"]


def run(argv: list[str], out=sys.stdout) -> int:
    parser = argparse.ArgumentParser(prog="adm", description="Ask your AI Daily Memory.")
    parser.add_argument("--json", action="store_true", help="machine-readable output")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("login")
    ask = sub.add_parser("ask")
    ask.add_argument("question", nargs="+")
    day = sub.add_parser("day")
    day.add_argument("when", nargs="?", default="today")
    search = sub.add_parser("search")
    search.add_argument("text", nargs="+")
    for name in KINDS:
        kind = sub.add_parser(name)
        kind.add_argument("--days", type=int, default=7)
    args = parser.parse_args(argv)
    try:
        if args.command == "login":
            save_key(getpass.getpass("API key: ").strip())
            print(f"Saved to {CONFIG}", file=out)
            return 0
        if args.command == "ask":
            result = api("/ask", method="POST", body={"question": " ".join(args.question), "today": date.today().isoformat()})
            assert isinstance(result, dict)
            if args.json:
                json.dump(result, out, indent=2)
                print(file=out)
            else:
                print(result["answer"], file=out)
                for item in result["items"]:
                    print("  - " + line({**item, "memory_day": item["day"]}), file=out)
            return 0
        if args.command == "day":
            found = items({"day": resolve_day(args.when)})
        elif args.command == "search":
            found = items({"q": " ".join(args.text)})
        else:
            since = (date.today() - timedelta(days=max(args.days, 1) - 1)).isoformat()
            found = items({"kind": KINDS[args.command], "from": since})
        if args.json:
            json.dump(found, out, indent=2)
            print(file=out)
        else:
            print("\n".join(line(i) for i in found) or "Nothing found.", file=out)
        return 0
    except CliError as error:
        print(f"adm: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(run(sys.argv[1:]))
