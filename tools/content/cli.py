#!/usr/bin/env python3
"""FamilyTV content authoring CLI: validate / build / inspect / diff."""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import zipfile
from pathlib import Path

# Allow running as script from repo root or tools/content
ROOT = Path(__file__).resolve().parents[2]
if str(Path(__file__).resolve().parent) not in sys.path:
    sys.path.insert(0, str(Path(__file__).resolve().parent))

from schema import (  # noqa: E402
    guess_media_type,
    sha256_file,
    validate_adventure_json,
    validate_attribute_apply,
    validate_count_combine,
    validate_manifest,
    verify_payload,
)
from quality import format_report, quality_check_source  # noqa: E402
from narration_coverage import audio_first_release_errors, format_coverage, narration_coverage  # noqa: E402

try:
    import yaml  # type: ignore
except ImportError:  # pragma: no cover
    yaml = None


def load_lesson(source: Path) -> dict:
    for name in ("lesson.yaml", "lesson.yml", "lesson.json"):
        path = source / name
        if path.is_file():
            text = path.read_text(encoding="utf-8")
            if path.suffix == ".json":
                return json.loads(text)
            if yaml is None:
                raise SystemExit("PyYAML required for lesson.yaml — pip install pyyaml, or use lesson.json")
            data = yaml.safe_load(text)
            if not isinstance(data, dict):
                raise SystemExit(f"{path} must be a mapping")
            return data
    raise SystemExit(f"No lesson.yaml/lesson.json in {source}")


def collect_payload_files(source: Path) -> list[Path]:
    files: list[Path] = []
    for folder in ("media", "images", "experiences", "sessions", "adventures", "narration", "audio", "content"):
        base = source / folder
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*")):
            if path.is_file() and path.name != "lesson.yaml":
                files.append(path)
    # Also include top-level experience/session json if present outside folders
    for path in sorted(source.glob("*.json")):
        if path.name not in {"lesson.json", "package.json"}:
            files.append(path)
    return files


def build_manifest(source: Path, lesson: dict) -> dict:
    package_id = lesson["packageId"]
    version = int(lesson["packageVersion"])
    content = lesson.get("content") or []
    if not content:
        raise SystemExit("lesson.content is required")

    payload = collect_payload_files(source)
    file_entries = []
    for path in payload:
        rel = path.relative_to(source).as_posix()
        file_entries.append(
            {
                "path": rel,
                "sha256": sha256_file(path),
                "size": path.stat().st_size,
                "mediaType": guess_media_type(rel),
            }
        )

    manifest = {
        "schemaVersion": int(lesson.get("schemaVersion", 1)),
        "packageId": package_id,
        "packageVersion": version,
        "title": lesson.get("title") or package_id,
        "description": lesson.get("description") or "",
        "content": content,
        "files": file_entries,
    }
    if lesson.get("sessions"):
        manifest["sessions"] = lesson["sessions"]
    return manifest


def cmd_validate(source: Path) -> int:
    lesson = load_lesson(source)
    # Validate experiences JSON parse
    errors: list[str] = []
    for path in (source / "experiences").glob("*.json") if (source / "experiences").is_dir() else []:
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            template = str(data.get("template", "")).strip()
            if template == "count_combine":
                ok_game, game_errors = validate_count_combine(data)
                if not ok_game:
                    errors.extend(f"{path.name}: {e}" for e in game_errors)
            elif template == "attribute_apply":
                ok_game, game_errors = validate_attribute_apply(data)
                if not ok_game:
                    errors.extend(f"{path.name}: {e}" for e in game_errors)
            elif "steps" not in data:
                errors.append(f"{path.name}: missing steps")
        except Exception as exc:  # noqa: BLE001
            errors.append(f"{path.name}: {exc}")
    for path in (source / "sessions").glob("*.json") if (source / "sessions").is_dir() else []:
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            if "items" not in data:
                errors.append(f"{path.name}: missing items")
        except Exception as exc:  # noqa: BLE001
            errors.append(f"{path.name}: {exc}")
    for path in (source / "adventures").glob("*.json") if (source / "adventures").is_dir() else []:
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            ok_adv, adv_errors = validate_adventure_json(data)
            if not ok_adv:
                errors.extend(f"{path.name}: {e}" for e in adv_errors)
        except Exception as exc:  # noqa: BLE001
            errors.append(f"{path.name}: {exc}")
    try:
        manifest = build_manifest(source, lesson)
    except SystemExit as exc:
        print(f"INVALID: {exc}")
        return 1
    ok, m_errors, warnings = validate_manifest(manifest)
    errors.extend(m_errors)
    errors.extend(audio_first_release_errors(source))
    cov = narration_coverage(source)
    if errors:
        print("INVALID source")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"OK source {lesson['packageId']}@{lesson['packageVersion']}")
    print(format_coverage(cov))
    for w in warnings:
        print(f"  warning: {w}")
    return 0


