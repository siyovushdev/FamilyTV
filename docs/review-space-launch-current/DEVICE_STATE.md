# Space Launch Review Pack — Device State

Captured: **2026-09-21** (local)

## Device

| Field | Value |
|-------|-------|
| ADB serial | `192.168.68.109:5555` |
| Product | ADT-3 (H728 / Android TV) |
| Resolution | **1920×1080** physical |
| Density | 320 dpi |
| Android | **14** (API 34) |
| Only device attached | Yes |

## APK / build

| Field | Value |
|-------|-------|
| Package | `ru.familytv` |
| Build type | debug (`installDebug`) |
| versionName | 1.0 |
| versionCode | 1 |
| lastUpdateTime (device) | 2026-09-21 11:33:00 |
| Git commit (workspace) | `0720beb60c454110975082d863d705158aacdc64` (“Initial commit”) |
| Working tree | **dirty** — Space Launch vertical-slice changes present, not a clean tagged release |

## Content

| Field | Value |
|-------|-------|
| Entry | bundled catalog item `adv-space-expedition` |
| Adventure JSON | `space-expedition.json` (assets) |
| Manifest item version field | `1` |
| Installable package zip | not required for this capture (bundled assets) |

## Presentation / family settings during capture

| Setting | Value |
|---------|-------|
| ReadingSupportMode | `AUDIO_AND_TEXT` (`familytv_family_settings.xml` → `reading_mode`) |
| HouseholdAgeBand | prefs `age_min=7`, `age_max=9` |
| NON_READER_SIM | **OFF** for main recording; **ON** only for `31_non_reader_sim.png` (DEBUG_ONLY) |
| High focus pref | `high_focus=true` |
| SFX | `sfx_enabled=true` |

## Input / voice

| Field | Value |
|-------|-------|
| Capture input method | `adb shell input tap` / `keyevent` (simulates remote) |
| Physical remote | not used for this pack |
| Microphone permission | `RECORD_AUDIO` **granted** |
| USB audio device | USB headset appears in audio dumps as **output** (`USB-Audio - Logi USB Headset H340`); live ASR quality **not evaluated** |
| Transcript injection | **not used** |

## Memory snapshots (indicative)

| Moment | TOTAL PSS |
|--------|----------|
| Fresh Home | ≈ 116 MB (`logs/meminfo_home.txt`) |
| Space start (intro) | ≈ 124 MB (`logs/meminfo_space_start.txt`) |
| Mid challenge | ≈ 164 MB (`logs/meminfo_space_mid.txt`) |
| After next scene | ≈ 154 MB (`logs/meminfo_space_end.txt`) |

Not a cold-start lab protocol; app had prior installs the same day.

## Capture honesty

- Main recording + primary screenshots: **cleared** `familytv_adventure_progress` before run; no scene seeding.
- `01b_details_space.png` / Start: opened via `ContentDetailsActivity` with `contentId=adv-space-expedition` after cold Home (catalog path, not jump-to-mechanic).
- Secondary object/non-reader shots: second Activity open after clear; still no scene seed.
- `adb shell screenrecord`: **no internal audio** on this device path.
