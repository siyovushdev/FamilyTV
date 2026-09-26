"""Narration coverage + AUDIO_FIRST readiness for FamilyTV packages.

Readiness levels:
  TECHNICALLY_WIRED          — spoken/audio refs present (may be DEV_PLACEHOLDER tones)
  SEMANTIC_NARRATION_READY   — DEV_VOICE or FINAL_VOICE (meaningful speech)
  PRODUCTION_READY           — FINAL_VOICE only

AUDIO_FIRST release gate requires SEMANTIC_NARRATION_READY (not placeholder tones).
Debug override: `.audio_first_dev_ok` allows TECHNICALLY_WIRED-only packages.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any


PLACEHOLDER_MARKERS = {"DEV_PLACEHOLDER", "PLACEHOLDER", "BEEP", "TONE"}
SEMANTIC_MARKERS = {"DEV_VOICE", "INTERIM_VOICE", "V1_CANDIDATE_VOICE", "FINAL_VOICE", "SEMANTIC_NARRATION_READY"}
PRODUCTION_MARKERS = {"FINAL_VOICE", "PRODUCTION_READY"}

COMMON_KEYS = {
    "hello", "lets_start", "explore", "solve", "create", "stories", "move", "play",
    "repeat_task", "try_again", "want_hint", "want_repeat", "good_thought",
    "did_not_hear", "go_next", "go_home", "discuss_together", "someone_answer",
    "other_turn", "choose_together", "well_done", "take_break",
}


def _has_spoken_path(spoken: str | None, audio_ref: str | None) -> bool:
    return bool((spoken and spoken.strip()) or (audio_ref and str(audio_ref).strip()))


def _resolve_exists(source: Path, ref: str | None) -> bool:
    if not ref:
        return False
    r = str(ref).strip()
    if r.startswith("common:"):
        return r.split(":", 1)[1] in COMMON_KEYS
    candidates = [
        source / r,
        source / "narration" / r,
        source / "audio" / r,
        source / "adventures" / r,
    ]
    return any(p.is_file() for p in candidates)


def detect_voice_quality(source: Path) -> str:
    """Return DEV_PLACEHOLDER | DEV_VOICE | FINAL_VOICE | UNKNOWN from markers."""
    candidates = [
        source / "narration" / "quality.json",
        source / "quality.json",
        source / "narration" / "common" / "manifest.json",
    ]
    # Also scan narration/*/quality.json and README
    for p in candidates:
        if p.is_file() and p.suffix == ".json":
            try:
                data = json.loads(p.read_text(encoding="utf-8"))
                q = str(data.get("quality") or data.get("readiness") or "").upper()
                if q in PLACEHOLDER_MARKERS | SEMANTIC_MARKERS | PRODUCTION_MARKERS:
                    if q == "SEMANTIC_NARRATION_READY":
                        return "DEV_VOICE"
                    if q == "PRODUCTION_READY":
                        return "FINAL_VOICE"
                    return q
            except Exception:
                pass
    for readme in source.rglob("README.txt"):
        if "narration" not in str(readme):
            continue
        text = readme.read_text(encoding="utf-8", errors="ignore").upper()
        if "FINAL_VOICE" in text:
            return "FINAL_VOICE"
        if "DEV_VOICE" in text:
            return "DEV_VOICE"
        if "DEV_PLACEHOLDER" in text or "PLACEHOLDER" in text:
            return "DEV_PLACEHOLDER"
    # App common pack default probe (when validating from repo root lessons)
    return "UNKNOWN"


def readiness_level(voice_quality: str, technically_wired: bool) -> str:
    if not technically_wired:
        return "NOT_WIRED"
    if voice_quality in PRODUCTION_MARKERS or voice_quality == "FINAL_VOICE":
        return "PRODUCTION_READY"
    if voice_quality in ("DEV_VOICE", "INTERIM_VOICE", "V1_CANDIDATE_VOICE") or voice_quality in SEMANTIC_MARKERS:
        return "SEMANTIC_NARRATION_READY"
    if voice_quality in PLACEHOLDER_MARKERS or voice_quality == "UNKNOWN":
        return "TECHNICALLY_WIRED"
    return "TECHNICALLY_WIRED"


def narration_coverage(source: Path) -> dict[str, Any]:
    """Summarize instruction/feedback/hint narration coverage for a lesson source."""
    voice_quality = detect_voice_quality(source)
    summary = {
        "instructions": {"have": 0, "total": 0},
        "feedback": {"have": 0, "total": 0},
        "hints": {"have": 0, "total": 0},
        "missing": [],
        "audio_first_ready": True,
        "technically_wired": True,
        "quality": "UNKNOWN",
        "voice_quality": voice_quality,
        "readiness": "NOT_WIRED",
    }
    claimed_audio_first = False
    adv_dir = source / "adventures"
    if adv_dir.is_dir():
        for path in sorted(adv_dir.glob("*.json")):
            data = json.loads(path.read_text(encoding="utf-8"))
            reading = str(data.get("readingSupport") or data.get("readingSupportMode") or "").upper()
            if reading == "AUDIO_FIRST":
                summary["quality"] = "AUDIO_FIRST"
                claimed_audio_first = True
            for scene in data.get("scenes") or []:
                if not isinstance(scene, dict):
                    continue
                kind = str(scene.get("kind", "")).upper()
                ch = scene.get("challenge") if isinstance(scene.get("challenge"), dict) else {}
                spoken = scene.get("spokenPrompt") or (ch.get("spokenPrompt") if ch else None)
                audio = scene.get("narrationAudio") or (ch.get("promptAudio") if ch else None)
                if kind in {"STORY", "CHALLENGE", "DIALOGUE", "OFFSCREEN", "MOVE"} or ch:
                    summary["instructions"]["total"] += 1
                    ok = _has_spoken_path(
                        spoken if isinstance(spoken, str) else None,
                        audio if isinstance(audio, str) else None,
                    )
                    if ok:
                        summary["instructions"]["have"] += 1
                    else:
                        summary["missing"].append(f"{path.name}:{scene.get('id')}:instruction")
                        summary["technically_wired"] = False
                        if reading == "AUDIO_FIRST":
                            summary["audio_first_ready"] = False
                if ch:
                    sc = ch.get("scaffolding") or {}
                    for key in ("hint1", "hint2", "explanation"):
                        if sc.get(key):
                            summary["hints"]["total"] += 1
                            summary["hints"]["have"] += 1
    exp_dir = source / "experiences"
    if exp_dir.is_dir():
        for path in sorted(exp_dir.glob("*.json")):
            data = json.loads(path.read_text(encoding="utf-8"))
            if str(data.get("readingSupport") or "").upper() == "AUDIO_FIRST":
                summary["quality"] = "AUDIO_FIRST"
                claimed_audio_first = True
            for step in data.get("steps") or []:
                if not isinstance(step, dict):
                    continue
                stype = str(step.get("type", "")).upper()
                if stype in {"MESSAGE", "QUESTION", "CHOICE", "TRUE_FALSE", "TRUEFALSE", "MOVE"}:
                    summary["instructions"]["total"] += 1
                    spoken = step.get("spokenPrompt")
                    audio = step.get("promptAudio")
                    if _has_spoken_path(
                        spoken if isinstance(spoken, str) else None,
                        audio if isinstance(audio, str) else None,
                    ):
                        summary["instructions"]["have"] += 1
                    else:
                        summary["missing"].append(f"{path.name}:{step.get('id')}:instruction")
                        summary["technically_wired"] = False
                        if str(data.get("readingSupport") or "").upper() == "AUDIO_FIRST":
                            summary["audio_first_ready"] = False
                fb = step.get("feedback") or {}
                if isinstance(fb, dict) and (fb.get("correctAudio") or step.get("correctFeedback")):
                    summary["feedback"]["total"] += 1
                    summary["feedback"]["have"] += 1
                if isinstance(fb, dict) and fb.get("hint"):
                    summary["hints"]["total"] += 1
                    summary["hints"]["have"] += 1

    summary["readiness"] = readiness_level(voice_quality, summary["technically_wired"])
    # Semantic AUDIO_FIRST claim requires semantic voice, not just wiring
    if claimed_audio_first and summary["readiness"] == "TECHNICALLY_WIRED":
        summary["audio_first_ready"] = False
    if claimed_audio_first and summary["readiness"] in ("SEMANTIC_NARRATION_READY", "PRODUCTION_READY"):
        if summary["technically_wired"]:
            summary["audio_first_ready"] = True
    return summary


def format_coverage(summary: dict[str, Any]) -> str:
    lines = ["AUDIO_FIRST coverage:"]
    for key in ("instructions", "feedback", "hints"):
        block = summary[key]
        lines.append(f"  {key.capitalize()}: {block['have']}/{block['total']}")
    lines.append("  Navigation: common voice pack")
    lines.append(
        f"  ready={summary['audio_first_ready']} claim={summary.get('quality')} "
        f"voice={summary.get('voice_quality')} readiness={summary.get('readiness')}"
    )
    for m in summary.get("missing") or []:
        lines.append(f"  missing: {m}")
    return "\n".join(lines)


def audio_first_release_errors(source: Path, allow_dev_override: bool = False) -> list[str]:
    """ERROR-level gaps for AUDIO_FIRST packages at release quality.

    Requires SEMANTIC_NARRATION_READY (DEV_VOICE+) — placeholder tones are not enough.
    `.audio_first_dev_ok` allows TECHNICALLY_WIRED for debug packages.
    """
    if allow_dev_override and (source / ".audio_first_dev_ok").is_file():
        return []
    summary = narration_coverage(source)
    claimed = summary.get("quality") == "AUDIO_FIRST"
    lesson = None
    for name in ("lesson.json", "lesson.yaml", "lesson.yml"):
        p = source / name
        if p.is_file() and p.suffix == ".json":
            lesson = json.loads(p.read_text(encoding="utf-8"))
            break
    if isinstance(lesson, dict):
        for item in lesson.get("content") or []:
            if not isinstance(item, dict):
                continue
            meta = item.get("metadata") or {}
            modes = [str(m).upper() for m in (meta.get("interactionModes") or [])]
            if "AUDIO_FIRST" in modes or str(meta.get("readingSupport") or "").upper() == "AUDIO_FIRST":
                claimed = True
    if not claimed:
        return []
    errors: list[str] = []
    if not summary["technically_wired"]:
        for m in summary.get("missing") or []:
            errors.append(f"AUDIO_FIRST release gate: {m}")
    readiness = summary.get("readiness")
    if readiness == "TECHNICALLY_WIRED":
        errors.append(
            "AUDIO_FIRST release gate: voice is DEV_PLACEHOLDER/unknown — "
            "need DEV_VOICE+ for SEMANTIC_NARRATION_READY (or .audio_first_dev_ok)"
        )
    elif readiness == "NOT_WIRED":
        errors.append("AUDIO_FIRST release gate: not technically wired")
    return errors
