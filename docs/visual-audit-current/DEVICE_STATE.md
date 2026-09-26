# FamilyTV Visual Audit — Device State

Captured: **2026-09-20** (local)

## Device

| Field | Value |
|-------|-------|
| ADB serial | `192.168.68.109:5555` |
| Product | ADT-3 (H728 board / Android TV) |
| Resolution | **1920×1080** (physical) |
| Android | **14** (API from getprop) |
| Only device attached | Yes (single `adb devices` entry) |

## APK / build

| Field | Value |
|-------|-------|
| Package | `ru.familytv` |
| Build type | **debug** (`installDebug`) |
| versionName | 1.0 |
| versionCode | 1 |
| Git commit (workspace) | `0720beb` (“Initial commit” baseline) |
| Working tree | **dirty** — Milestone 8.1/8.2 (and related) changes present but not committed as a named release tag |
| Capture tooling | `tools/visual_audit_capture.sh` + ad-hoc redo shell (no product redesign) |

## Content

| Field | Value |
|-------|-------|
| Bundled catalog | Adventures Space / Forest / Weather Lab, Story Builder, games, demo videos |
| Journeys | `space-grand-expedition`, `forest-secrets` (APK assets) |
| Journal unlocks | Present from prior M8.2 device usage |
| Publisher | `http://127.0.0.1:8080` via `adb reverse` for most shots; offline shot taken with reverse removed |
| Installed signed packages | Not required for this audit (bundled assets used) |

## Presentation / family settings during capture

| Setting | Typical value during capture |
|---------|------------------------------|
| ReadingSupportMode | Mostly `AUDIO_AND_TEXT`; dedicated shots for `AUDIO_FIRST` / `TEXT_RICH` |
| Non-reader simulation | Off except `10_home_non_reader_mode.png` |
| Household age filter | Varied for `153`–`155`; default otherwise |
| Active journey | Space grand expedition (completed and mid-progress states seeded for map shots) |

## AI / voice

| Field | Value |
|-------|-------|
| Cold Home AI load | Not expected (Home does not auto-load Qwen) |
| Microphone | USB mic **not** verified as attached for acoustic tests |
| Deterministic injection | **Yes**, used for Ask Lumi / UNKNOWN / clarification / some Home voice actions (`inject_transcript`) |
| Acoustic ASR quality | **Not evaluated** |

## Memory snapshot (indicative)

- `TOTAL PSS` ≈ **119513 KB** while app was resident near end of capture session (not a cold-start lab measurement)

## Capture honesty notes

- Screenshots are **real H728 `screencap`** at 1920×1080.
- Some adventure scenes were reached by **seeding `AdventureProgressStore` scene id** (existing prefs mechanism), then launching `AdventureActivity` — not a new navigation framework.
- Journey map mid/end states used **SharedPreferences seeding** and/or existing debug extras (`debug_complete_*`) from Milestone 8.2.
- No visual redesign or “beautify before shoot” code changes for this pack.
