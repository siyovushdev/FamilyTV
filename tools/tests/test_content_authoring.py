#!/usr/bin/env python3
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "content"))
from schema import sha256_file, validate_manifest  # noqa: E402
from cli import cmd_build, cmd_diff, cmd_inspect, cmd_validate  # noqa: E402


class AuthoringToolTest(unittest.TestCase):
    def test_build_generates_sha_automatically(self):
        source = ROOT / "lessons" / "move-break"
        out = Path(tempfile.mkdtemp())
        self.assertEqual(0, cmd_build(source, out))
        zip_path = out / "move-break-v1.zip"
        self.assertTrue(zip_path.is_file())
        self.assertEqual(0, cmd_inspect(zip_path))

    def test_validate_rejects_bad_definition(self):
        bad = Path(tempfile.mkdtemp())
        (bad / "lesson.json").write_text(
            json.dumps({"packageId": "x", "packageVersion": 1, "title": "t", "content": []}),
            encoding="utf-8",
        )
        self.assertEqual(1, cmd_validate(bad))

    def test_diff_detects_version_change(self):
        a = ROOT / "dist" / "apple-adventure-v1.zip"
        b = ROOT / "dist" / "apple-adventure-v2.zip"
        self.assertTrue(a.exists() and b.exists())
        self.assertEqual(0, cmd_diff(a, b))

    def test_shared_schema_rejects_traversal(self):
        ok, errors, _ = validate_manifest(
            {
                "schemaVersion": 1,
                "packageId": "demo-pkg",
                "packageVersion": 1,
                "title": "t",
                "content": [{"id": "c", "title": "c", "type": "VIDEO", "mediaRef": "../x.mp4"}],
                "files": [{"path": "../x.mp4", "sha256": "a" * 64, "size": 1, "mediaType": "video/mp4"}],
            }
        )
        self.assertFalse(ok)
        self.assertTrue(any("Unsafe" in e for e in errors))

    def test_quality_warns_missing_minutes(self):
        from quality import quality_check_source

        bad = Path(tempfile.mkdtemp())
        (bad / "lesson.json").write_text(
            json.dumps(
                {
                    "packageId": "demo-pkg",
                    "packageVersion": 1,
                    "schemaVersion": 1,
                    "title": "t",
                    "content": [
                        {
                            "id": "c1",
                            "title": "Interactive",
                            "type": "INTERACTIVE",
                            "experienceRef": "experiences/x.json",
                        }
                    ],
                }
            ),
            encoding="utf-8",
        )
        (bad / "experiences").mkdir()
        (bad / "experiences" / "x.json").write_text(
            json.dumps(
                {
                    "id": "x",
                    "title": "x",
                    "schemaVersion": 3,
                    "steps": [
                        {"type": "message", "id": "a", "text": "hi", "transitions": {"next": "__end__"}}
                    ],
                }
            ),
            encoding="utf-8",
        )
        errors, warnings = quality_check_source(bad)
        self.assertEqual([], errors)
        self.assertTrue(any("estimatedMinutes" in w for w in warnings))

    def test_sha_helper_stable(self):
        path = ROOT / "lessons" / "move-break" / "media" / "warmup.mp4"
        self.assertEqual(64, len(sha256_file(path)))


if __name__ == "__main__":
    unittest.main()

