import json
from pathlib import Path

from app.openapi_artifact import generate_openapi, main


def test_generated_openapi_documents_the_versioned_health_route() -> None:
    schema = generate_openapi()

    assert schema["info"]["version"] == "0.1.1"
    assert "/v1/health" in schema["paths"]
    assert all(path.startswith("/v1/") for path in schema["paths"])


def test_cli_writes_and_checks_a_deterministic_snapshot(tmp_path: Path) -> None:
    output = tmp_path / "openapi.json"

    assert main(["--output", str(output)]) == 0
    assert json.loads(output.read_text(encoding="utf-8"))["info"]["version"] == "0.1.1"
    assert main(["--check", "--output", str(output)]) == 0


def test_check_fails_for_a_missing_or_stale_snapshot(tmp_path: Path) -> None:
    output = tmp_path / "missing.json"

    assert main(["--check", "--output", str(output)]) == 1
    output.write_text("{}\n", encoding="utf-8")
    assert main(["--check", "--output", str(output)]) == 1