def cmd_build(source: Path, out_dir: Path) -> int:
    if cmd_validate(source) != 0:
        return 1
    lesson = load_lesson(source)
    manifest = build_manifest(source, lesson)
    staging = out_dir / f".staging-{lesson['packageId']}-{lesson['packageVersion']}"
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)

    for entry in manifest["files"]:
        src = source / entry["path"]
        dest = staging / entry["path"]
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dest)
    (staging / "package.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    ok, errors, warnings = validate_manifest(manifest)
    if not ok:
        print("INVALID manifest after build")
        for e in errors:
            print(f"  - {e}")
        shutil.rmtree(staging)
        return 1
    payload_ok, payload_errors = verify_payload(staging, manifest)
    if not payload_ok:
        print("INVALID payload hashes")
        for e in payload_errors:
            print(f"  - {e}")
        shutil.rmtree(staging)
        return 1

    out_dir.mkdir(parents=True, exist_ok=True)
    zip_path = out_dir / f"{lesson['packageId']}-v{lesson['packageVersion']}.zip"
    if zip_path.exists():
        zip_path.unlink()
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for path in sorted(staging.rglob("*")):
            if path.is_file():
                arc = f"{lesson['packageId']}/{path.relative_to(staging).as_posix()}"
                zf.write(path, arc)
    # Keep unpacked package copy for inspect/debug
    unpacked = out_dir / f"{lesson['packageId']}-v{lesson['packageVersion']}"
    if unpacked.exists():
        shutil.rmtree(unpacked)
    shutil.copytree(staging, unpacked)
    shutil.rmtree(staging)

    total = sum(f["size"] for f in manifest["files"])
    print(f"Built {zip_path}")
    print(f"  packageId={manifest['packageId']} version={manifest['packageVersion']}")
    print(f"  content items={len(manifest['content'])} files={len(manifest['files'])} bytes={total}")
    for w in warnings:
        print(f"  warning: {w}")
    for item in manifest["content"]:
        meta = item.get("metadata") or {}
        skills = ",".join(meta.get("skills") or item.get("skills") or [])
        print(f"  - {item['id']} [{item['type']}] {item.get('title')} skills={skills or '-'}")
    return 0


def read_package(path: Path) -> tuple[dict, Path | None]:
    if path.is_dir():
        manifest = json.loads((path / "package.json").read_text(encoding="utf-8"))
        return manifest, path
    if path.suffix.lower() == ".zip":
        import tempfile

        tmp = Path(tempfile.mkdtemp(prefix="ftv-pkg-"))
        with zipfile.ZipFile(path) as zf:
            zf.extractall(tmp)
        # find package.json
        candidates = list(tmp.rglob("package.json"))
        if not candidates:
            raise SystemExit("package.json not found in ZIP")
        root = candidates[0].parent
        manifest = json.loads(candidates[0].read_text(encoding="utf-8"))
        return manifest, root
    raise SystemExit(f"Unsupported package path: {path}")


def cmd_inspect(path: Path) -> int:
    manifest, root = read_package(path)
    ok, errors, warnings = validate_manifest(manifest)
    payload_ok, payload_errors = (True, [])
    if root is not None:
        payload_ok, payload_errors = verify_payload(root, manifest)
    total = sum(int(f.get("size", 0)) for f in manifest.get("files", []))
    print(f"packageId: {manifest.get('packageId')}")
    print(f"version: {manifest.get('packageVersion')}")
    print(f"title: {manifest.get('title')}")
    print(f"estimated size: {total} bytes")
    print(f"validation: {'OK' if ok and payload_ok else 'FAIL'}")
    for e in errors + payload_errors:
        print(f"  error: {e}")
    for w in warnings:
        print(f"  warning: {w}")
    print("content:")
    for item in manifest.get("content", []):
        meta = item.get("metadata") or {}
        print(
            f"  - {item.get('id')} type={item.get('type')} "
            f"age={meta.get('ageMin')}-{meta.get('ageMax')} "
            f"skills={meta.get('skills') or item.get('skills')} "
            f"minutes={meta.get('estimatedMinutes')} "
            f"sections={item.get('homeSections')}"
        )
    experiences = [f["path"] for f in manifest.get("files", []) if str(f["path"]).startswith("experiences/")]
    media = [f["path"] for f in manifest.get("files", []) if str(f["path"]).startswith("media/")]
    print(f"experiences: {experiences}")
    print(f"media: {media}")
    return 0 if ok and payload_ok else 1


def cmd_diff(a: Path, b: Path) -> int:
    ma, _ = read_package(a)
    mb, _ = read_package(b)
    print(f"{ma.get('packageId')}@{ma.get('packageVersion')} → {mb.get('packageId')}@{mb.get('packageVersion')}")
    if ma.get("packageId") != mb.get("packageId"):
        print("packageId changed")
    ids_a = {c["id"]: c for c in ma.get("content", [])}
    ids_b = {c["id"]: c for c in mb.get("content", [])}
    for cid in sorted(set(ids_a) - set(ids_b)):
        print(f"- removed content {cid}")
    for cid in sorted(set(ids_b) - set(ids_a)):
        print(f"+ added content {cid}: {ids_b[cid].get('title')}")
    for cid in sorted(set(ids_a) & set(ids_b)):
        if ids_a[cid] != ids_b[cid]:
            print(f"~ changed content {cid}")
            for key in sorted(set(ids_a[cid]) | set(ids_b[cid])):
                if ids_a[cid].get(key) != ids_b[cid].get(key):
                    print(f"    {key}: {ids_a[cid].get(key)!r} -> {ids_b[cid].get(key)!r}")
    files_a = {f["path"]: f for f in ma.get("files", [])}
    files_b = {f["path"]: f for f in mb.get("files", [])}
    for path in sorted(set(files_a) - set(files_b)):
        print(f"- removed file {path}")
    for path in sorted(set(files_b) - set(files_a)):
        print(f"+ added file {path}")
    for path in sorted(set(files_a) & set(files_b)):
        if files_a[path].get("sha256") != files_b[path].get("sha256"):
            print(f"~ file hash changed {path}")
    return 0


def cmd_quality(source: Path) -> int:
    errors, warnings = quality_check_source(source)
    print(format_report(source, errors, warnings))
    return 1 if errors else 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="content", description="FamilyTV content authoring")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_val = sub.add_parser("validate", help="Validate lesson source")
    p_val.add_argument("source", type=Path)

    p_build = sub.add_parser("build", help="Build package ZIP from lesson source")
    p_build.add_argument("source", type=Path)
    p_build.add_argument("-o", "--out", type=Path, default=ROOT / "dist")

    p_ins = sub.add_parser("inspect", help="Inspect built package dir/zip")
    p_ins.add_argument("package", type=Path)

    p_diff = sub.add_parser("diff", help="Diff two packages")
    p_diff.add_argument("package_a", type=Path)
    p_diff.add_argument("package_b", type=Path)

    p_q = sub.add_parser("quality", help="Optional content-quality warnings (authoring safety)")
    p_q.add_argument("source", type=Path)

    # Convenience: python tools/content/build.py SOURCE
    args = parser.parse_args(argv)
    if args.cmd == "validate":
        return cmd_validate(args.source.resolve())
    if args.cmd == "build":
        return cmd_build(args.source.resolve(), args.out.resolve())
    if args.cmd == "inspect":
        return cmd_inspect(args.package.resolve())
    if args.cmd == "diff":
        return cmd_diff(args.package_a.resolve(), args.package_b.resolve())
    if args.cmd == "quality":
        return cmd_quality(args.source.resolve())
    return 1


if __name__ == "__main__":
    # Support `python3 tools/content/build.py lessons/apples` as shortcut
    argv = sys.argv[1:]
    script = Path(sys.argv[0]).name
    if script == "build.py" and argv and not argv[0].startswith("-") and argv[0] not in {
        "validate", "build", "inspect", "diff",
    }:
        argv = ["build", *argv]
    raise SystemExit(main(argv))
