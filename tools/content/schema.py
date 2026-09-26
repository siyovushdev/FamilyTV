"""FamilyTV content authoring — shared validation rules mirroring device ContentPackageParser."""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from typing import Any

SUPPORTED_SCHEMA = 1
MAX_FILE_BYTES = 200 * 1024 * 1024
MAX_PACKAGE_FILES = 500
FORBIDDEN_EXTENSIONS = {
    "apk", "dex", "so", "jar", "class", "exe", "bat", "cmd", "sh", "bin",
    "js", "mjs", "py", "rb", "php", "pl", "ps1", "vbs", "dll", "dylib",
}
ALLOWED_MEDIA_TYPES = {
    "application/json",
    "video/mp4",
    "video/webm",
    "image/png",
    "image/jpeg",
    "image/webp",
    "image/svg+xml",
    "audio/mpeg",
    "audio/mp4",
    "audio/aac",
    "audio/wav",
    "audio/ogg",
    "audio/x-wav",
    "text/plain",
}
CONTENT_TYPES = {"VIDEO", "GAME", "INTERACTIVE", "SESSION", "ADVENTURE"}
MEDIA_TYPE_BY_EXT = {
    ".json": "application/json",
    ".mp4": "video/mp4",
    ".webm": "video/webm",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".webp": "image/webp",
    ".svg": "image/svg+xml",
    ".mp3": "audio/mpeg",
    ".m4a": "audio/mp4",
    ".aac": "audio/aac",
    ".wav": "audio/wav",
    ".ogg": "audio/ogg",
    ".txt": "text/plain",
    ".yaml": "text/plain",
    ".yml": "text/plain",
}


def is_safe_relative_path(path: str) -> bool:
    if not path or path.startswith(("/", "\\")) or ":" in path:
        return False
    normalized = path.replace("\\", "/")
    if ".." in normalized or normalized.startswith("./"):
        return False
    return bool(re.fullmatch(r"[A-Za-z0-9._\-/]+", normalized))


def extension_allowed(path: str) -> bool:
    ext = Path(path).suffix.lower().lstrip(".")
    return not ext or ext not in FORBIDDEN_EXTENSIONS


def media_type_allowed(media_type: str | None) -> bool:
    if not media_type:
        return True
    return media_type.lower() in ALLOWED_MEDIA_TYPES


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        while True:
            chunk = fh.read(8192)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def guess_media_type(path: str) -> str | None:
    return MEDIA_TYPE_BY_EXT.get(Path(path).suffix.lower())


