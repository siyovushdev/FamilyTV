#!/usr/bin/env python3
"""Build a signed content index from dist/*.zip packages."""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "signing"))
sys.path.insert(0, str(ROOT / "tools" / "content"))
from sign import load_key_id, sign_payload  # noqa: E402
from schema import validate_manifest  # noqa: E402


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def read_package_meta(zip_path: Path) -> dict:
    with zipfile.ZipFile(zip_path) as zf:
        names = zf.namelist()
        pkg_name = next((n for n in names if n.endswith("package.json") and n.count("/") <= 1), None)
        if pkg_name is None:
            raise SystemExit(f"No package.json in {zip_path}")
        manifest = json.loads(zf.read(pkg_name).decode("utf-8"))
    ok, errors, _ = validate_manifest(manifest)
    if not ok:
        raise SystemExit(f"Invalid package {zip_path.name}: {errors}")
    return manifest


def build_index(content_dir: Path, out_dir: Path, index_version: int, base_url: str) -> Path:
    zips = sorted(content_dir.glob("*.zip"))
    if not zips:
        raise SystemExit(f"No ZIP packages in {content_dir}")

    out_dir.mkdir(parents=True, exist_ok=True)
    pkg_out = out_dir / "packages"
    pkg_out.mkdir(exist_ok=True)

    packages = []
    for zip_path in zips:
        manifest = read_package_meta(zip_path)
        package_id = manifest["packageId"]
        version = int(manifest["packageVersion"])
        dest_name = f"{package_id}-v{version}.zip"
        dest = pkg_out / dest_name
        shutil.copy2(zip_path, dest)
        digest = sha256_file(dest)
        size = dest.stat().st_size
        # Relative URL under publisher root
        rel = f"packages/{dest_name}"
        url = f"{base_url.rstrip('/')}/{rel}" if base_url else rel
        packages.append(
            {
                "packageId": package_id,
                "packageVersion": version,
                "title": manifest.get("title") or package_id,
                "description": manifest.get("description") or "",
                "url": url,
                "path": rel,
                "size": size,
                "sha256": digest,
                "enabled": True,
                "sortOrder": int(manifest.get("content", [{}])[0].get("sortOrder", 100))
                if manifest.get("content")
                else 100,
            }
        )

    packages.sort(key=lambda p: (p["packageId"], p["packageVersion"]))
    payload = {
        "schemaVersion": 1,
        "indexVersion": index_version,
        "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "publisherId": "familytv-dev",
        "packages": packages,
    }
    sk = ROOT / ".secrets" / "content-signing" / "dev-publisher.ed25519.sk"
    if not sk.is_file():
        raise SystemExit(f"Missing signing key {sk}. Run tools/signing/generate_keys.py")
    envelope = sign_payload(payload, sk, load_key_id())
    index_path = out_dir / "index.json"
    index_path.write_text(json.dumps(envelope, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    # Also write unsigned payload for debugging
    (out_dir / "index.payload.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"Published indexVersion={index_version} packages={len(packages)} → {index_path}")
    for p in packages:
        print(f"  - {p['packageId']}@{p['packageVersion']} {p['size']}B sha={p['sha256'][:12]}…")
    return index_path


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Publish signed FamilyTV content index")
    p.add_argument("--content", type=Path, default=ROOT / "dist")
    p.add_argument("--out", type=Path, default=ROOT / "publish")
    p.add_argument("--index-version", type=int, default=None)
    p.add_argument("--base-url", default="", help="Absolute URL prefix for package urls (optional)")
    args = p.parse_args(argv)

    version_file = args.out / "index-version.txt"
    if args.index_version is not None:
        index_version = args.index_version
    elif version_file.is_file():
        index_version = int(version_file.read_text(encoding="utf-8").strip() or "0") + 1
    else:
        index_version = 1
    build_index(args.content, args.out, index_version, args.base_url)
    version_file.write_text(str(index_version) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
