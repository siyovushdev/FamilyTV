#!/usr/bin/env python3
"""Sign / verify FamilyTV content index envelopes (Ed25519)."""
from __future__ import annotations

import argparse
import base64
import json
import sys
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey

# Allow running as script
sys.path.insert(0, str(Path(__file__).resolve().parent))
from canonical import canonical_bytes, canonical_dumps  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SK = ROOT / ".secrets" / "content-signing" / "dev-publisher.ed25519.sk"
DEFAULT_PK = ROOT / ".secrets" / "content-signing" / "dev-publisher.ed25519.pk"
DEFAULT_KEY_ID = ROOT / ".secrets" / "content-signing" / "key-id.txt"


def load_key_id() -> str:
    if DEFAULT_KEY_ID.is_file():
        return DEFAULT_KEY_ID.read_text(encoding="utf-8").strip() or "familytv-dev-1"
    return "familytv-dev-1"


def sign_payload(payload: dict, sk_path: Path, key_id: str) -> dict:
    sk = Ed25519PrivateKey.from_private_bytes(sk_path.read_bytes())
    message = canonical_bytes(payload)
    sig = sk.sign(message)
    return {
        "schemaVersion": 1,
        "keyId": key_id,
        "signature": base64.b64encode(sig).decode("ascii"),
        "payload": payload,
    }


def verify_envelope(envelope: dict, pk_bytes: bytes) -> tuple[bool, list[str]]:
    errors: list[str] = []
    if int(envelope.get("schemaVersion", 0)) != 1:
        errors.append("Unsupported envelope schemaVersion")
    if "signature" not in envelope or "payload" not in envelope:
        errors.append("Envelope missing signature or payload")
        return False, errors
    if "keyId" not in envelope or not str(envelope["keyId"]).strip():
        errors.append("Envelope missing keyId")
    payload = envelope["payload"]
    if not isinstance(payload, dict):
        errors.append("payload must be object")
        return False, errors
    try:
        sig = base64.b64decode(envelope["signature"], validate=True)
        pk = Ed25519PublicKey.from_public_bytes(pk_bytes)
        pk.verify(sig, canonical_bytes(payload))
    except (InvalidSignature, ValueError, TypeError) as exc:
        errors.append(f"Signature verification failed: {exc}")
        return False, errors
    return len(errors) == 0, errors


def cmd_sign(args: argparse.Namespace) -> int:
    payload = json.loads(Path(args.payload).read_text(encoding="utf-8"))
    key_id = args.key_id or load_key_id()
    envelope = sign_payload(payload, Path(args.sk), key_id)
    out = Path(args.out)
    out.write_text(json.dumps(envelope, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Signed → {out} keyId={key_id}")
    return 0


def cmd_verify(args: argparse.Namespace) -> int:
    envelope = json.loads(Path(args.envelope).read_text(encoding="utf-8"))
    pk_path = Path(args.pk)
    if pk_path.suffix == ".b64" or pk_path.name.endswith(".pub.b64"):
        pk_bytes = base64.b64decode(pk_path.read_text(encoding="utf-8").strip())
    else:
        pk_bytes = pk_path.read_bytes()
    ok, errors = verify_envelope(envelope, pk_bytes)
    if ok:
        print("OK signature")
        print(f"  keyId={envelope.get('keyId')}")
        print(f"  indexVersion={envelope.get('payload', {}).get('indexVersion')}")
        return 0
    print("INVALID")
    for e in errors:
        print(f"  - {e}")
    return 1


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Sign/verify FamilyTV content index")
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("sign", help="Sign a payload JSON into an envelope")
    s.add_argument("payload", help="Path to unsigned payload JSON")
    s.add_argument("--sk", default=str(DEFAULT_SK))
    s.add_argument("--key-id", default=None)
    s.add_argument("--out", required=True)

    v = sub.add_parser("verify", help="Verify a signed envelope")
    v.add_argument("envelope", help="Path to signed index envelope")
    v.add_argument("--pk", default=str(DEFAULT_PK))

    args = p.parse_args(argv)
    if args.cmd == "sign":
        return cmd_sign(args)
    if args.cmd == "verify":
        return cmd_verify(args)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