def validate_manifest(manifest: dict[str, Any]) -> tuple[bool, list[str], list[str]]:
    errors: list[str] = []
    warnings: list[str] = []
    if manifest.get("schemaVersion") != SUPPORTED_SCHEMA:
        errors.append(f"Unsupported schemaVersion={manifest.get('schemaVersion')} (expected {SUPPORTED_SCHEMA})")
    package_id = str(manifest.get("packageId", "")).strip()
    if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{1,63}", package_id or ""):
        errors.append("Invalid packageId")
    version = manifest.get("packageVersion")
    if not isinstance(version, int) or version < 1:
        errors.append("packageVersion must be >= 1")
    if not str(manifest.get("title", "")).strip():
        errors.append("Missing title")
    content = manifest.get("content")
    files = manifest.get("files")
    if not isinstance(content, list) or not content:
        errors.append("content array required and non-empty")
    if not isinstance(files, list) or not files:
        errors.append("files array required and non-empty")
    if errors:
        return False, errors, warnings

    content_ids: set[str] = set()
    file_paths: set[str] = set()
    for i, entry in enumerate(content):
        if not isinstance(entry, dict):
            errors.append(f"content[{i}] is not an object")
            continue
        cid = str(entry.get("id", "")).strip()
        if not cid:
            errors.append(f"content[{i}] missing id")
            continue
        if cid in content_ids:
            errors.append(f"Duplicate content id '{cid}'")
            continue
        content_ids.add(cid)
        ctype = str(entry.get("type", "")).strip().upper()
        if ctype not in CONTENT_TYPES:
            errors.append(f"content[{cid}] unknown type '{ctype}'")
            continue
        if ctype == "VIDEO" and not entry.get("mediaRef"):
            errors.append(f"content[{cid}] VIDEO requires mediaRef")
        if ctype == "GAME" and not entry.get("route"):
            errors.append(f"content[{cid}] GAME requires route")
        if ctype == "INTERACTIVE" and not entry.get("experienceRef"):
            errors.append(f"content[{cid}] INTERACTIVE requires experienceRef")
        if ctype == "SESSION" and not entry.get("sessionRef"):
            errors.append(f"content[{cid}] SESSION requires sessionRef")
        if ctype == "ADVENTURE" and not entry.get("adventureRef"):
            errors.append(f"content[{cid}] ADVENTURE requires adventureRef")

    # Adventure JSON structural checks when files are present on disk (caller may pass source root via files paths)
    for entry in content:
        if not isinstance(entry, dict):
            continue
        if str(entry.get("type", "")).strip().upper() != "ADVENTURE":
            continue
        ref = str(entry.get("adventureRef", "")).strip()
        if ref and ".." in ref:
            errors.append(f"Unsafe adventureRef '{ref}'")

    if len(files) > MAX_PACKAGE_FILES:
        errors.append(f"Too many files (>{MAX_PACKAGE_FILES})")
    for i, entry in enumerate(files):
        if not isinstance(entry, dict):
            errors.append(f"files[{i}] is not an object")
            continue
        path = str(entry.get("path", "")).strip().replace("\\", "/")
        if not is_safe_relative_path(path):
            errors.append(f"Unsafe path '{path}'")
            continue
        if not extension_allowed(path):
            errors.append(f"Forbidden executable/extension in '{path}'")
            continue
        if path in file_paths:
            errors.append(f"Duplicate file path '{path}'")
            continue
        file_paths.add(path)
        sha = str(entry.get("sha256", "")).strip().lower()
        if not re.fullmatch(r"[a-f0-9]{64}", sha or ""):
            errors.append(f"Invalid sha256 for '{path}'")
        size = entry.get("size")
        if not isinstance(size, int) or size < 0 or size > MAX_FILE_BYTES:
            errors.append(f"Invalid size for '{path}'")
        mt = entry.get("mediaType")
        if not media_type_allowed(mt if isinstance(mt, str) else None):
            errors.append(f"Disallowed mediaType '{mt}' for '{path}'")

    if "package.json" not in file_paths:
        warnings.append("package.json not listed in files (still validated separately)")

    for entry in content:
        if not isinstance(entry, dict):
            continue
        cid = entry.get("id")
        for key in ("mediaRef", "experienceRef", "sessionRef", "thumbnail"):
            ref = entry.get(key)
            if not ref:
                continue
            ref = str(ref).replace("\\", "/")
            if key == "experienceRef" and not ref.startswith("experiences/"):
                path = f"experiences/{ref}"
            elif key == "sessionRef" and not ref.startswith("sessions/"):
                path = f"sessions/{ref}"
            else:
                path = ref
            if not is_safe_relative_path(path if key != "thumbnail" or "/" in path else path):
                if key != "thumbnail":
                    errors.append(f"Unsafe {key} '{ref}'")
            elif key in ("mediaRef", "experienceRef", "sessionRef"):
                if path not in file_paths and ref not in file_paths:
                    errors.append(f"{key} '{ref}' missing from files")
        # adventureRef resolution
        adv_ref = entry.get("adventureRef")
        if adv_ref and str(entry.get("type", "")).strip().upper() == "ADVENTURE":
            ref = str(adv_ref).replace("\\", "/")
            path = ref if ref.startswith("adventures/") else f"adventures/{ref}"
            if not is_safe_relative_path(path):
                errors.append(f"Unsafe adventureRef '{ref}'")
            elif path not in file_paths and ref not in file_paths:
                errors.append(f"adventureRef '{ref}' missing from files")

    return not errors, errors, warnings


SCENE_KINDS = {"STORY", "CHALLENGE", "DIALOGUE", "OFFSCREEN", "MOVE", "REFLECTION"}
MECHANICS = {"VISUAL_SEARCH", "SORT_AND_CLASSIFY", "PATTERN_SEQUENCE", "PATH_MAZE"}


