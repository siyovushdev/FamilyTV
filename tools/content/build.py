#!/usr/bin/env python3
"""Shortcut: python3 tools/content/build.py lessons/apples"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from cli import main

if __name__ == "__main__":
    argv = sys.argv[1:]
    if argv and argv[0] not in {"validate", "build", "inspect", "diff"}:
        argv = ["build", *argv]
    raise SystemExit(main(argv))
