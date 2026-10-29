import importlib.util
from pathlib import Path
import subprocess
import sys
import unittest

SPEC = importlib.util.spec_from_file_location("smoke_sync", Path(__file__).with_name("smoke_sync.py"))
SMOKE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SMOKE)


class SmokeSafetyTest(unittest.TestCase):
    def test_only_exact_disposable_origin_is_allowed(self):
        SMOKE.validate_target("http://127.0.0.1:8788", Path("dev.json"))
        for target in ["https://stint.example/stint", "http://127.0.0.1:8788@evil.example",
                       "http://127.0.0.1:87880", "http://127.0.0.1:8788/v1", "http://127.0.0.1:8788?x=1",
                       "http://127.0.0.1:8788#x", "http://localhost:8788", "http://127.0.0.1:8788:9"]:
            with self.subTest(target=target), self.assertRaises(ValueError):
                SMOKE.validate_target(target, Path("dev.json"))

    def test_production_credentials_are_rejected_before_read(self):
        with self.assertRaises(ValueError):
            SMOKE.validate_target("http://127.0.0.1:8788", Path(".local/production.json"))

    def test_optimized_python_cannot_disable_the_guard(self):
        result = subprocess.run(
            [sys.executable, "-O", str(Path(__file__).with_name("smoke_sync.py")),
             "--base-url", "https://stint.example/stint", "--config", "/does/not/exist.json"],
            capture_output=True, text=True,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("dedicated disposable smoke server", result.stderr)
        self.assertNotIn("FileNotFoundError", result.stderr)


if __name__ == "__main__":
    unittest.main()