def validate_adventure_json(data: dict[str, Any]) -> tuple[bool, list[str]]:
    """Structural checks for Adventure JSON (mirrors AdventureParser.validate)."""
    errors: list[str] = []
    if not isinstance(data, dict):
        return False, ["adventure root must be object"]
    if not str(data.get("id", "")).strip():
        errors.append("adventure id required")
    if not str(data.get("title", "")).strip():
        errors.append("adventure title required")
    goals = data.get("learningGoals") or []
    if not isinstance(goals, list) or not goals:
        errors.append("adventure learningGoals empty")
    scenes = data.get("scenes")
    if not isinstance(scenes, list) or not scenes:
        errors.append("adventure needs scenes")
        return False, errors
    ids: list[str] = []
    for i, scene in enumerate(scenes):
        if not isinstance(scene, dict):
            errors.append(f"scenes[{i}] not object")
            continue
        sid = str(scene.get("id", "")).strip()
        if not sid:
            errors.append(f"scenes[{i}] missing id")
            continue
        if sid in ids:
            errors.append(f"duplicate scene id '{sid}'")
        ids.append(sid)
        kind = str(scene.get("kind", "")).strip().upper()
        if kind not in SCENE_KINDS:
            errors.append(f"scene '{sid}' unknown kind '{kind}'")
        nxt = scene.get("next")
        if nxt and str(nxt) not in ids and str(nxt) not in {str(s.get("id")) for s in scenes if isinstance(s, dict)}:
            # checked after full id set below
            pass
        if kind == "CHALLENGE":
            ch = scene.get("challenge")
            if not isinstance(ch, dict):
                errors.append(f"scene '{sid}' missing challenge")
                continue
            mech = str(ch.get("mechanic", "")).strip().upper()
            if mech not in MECHANICS:
                errors.append(f"challenge '{ch.get('id')}' unknown mechanic '{mech}'")
            lg = ch.get("learningGoals") or []
            if not isinstance(lg, list) or not lg:
                errors.append(f"challenge '{ch.get('id')}' missing learningGoals")
            sc = ch.get("scaffolding") or {}
            if isinstance(sc, dict):
                if not any(sc.get(k) for k in ("hint1", "hint2", "explanation")):
                    errors.append(f"challenge '{ch.get('id')}' missing scaffolding hints")
            if not ch.get("params"):
                errors.append(f"challenge '{ch.get('id')}' missing params")
        if kind == "DIALOGUE":
            accepted = scene.get("accepted") or []
            if not isinstance(accepted, list) or not accepted:
                errors.append(f"dialogue '{sid}' has no accepted mappings")

    id_set = set(ids)
    start = str(data.get("startSceneId") or (ids[0] if ids else "")).strip()
    if start not in id_set:
        errors.append("startSceneId missing")
    for scene in scenes:
        if not isinstance(scene, dict):
            continue
        sid = str(scene.get("id", ""))
        for key in ("next", "onMappedNext"):
            target = scene.get(key)
            if target and str(target) not in id_set:
                errors.append(f"scene '{sid}' {key}→{target} missing")
        ch = scene.get("challenge")
        if isinstance(ch, dict):
            sn = ch.get("successNext")
            if sn and str(sn) not in id_set:
                errors.append(f"challenge '{ch.get('id')}' successNext→{sn} missing")

    # BFS reachability
    if start in id_set:
        by_id = {str(s["id"]): s for s in scenes if isinstance(s, dict) and s.get("id")}
        reachable: set[str] = set()
        queue = [start]
        while queue:
            cur = queue.pop(0)
            if cur in reachable:
                continue
            reachable.add(cur)
            scene = by_id.get(cur)
            if not scene:
                continue
            for key in ("next", "onMappedNext"):
                t = scene.get(key)
                if t:
                    queue.append(str(t))
            ch = scene.get("challenge")
            if isinstance(ch, dict) and ch.get("successNext"):
                queue.append(str(ch["successNext"]))
        unreachable = id_set - reachable
        if unreachable:
            errors.append("unreachable scenes: " + ", ".join(sorted(unreachable)))

    return not errors, errors


JOURNEY_NODE_TYPES = {
    "ADVENTURE", "DISCOVERY", "CREATE", "GAME", "VIDEO", "SESSION", "REST", "HUB", "CONCLUSION",
}
COMPLETION_POLICIES = {"CONTENT_COMPLETED", "CONTENT_OPENED", "MANUAL"}


