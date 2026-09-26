#!/usr/bin/env python3
"""Canonical JSON for FamilyTV signed payloads (must match Kotlin CanonicalJson)."""
from __future__ import annotations

import json
from typing import Any


def canonical_dumps(obj: Any) -> str:
    """UTF-8 JSON with sorted keys and compact separators; ensure_ascii=False."""
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def canonical_bytes(obj: Any) -> bytes:
    return canonical_dumps(obj).encode("utf-8")
