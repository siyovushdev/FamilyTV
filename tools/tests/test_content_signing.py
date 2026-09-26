#!/usr/bin/env python3
"""Cross-check Python/Kotlin canonical JSON + signing roundtrip."""
from __future__ import annotations

import base64
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "signing"))
from canonical import canonical_dumps  # noqa: E402
from sign import sign_payload, verify_envelope  # noqa: E402

ROOT_SK = ROOT / ".secrets" / "content-signing" / "dev-publisher.ed25519.sk"
ROOT_PK = ROOT / ".secrets" / "content-signing" / "dev-publisher.ed25519.pk"


def test_canonical():
    obj = {"b": 2, "a": "привет", "c": [1, 2]}
    assert canonical_dumps(obj) == '{"a":"привет","b":2,"c":[1,2]}'


def test_sign_verify():
    payload = {
        "schemaVersion": 1,
        "indexVersion": 7,
        "generatedAt": "2026-09-19T00:00:00Z",
        "publisherId": "familytv-dev",
        "packages": [
            {
                "packageId": "move-break",
                "packageVersion": 1,
                "title": "Подвигаемся",
                "description": "",
                "url": "packages/move-break-v1.zip",
                "path": "packages/move-break-v1.zip",
                "size": 1,
                "sha256": "a" * 64,
                "enabled": True,
                "sortOrder": 8,
            }
        ],
    }
    env = sign_payload(payload, ROOT_SK, "familytv-dev-1")
    ok, errors = verify_envelope(env, ROOT_PK.read_bytes())
    assert ok, errors
    # Tamper
    env2 = json.loads(json.dumps(env))
    env2["payload"]["indexVersion"] = 99
    ok2, _ = verify_envelope(env2, ROOT_PK.read_bytes())
    assert not ok2


if __name__ == "__main__":
    test_canonical()
    test_sign_verify()
    print("OK signing tests")