def validate_journey_json(data: dict[str, Any], known_content_ids: set[str] | None = None) -> tuple[bool, list[str], list[str]]:
    """Structural Journey checks — mirrors JourneyParser."""
    errors: list[str] = []
    warnings: list[str] = []
    if not isinstance(data, dict):
        return False, ["journey root must be object"], warnings
    if data.get("schemaVersion", 1) != 1:
        errors.append(f"Unsupported schemaVersion={data.get('schemaVersion')}")
    if not str(data.get("id", "")).strip():
        errors.append("missing id")
    if not str(data.get("title", "")).strip():
        errors.append("missing title")
    if not str(data.get("world", "")).strip():
        errors.append("missing world")
    start = str(data.get("startNode") or data.get("startNodeId") or "").strip()
    if not start:
        errors.append("missing startNode")
    nodes = data.get("nodes")
    if not isinstance(nodes, list) or not nodes:
        errors.append("nodes empty")
        return False, errors, warnings
    ids: set[str] = set()
    for i, node in enumerate(nodes):
        if not isinstance(node, dict):
            errors.append(f"nodes[{i}] not object")
            continue
        nid = str(node.get("id", "")).strip()
        if not nid:
            errors.append(f"nodes[{i}] missing id")
            continue
        if nid in ids:
            errors.append(f"duplicate node '{nid}'")
        ids.add(nid)
        ntype = str(node.get("type", "")).upper()
        if ntype not in JOURNEY_NODE_TYPES:
            errors.append(f"node '{nid}' unknown type '{ntype}'")
        policy = str(node.get("completionPolicy", "CONTENT_COMPLETED")).upper()
        if policy not in COMPLETION_POLICIES:
            errors.append(f"node '{nid}' invalid completionPolicy")
        cid = str(node.get("contentId") or "").strip()
        if not cid and ntype not in {"CONCLUSION", "HUB"} and policy != "MANUAL":
            errors.append(f"node '{nid}' missing contentId")
        if cid and known_content_ids is not None and cid not in known_content_ids:
            errors.append(f"node '{nid}' broken content ref '{cid}'")
        for key in ("prerequisites", "prerequisitesAnyOf", "journalUnlocks"):
            arr = node.get(key) or []
            if arr and not isinstance(arr, list):
                errors.append(f"node '{nid}' {key} must be array")
        for b in node.get("branches") or []:
            if not isinstance(b, dict):
                continue
            if not b.get("id") or not b.get("next"):
                errors.append(f"node '{nid}' branch incomplete")
    if start and start not in ids:
        errors.append(f"startNode '{start}' missing")
    for node in nodes:
        if not isinstance(node, dict):
            continue
        nid = str(node.get("id", ""))
        nxt = node.get("next")
        if nxt and str(nxt) not in ids and str(nxt).lower() != "null":
            errors.append(f"node '{nid}' next → missing '{nxt}'")
        for p in node.get("prerequisites") or []:
            if str(p) not in ids:
                errors.append(f"node '{nid}' prerequisite missing '{p}'")
        for p in node.get("prerequisitesAnyOf") or []:
            if str(p) not in ids:
                errors.append(f"node '{nid}' prerequisitesAnyOf missing '{p}'")
        for b in node.get("branches") or []:
            if isinstance(b, dict) and b.get("next") and str(b["next"]) not in ids:
                errors.append(f"node '{nid}' branch → missing '{b['next']}'")
    has_end = any(
        isinstance(n, dict)
        and (
            str(n.get("type", "")).upper() == "CONCLUSION"
            or (not n.get("next") and not n.get("branches"))
        )
        for n in nodes
    )
    if not has_end:
        errors.append("missing ending (CONCLUSION or terminal node)")
    return not errors, errors, warnings


def verify_payload(root: Path, manifest: dict[str, Any]) -> tuple[bool, list[str]]:
    errors: list[str] = []
    root = root.resolve()
    for entry in manifest.get("files", []):
        path = str(entry["path"]).replace("\\", "/")
        if not is_safe_relative_path(path):
            errors.append(f"Unsafe path '{path}'")
            continue
        file_path = (root / path).resolve()
        try:
            file_path.relative_to(root)
        except ValueError:
            errors.append(f"Path traversal detected for '{path}'")
            continue
        if not file_path.is_file():
            errors.append(f"Missing file '{path}'")
            continue
        size = file_path.stat().st_size
        if size != entry.get("size"):
            errors.append(f"Size mismatch for '{path}': expected {entry.get('size')}, got {size}")
        if size > MAX_FILE_BYTES:
            errors.append(f"File too large '{path}'")
        actual = sha256_file(file_path)
        if actual != str(entry.get("sha256", "")).lower():
            errors.append(f"SHA-256 mismatch for '{path}'")
    return not errors, errors
