"""Generate and verify the checked-in OpenAPI v1 contract."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from app.config import Settings
from app.main import create_app

DEFAULT_OUTPUT = Path(__file__).resolve().parents[2] / "docs" / "api" / "openapi.v1.json"


def generate_openapi() -> dict[str, Any]:
    """Build the contract without loading local .env credentials or runtime services."""
    application = create_app(Settings.model_construct(app_env="development"))
    return application.openapi()


def render_openapi(schema: dict[str, Any]) -> str:
    return json.dumps(schema, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true", help="fail if the snapshot is stale")
    args = parser.parse_args(argv)
    expected = render_openapi(generate_openapi())

    if args.check:
        try:
            actual = args.output.read_text(encoding="utf-8")
        except OSError as error:
            print(f"OpenAPI snapshot is missing or unreadable: {error}", file=sys.stderr)
            return 1
        if actual != expected:
            print(
                f"{args.output} is stale; run `uv run python -m app.openapi_artifact` "
                "and review the API contract change.",
                file=sys.stderr,
            )
            return 1
        print(f"OpenAPI snapshot matches {args.output}.")
        return 0

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(expected, encoding="utf-8")
    print(f"Wrote OpenAPI v1 contract to {args.output}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
