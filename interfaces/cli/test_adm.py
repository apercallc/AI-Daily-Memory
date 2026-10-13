"""Run with: python -m unittest interfaces/cli/test_adm.py (no third-party packages)."""

import io
import json
import os
import stat
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

import adm

SEEN: list[dict] = []


class Stub(BaseHTTPRequestHandler):
    def _send(self, status: int, body: object) -> None:
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self) -> None:
        SEEN.append({"path": self.path, "auth": self.headers.get("Authorization")})
        if self.headers.get("Authorization") != "Bearer adm_good":
            return self._send(401, {"error": {"code": "unauthorized"}})
        self._send(200, {"items": [{"memory_day": "2026-10-13", "kind": "decision", "text": "Launch Friday",
                                    "counterpart": None, "due": None}], "total": 1})

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", 0))
        SEEN.append({"path": self.path, "body": json.loads(self.rfile.read(length)),
                     "auth": self.headers.get("Authorization")})
        self._send(200, {"answer": "You decided to launch Friday.", "items": [
            {"day": "2026-10-13", "kind": "decision", "text": "Launch Friday", "counterpart": None, "due": None}]})

    def log_message(self, *args: object) -> None:
        pass


class AdmTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.http = HTTPServer(("127.0.0.1", 0), Stub)
        threading.Thread(target=cls.http.serve_forever, daemon=True).start()
        os.environ["ADM_SERVER"] = f"http://127.0.0.1:{cls.http.server_port}"

    @classmethod
    def tearDownClass(cls) -> None:
        cls.http.shutdown()

    def setUp(self) -> None:
        SEEN.clear()
        os.environ["ADM_API_KEY"] = "adm_good"
        self.dir = tempfile.TemporaryDirectory()
        adm.CONFIG = Path(self.dir.name) / "config.json"

    def tearDown(self) -> None:
        self.dir.cleanup()

    def run_cli(self, *argv: str) -> tuple[int, str]:
        out = io.StringIO()
        return adm.run(list(argv), out), out.getvalue()

    def test_ask_sends_the_question_with_the_key_and_prints_answer_and_items(self) -> None:
        code, text = self.run_cli("ask", "what", "did", "I", "decide?")
        self.assertEqual(code, 0)
        self.assertIn("You decided to launch Friday.", text)
        self.assertIn("Launch Friday", text)
        self.assertEqual(SEEN[0]["body"]["question"], "what did I decide?")
        self.assertEqual(SEEN[0]["auth"], "Bearer adm_good")

    def test_json_mode_is_valid_json(self) -> None:
        code, text = self.run_cli("--json", "day", "2026-10-13")
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(text)[0]["kind"], "decision")
        self.assertIn("day=2026-10-13", SEEN[0]["path"])

    def test_kind_commands_filter_by_kind_and_window(self) -> None:
        self.run_cli("tasks", "--days", "3")
        self.assertIn("kind=action", SEEN[0]["path"])
        self.assertIn("from=", SEEN[0]["path"])

    def test_search_escapes_the_text(self) -> None:
        self.run_cli("search", "a&b c")
        self.assertIn("q=a%26b+c", SEEN[0]["path"])

    def test_a_bad_key_and_bad_input_give_plain_errors(self) -> None:
        os.environ["ADM_API_KEY"] = "adm_bad"
        self.assertEqual(self.run_cli("day")[0], 1)
        os.environ["ADM_API_KEY"] = "adm_good"
        self.assertEqual(self.run_cli("day", "someday")[0], 1)
        del os.environ["ADM_API_KEY"]
        self.assertEqual(self.run_cli("day")[0], 1)

    def test_the_server_must_be_https_unless_local(self) -> None:
        saved = os.environ["ADM_SERVER"]
        os.environ["ADM_SERVER"] = "http://example.com"
        try:
            self.assertEqual(self.run_cli("day")[0], 1)
        finally:
            os.environ["ADM_SERVER"] = saved

    def test_the_saved_key_is_owner_only(self) -> None:
        adm.save_key("adm_good")
        mode = stat.S_IMODE(adm.CONFIG.stat().st_mode)
        self.assertEqual(mode, 0o600)
        del os.environ["ADM_API_KEY"]
        self.assertEqual(self.run_cli("day")[0], 0)


if __name__ == "__main__":
    unittest.main()
