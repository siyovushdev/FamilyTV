#!/usr/bin/env python3
"""LAN content publisher HTTP server (dev only)."""
from __future__ import annotations

import argparse
import json
import socket
import sys
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "publisher"))
import publish as publish_mod  # noqa: E402


class PublisherHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, directory: str | None = None, **kwargs):
        super().__init__(*args, directory=directory, **kwargs)

    def log_message(self, fmt: str, *args) -> None:  # noqa: A003
        sys.stderr.write("[publisher] " + (fmt % args) + "\n")

    def end_headers(self) -> None:
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        super().end_headers()


def guess_lan_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return "127.0.0.1"


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Serve signed FamilyTV content over LAN")
    p.add_argument("--content", type=Path, default=ROOT / "dist")
    p.add_argument("--publish-dir", type=Path, default=ROOT / "publish")
    p.add_argument("--host", default="0.0.0.0")
    p.add_argument("--port", type=int, default=8080)
    p.add_argument("--index-version", type=int, default=None)
    p.add_argument("--skip-publish", action="store_true", help="Serve existing publish/ only")
    args = p.parse_args(argv)

    lan = guess_lan_ip()
    base = f"http://{lan}:{args.port}"
    if not args.skip_publish:
        publish_mod.build_index(
            args.content,
            args.publish_dir,
            args.index_version
            if args.index_version is not None
            else (
                int((args.publish_dir / "index-version.txt").read_text().strip()) + 1
                if (args.publish_dir / "index-version.txt").is_file()
                else 1
            ),
            base_url=base,
        )
        (args.publish_dir / "index-version.txt").write_text(
            str(
                args.index_version
                if args.index_version is not None
                else json.loads((args.publish_dir / "index.json").read_text())["payload"]["indexVersion"]
            )
            + "\n",
            encoding="utf-8",
        )

    index = args.publish_dir / "index.json"
    if not index.is_file():
        raise SystemExit(f"Missing {index}; run without --skip-publish first")

    handler = partial(PublisherHandler, directory=str(args.publish_dir.resolve()))
    httpd = ThreadingHTTPServer((args.host, args.port), handler)
    print("FamilyTV local publisher")
    print(f"  root:  {args.publish_dir.resolve()}")
    print(f"  index: {base}/index.json")
    print(f"  bind:  http://{args.host}:{args.port}/")
    print(f"  LAN:   {base}/")
    print("  Ctrl+C to stop")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nStopped")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
