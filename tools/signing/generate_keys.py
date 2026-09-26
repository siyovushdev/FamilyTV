#!/usr/bin/env python3
"""Generate Ed25519 publisher keypair into an ignored secrets directory."""
from __future__ import annotations

import argparse
import base64
import sys
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization

ROOT = Path(__file__).resolve().parents[2]


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Generate FamilyTV content signing keys")
    p.add_argument("--out", type=Path, default=ROOT / ".secrets" / "content-signing")
    p.add_argument("--key-id", default="familytv-dev-1")
    p.add_argument(
        "--install-public",
        action="store_true",
        help="Write public key into app assets (safe to commit)",
    )
    args = p.parse_args(argv)
    args.out.mkdir(parents=True, exist_ok=True)
    sk = Ed25519PrivateKey.generate()
    pk = sk.public_key()
    sk_raw = sk.private_bytes(
        serialization.Encoding.Raw,
        serialization.PrivateFormat.Raw,
        serialization.NoEncryption(),
    )
    pk_raw = pk.public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    (args.out / "dev-publisher.ed25519.sk").write_bytes(sk_raw)
    (args.out / "dev-publisher.ed25519.pk").write_bytes(pk_raw)
    b64 = base64.b64encode(pk_raw).decode("ascii")
    (args.out / "dev-publisher.pub.b64").write_text(b64 + "\n", encoding="utf-8")
    (args.out / "key-id.txt").write_text(args.key_id + "\n", encoding="utf-8")
    print(f"Wrote private key → {args.out / 'dev-publisher.ed25519.sk'}")
    print(f"Wrote public key  → {args.out / 'dev-publisher.pub.b64'}")
    print(f"keyId={args.key_id}")
    if args.install_public:
        dest = ROOT / "app/src/main/assets/content/trust/dev-publisher.pub.b64"
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(b64 + "\n", encoding="utf-8")
        print(f"Installed public key → {dest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
