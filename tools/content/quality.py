"""Optional content-quality checks for FamilyTV authoring.

These are safety/authoring warnings — they do not prove educational quality.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any


TV_TEXT_SOFT_LIMIT = 90
PASSIVE_SEQUENCE_WARN = 3  # consecutive video/message without interaction
SESSION_NO_INTERACTION_WARN = 2
LONG_PASSIVE_MINUTES = 8


def _load_json(path: Path) -> dict[str, Any] | None:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else None
    except Exception:
        return None


def _step_is_interactive(step: dict[str, Any]) -> bool:
    return str(step.get("type", "")).lower() in {
        "choice",
        "true_false",
        "truefalse",
        "question",
        "move",
        "break_or_move",
        "game",
    }


def _step_is_passive(step: dict[str, Any]) -> bool:
    return str(step.get("type", "")).lower() in {"video", "message", "break"}


def check_experience(path: Path, data: dict[str, Any]) -> list[str]:
    warnings: list[str] = []
    steps = data.get("steps") or []
    if not isinstance(steps, list) or not steps:
        return warnings

    # Long passive sequences
    run = 0
    for step in steps:
        if not isinstance(step, dict):
            continue
        if _step_is_passive(step):
            run += 1
            if run >= PASSIVE_SEQUENCE_WARN:
                warnings.append(
                    f"{path.name}: long passive sequence (≥{PASSIVE_SEQUENCE_WARN} video/message without interaction)"
                )
                break
        else:
            run = 0

    # Transition loops / missing targets
    ids = {s.get("id") for s in steps if isinstance(s, dict)}
    for step in steps:
        if not isinstance(step, dict):
            continue
        sid = step.get("id")
        transitions = step.get("transitions") or {}
        if not isinstance(transitions, dict):
            transitions = {}
        for key in ("next", "onCorrect", "onWrong", "onUnknown"):
            target = transitions.get(key) or step.get(key)
            if not target or target == "__end__":
                continue
            if target not in ids:
                warnings.append(f"{path.name}: step '{sid}' transitions.{key} → missing '{target}'")
        # intentional retry loops — warn once if no hint/retryLimit
        wrong_t = transitions.get("onWrong") or step.get("onWrong")
        unknown_t = transitions.get("onUnknown") or step.get("onUnknown")
        if sid and (wrong_t == sid or unknown_t == sid):
            fb = step.get("feedback") or {}
            if not fb.get("hint") and not fb.get("retryLimit"):
                warnings.append(
                    f"{path.name}: step '{sid}' retries forever without hint/retryLimit"
                )

    # Excessively long TV text
    for step in steps:
        if not isinstance(step, dict):
            continue
        for field in ("text", "prompt"):
            text = step.get(field)
            if isinstance(text, str) and len(text) > TV_TEXT_SOFT_LIMIT:
                warnings.append(
                    f"{path.name}: step '{step.get('id')}' {field} is long for TV ({len(text)} chars)"
                )

        # Accessibility / narration metadata
        stype = str(step.get("type", "")).lower()
        if stype in {"choice", "true_false", "truefalse", "move", "question", "message"}:
            if not step.get("promptAudio") and not (step.get("feedback") or {}).get("feedbackAudio"):
                # soft: only note once per file later
                pass

    interactive = sum(1 for s in steps if isinstance(s, dict) and _step_is_interactive(s))
    if interactive == 0 and len(steps) >= 3:
        warnings.append(f"{path.name}: experience has no interactive steps")

    has_narration = any(
        isinstance(s, dict) and (s.get("promptAudio") or (s.get("feedback") or {}).get("correctAudio"))
        for s in steps
    )
    if not has_narration:
        warnings.append(f"{path.name}: no narration audio refs (promptAudio/feedback audio) — text-only OK")

    return warnings


def check_session(path: Path, data: dict[str, Any]) -> list[str]:
    warnings: list[str] = []
    items = data.get("items") or []
    if not items:
        warnings.append(f"{path.name}: session has no items")
    minutes = data.get("estimatedMinutes")
    if not minutes:
        warnings.append(f"{path.name}: missing estimatedMinutes")
    # Movement presence is soft — cannot know content types without catalog
    return warnings


def check_content_item(item: dict[str, Any], package_id: str) -> list[str]:
    warnings: list[str] = []
    cid = item.get("id", "?")
    meta = item.get("metadata") or {}
    if not isinstance(meta, dict):
        meta = {}
    if meta.get("estimatedMinutes") is None and item.get("type") != "VIDEO":
        warnings.append(f"{package_id}/{cid}: missing estimatedMinutes")
    if meta.get("ageMin") is None and meta.get("ageMax") is None and not item.get("ageRange"):
        warnings.append(f"{package_id}/{cid}: missing age metadata")
    modes = meta.get("interactionModes") or []
    if item.get("type") == "INTERACTIVE" and not modes:
        warnings.append(f"{package_id}/{cid}: missing interactionModes")
    if item.get("type") == "SESSION":
        # movement sessions should mention move somehow
        purpose = str(meta.get("contentPurpose") or "").lower()
        skills = [str(s).lower() for s in (meta.get("skills") or [])]
        sections = [str(s).lower() for s in (item.get("homeSections") or [])]
        if "move" in purpose or "move" in sections or any("move" in s for s in skills):
            pass  # ok
    title = str(item.get("title") or "")
    if len(title) > 40:
        warnings.append(f"{package_id}/{cid}: title may be long for TV cards ({len(title)} chars)")
    return warnings


def check_journey(path: Path, data: dict[str, Any]) -> list[str]:
    """Journey quality warnings — not pedagogical proof."""
    warnings: list[str] = []
    nodes = data.get("nodes") or []
    if not isinstance(nodes, list):
        return warnings
    required = [n for n in nodes if isinstance(n, dict) and not n.get("optional")]
    if len(required) > 10:
        warnings.append(f"{path.name}: many required nodes ({len(required)}) — may feel long")
    minutes = data.get("estimatedMinutes")
    age_max = data.get("ageMax")
    if isinstance(minutes, int) and isinstance(age_max, int):
        if age_max <= 5 and minutes > 25:
            warnings.append(f"{path.name}: estimatedMinutes={minutes} may be long for ageMax={age_max}")
        if age_max <= 8 and minutes > 40:
            warnings.append(f"{path.name}: estimatedMinutes={minutes} may be long for ageMax={age_max}")
    has_branch = any(isinstance(n, dict) and n.get("branches") for n in nodes)
    if len(required) >= 5 and not has_branch:
        warnings.append(f"{path.name}: long journey without meaningful branch")
    # fake branch: single branch only
    for n in nodes:
        if not isinstance(n, dict):
            continue
        br = n.get("branches") or []
        if isinstance(br, list) and len(br) == 1:
            warnings.append(f"{path.name}: node '{n.get('id')}' has fake branch (only one option)")
    has_rest = any(
        isinstance(n, dict) and str(n.get("type", "")).upper() in {"REST", "MOVE"}
        for n in nodes
    )
    if len(required) >= 6 and not has_rest:
        warnings.append(f"{path.name}: long journey without movement/rest node")
    # consecutive passive (VIDEO / CONCLUSION hubs)
    run = 0
    for n in nodes:
        if not isinstance(n, dict):
            continue
        t = str(n.get("type", "")).upper()
        if t in {"VIDEO", "CONCLUSION"}:
            run += 1
            if run >= 3:
                warnings.append(f"{path.name}: long passive sequence of VIDEO/CONCLUSION")
                break
        else:
            run = 0
    # reading dependency soft check
    for n in nodes:
        if not isinstance(n, dict):
            continue
        if not n.get("spokenLabel") and n.get("mapLabel"):
            warnings.append(
                f"{path.name}: node '{n.get('id')}' missing spokenLabel (AUDIO_FIRST map)"
            )
            break
    return warnings


def quality_check_source(source: Path) -> tuple[list[str], list[str]]:
    """Return (errors, warnings). Errors are reserved for unreadable sources."""
    errors: list[str] = []
    warnings: list[str] = []

    lesson_path = None
    for name in ("lesson.json", "lesson.yaml", "lesson.yml"):
        if (source / name).is_file():
            lesson_path = source / name
            break
    if lesson_path is None:
        errors.append(f"No lesson.json in {source}")
        return errors, warnings

    if lesson_path.suffix == ".json":
        lesson = _load_json(lesson_path)
    else:
        try:
            import yaml  # type: ignore

            lesson = yaml.safe_load(lesson_path.read_text(encoding="utf-8"))
        except Exception as e:
            errors.append(f"Cannot read {lesson_path}: {e}")
            return errors, warnings

    if not isinstance(lesson, dict):
        errors.append("lesson must be an object")
        return errors, warnings

    package_id = str(lesson.get("packageId") or source.name)
    for item in lesson.get("content") or []:
        if isinstance(item, dict):
            warnings.extend(check_content_item(item, package_id))

    exp_dir = source / "experiences"
    if exp_dir.is_dir():
        for path in sorted(exp_dir.glob("*.json")):
            data = _load_json(path)
            if data:
                warnings.extend(check_experience(path, data))
            else:
                errors.append(f"Unreadable experience {path.name}")

    sess_dir = source / "sessions"
    if sess_dir.is_dir():
        has_move_ref = False
        for path in sorted(sess_dir.glob("*.json")):
            data = _load_json(path)
            if not data:
                errors.append(f"Unreadable session {path.name}")
                continue
            warnings.extend(check_session(path, data))
            for it in data.get("items") or []:
                if isinstance(it, dict) and "move" in str(it.get("contentId", "")).lower():
                    has_move_ref = True
        if sess_dir.is_dir() and not has_move_ref:
            warnings.append(f"{package_id}: session may lack a movement break item")

    adv_dir = source / "adventures"
    if adv_dir.is_dir():
        for path in sorted(adv_dir.glob("*.json")):
            data = _load_json(path)
            if not data:
                errors.append(f"Unreadable adventure {path.name}")
                continue
            warnings.extend(check_adventure_audio_first(path, data))
            warnings.extend(check_discovery_adventure(path, data))

    know_dir = source / "knowledge"
    if know_dir.is_dir():
        for path in sorted(know_dir.glob("*.json")):
            data = _load_json(path)
            if not data:
                errors.append(f"Unreadable knowledge {path.name}")
                continue
            warnings.extend(check_knowledge_pack(path, data))

    story_dir = source / "stories"
    if story_dir.is_dir():
        for path in sorted(story_dir.glob("*.json")):
            data = _load_json(path)
            if not data:
                errors.append(f"Unreadable story {path.name}")
                continue
            warnings.extend(check_story_grammar(path, data))

    journey_dir = source / "journeys"
    if journey_dir.is_dir():
        from schema import validate_journey_json  # local import to avoid cycles

        known = {
            str(c.get("id"))
            for c in (lesson.get("content") or [])
            if isinstance(c, dict) and c.get("id")
        }
        for path in sorted(journey_dir.glob("*.json")):
            data = _load_json(path)
            if not data:
                errors.append(f"Unreadable journey {path.name}")
                continue
            ok, jerr, jwarn = validate_journey_json(data, known_content_ids=known or None)
            if not ok:
                errors.extend(f"{path.name}: {e}" for e in jerr)
            warnings.extend(jwarn)
            warnings.extend(check_journey(path, data))

    # Interaction gap in multi-experience packages
    content = lesson.get("content") or []
    interactive_like = [
        c for c in content
        if isinstance(c, dict) and c.get("type") in {"INTERACTIVE", "GAME", "ADVENTURE"}
    ]
    if len(content) >= SESSION_NO_INTERACTION_WARN + 1 and not interactive_like:
        warnings.append(f"{package_id}: package has several items but no INTERACTIVE/GAME/ADVENTURE")

    return errors, warnings


def check_discovery_adventure(path: Path, data: dict[str, Any]) -> list[str]:
    """Discovery authoring: avoid fake experiments and missing observations."""
    warnings: list[str] = []
    scenes = data.get("scenes") or []
    if not isinstance(scenes, list):
        return warnings
    for scene in scenes:
        if not isinstance(scene, dict):
            continue
        ch = scene.get("challenge") if isinstance(scene.get("challenge"), dict) else None
        if not ch:
            continue
        mechanic = str(ch.get("mechanic", "")).upper()
        params = ch.get("params") or {}
        if mechanic == "PREDICT_AND_TEST":
            outcomes = params.get("outcomeByOptionId") or {}
            if isinstance(outcomes, dict):
                uniq = set(outcomes.values())
                if len(outcomes) >= 2 and len(uniq) == 1:
                    warnings.append(
                        f"{path.name}: PREDICT_AND_TEST '{ch.get('id')}' same outcome everywhere (fake experiment)"
                    )
                if any(not str(v).strip() for v in outcomes.values()):
                    warnings.append(f"{path.name}: PREDICT_AND_TEST '{ch.get('id')}' empty observation")
            opts = params.get("options") or []
            if isinstance(opts, list) and opts and not ch.get("spokenPrompt") and not ch.get("promptAudio"):
                warnings.append(f"{path.name}: discovery '{ch.get('id')}' reading dependency — no spokenPrompt")
        if mechanic == "BUILD_AND_TEST":
            if not params.get("failObservation"):
                warnings.append(f"{path.name}: BUILD_AND_TEST '{ch.get('id')}' missing failObservation (why)")
            if not params.get("successObservation"):
                warnings.append(f"{path.name}: BUILD_AND_TEST '{ch.get('id')}' missing successObservation")
        expl = (ch.get("scaffolding") or {}).get("explanation") if isinstance(ch.get("scaffolding"), dict) else None
        if expl and len(str(expl)) > 180:
            warnings.append(f"{path.name}: challenge '{ch.get('id')}' excessive explanation for age range")
    return warnings


def check_knowledge_pack(path: Path, data: dict[str, Any]) -> list[str]:
    warnings: list[str] = []
    entries = data.get("entries") or []
    if not isinstance(entries, list) or not entries:
        warnings.append(f"{path.name}: knowledge pack has no entries")
        return warnings
    for e in entries:
        if not isinstance(e, dict):
            continue
        eid = e.get("id", "?")
        facts = e.get("facts") or []
        if not facts:
            warnings.append(f"{path.name}: entry '{eid}' missing facts")
        if not e.get("shortAnswer"):
            warnings.append(f"{path.name}: entry '{eid}' missing shortAnswer")
        ans = str(e.get("shortAnswer") or "")
        if len(ans) > 160:
            warnings.append(f"{path.name}: entry '{eid}' overly long explanation for age range")
        if ans and not facts:
            warnings.append(f"{path.name}: entry '{eid}' answer without approved facts")
        prov = e.get("provenance") if isinstance(e.get("provenance"), dict) else None
        if not prov and not e.get("sourceMetadata"):
            warnings.append(f"{path.name}: entry '{eid}' knowledge without provenance warning")
        if not e.get("spokenAnswer") and not e.get("spokenAnswerAudio"):
            warnings.append(f"{path.name}: entry '{eid}' text-only spoken gap (AUDIO_FIRST)")
        if e.get("experimentActionRef") is not None and not str(e.get("experimentActionRef")).strip():
            warnings.append(f"{path.name}: entry '{eid}' dangling empty experimentActionRef")
    return warnings


def knowledge_audio_coverage(path: Path, data: dict[str, Any]) -> dict[str, int]:
    """Authoring summary: spoken-ready vs text-only knowledge entries."""
    entries = data.get("entries") or []
    total = 0
    spoken = 0
    text_only = 0
    reviewed = 0
    for e in entries:
        if not isinstance(e, dict):
            continue
        total += 1
        if e.get("spokenAnswer") or e.get("spokenAnswerAudio"):
            spoken += 1
        else:
            text_only += 1
        prov = e.get("provenance") if isinstance(e.get("provenance"), dict) else {}
        if str(prov.get("reviewStatus", "")).lower() in {"reviewed", "approved"} or e.get("sourceMetadata"):
            reviewed += 1
    return {
        "total": total,
        "spoken_ready": spoken,
        "text_only": text_only,
        "source_reviewed": reviewed,
    }


def check_story_grammar(path: Path, data: dict[str, Any]) -> list[str]:
    warnings: list[str] = []
    steps = data.get("steps") or []
    if not isinstance(steps, list) or not steps:
        warnings.append(f"{path.name}: story has no steps")
        return warnings
    kinds = [str(s.get("kind", "")).upper() for s in steps if isinstance(s, dict)]
    if "ENDING" not in kinds and not data.get("choiceToEnding"):
        warnings.append(f"{path.name}: missing endings")
    choice_map = data.get("choiceToEnding") or {}
    if isinstance(choice_map, dict) and choice_map:
        endings = set(choice_map.values())
        if len(endings) < 2:
            warnings.append(f"{path.name}: choices may not affect ending (single mapped ending)")
    for s in steps:
        if not isinstance(s, dict):
            continue
        opts = s.get("options") or []
        if not opts:
            warnings.append(f"{path.name}: step '{s.get('id')}' has no options (dead branch risk)")
        if not s.get("spokenPrompt") and not s.get("prompt"):
            warnings.append(f"{path.name}: step '{s.get('id')}' inaccessible narration")
        prompt = str(s.get("prompt") or "")
        if prompt and not s.get("spokenPrompt") and len(prompt) > 20:
            warnings.append(f"{path.name}: step '{s.get('id')}' reading dependency — no spokenPrompt")
    if not data.get("retellTemplate"):
        warnings.append(f"{path.name}: missing retellTemplate")
    return warnings


def check_adventure_audio_first(path: Path, data: dict[str, Any]) -> list[str]:
    """Warn when AUDIO_FIRST-oriented adventures lack spoken paths. Not pedagogy proof."""
    warnings: list[str] = []
    reading = str(data.get("readingSupport") or data.get("readingSupportMode") or "AUDIO_AND_TEXT").upper()
    # Flagship/family packages default to audio-first expectations for child challenges
    force = reading in {"AUDIO_FIRST", "AUDIOFIRST"} or "audio" in str(data.get("tags") or "").lower()
    scenes = data.get("scenes") or []
    if not isinstance(scenes, list):
        return warnings
    for scene in scenes:
        if not isinstance(scene, dict):
            continue
        sid = scene.get("id", "?")
        kind = str(scene.get("kind", "")).upper()
        text = scene.get("text") or scene.get("prompt") or ""
        spoken = scene.get("spokenPrompt")
        narr = scene.get("narrationAudio")
        ch = scene.get("challenge") if isinstance(scene.get("challenge"), dict) else {}
        if kind == "CHALLENGE" and ch:
            text = ch.get("prompt") or text
            spoken = ch.get("spokenPrompt") or spoken
            narr = ch.get("promptAudio") or narr
            params = ch.get("params") or {}
            objects = (
                params.get("objects")
                or params.get("items")
                or params.get("options")
                or params.get("parts")
                or []
            )
            if isinstance(objects, list) and objects and force:
                missing_voice = False
                for obj in objects:
                    if isinstance(obj, dict) and not obj.get("voiceAliases") and not obj.get("label"):
                        missing_voice = True
                    if isinstance(obj, str):
                        # pattern options are spoken via voiceAccept
                        pass
                if objects and not ch.get("spokenPrompt") and not narr:
                    warnings.append(
                        f"{path.name}: challenge '{ch.get('id')}' AUDIO_FIRST gap — no spokenPrompt/promptAudio"
                    )
        if force or reading == "AUDIO_FIRST":
            body = str(text or "")
            if body and not spoken and not narr and len(body) > 20:
                warnings.append(
                    f"{path.name}: scene '{sid}' important text without spokenPrompt/narrationAudio"
                )
            if len(body) > 140 and not spoken:
                warnings.append(f"{path.name}: scene '{sid}' long text without short spokenPrompt")
    return warnings


def format_report(source: Path, errors: list[str], warnings: list[str]) -> str:
    lines = [f"quality: {source}"]
    if not errors and not warnings:
        lines.append("  OK (no quality warnings)")
    for e in errors:
        lines.append(f"  error: {e}")
    for w in warnings:
        lines.append(f"  warning: {w}")
    return "\n".join(lines)
