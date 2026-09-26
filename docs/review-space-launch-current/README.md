# Space Launch — Independent Review Pack

**Purpose:** Raw visual evidence of the current Space Launch vertical slice on real H728 for external review.  
**Not** a product redesign pass. **No PASS/FAIL verdict in this pack.**

**Folder:** `docs/review-space-launch-current/`  
**Contact sheet:** `space_launch_contact_sheet.png` (thumbnails + filenames; originals in `screenshots/`)

---

## How to review

1. Watch `recordings/space_launch_full_review.mp4` continuously (no montage substitute).
2. Open screenshots by filename from the table below.
3. Compare before/after world: `50_world_before_success.png` vs `51_world_after_success.png`.
4. Ignore README prose for quality judgment — use pixels + video.

---

## Recordings

| File | Kind | Audio | Notes |
|------|------|-------|-------|
| `recordings/space_launch_full_review.mp4` | REAL continuous `adb screenrecord` | **No** internal audio on this H728 path | Cold stop → Home → Details → Start → intro → challenge (wrong, Help×2, Repeat, 3 installs) → flight → nav maze |

Optional external phone/camera audio recording: **not captured** in this pack.

---

## Screenshots

Legend: **REAL** = normal Activity + progress clear (no scene seed). **DEBUG_ONLY** = debug pref / secondary reopen marked below.

| File | Kind | Exact state | What happened before | Remote | Injection | Audio in shot |
|------|------|-------------|----------------------|--------|-----------|---------------|
| `01_home_space.png` | REAL | Cold Home | `force-stop` then `HomeActivity` | adb key/tap sim | no | n/a |
| `01b_details_space.png` | REAL | Content Details Space | Started Details with `contentId=adv-space-expedition` | adb | no | n/a |
| `02_space_intro.png` | REAL | Intro STORY | Tapped **Начать** | adb tap | no | screenrecord silent |
| `03_ship_problem.png` | REAL | Intro — ship empty slots | Same frame family as intro | adb | no | silent |
| `04_ship_problem_close_state.png` | REAL | Intro — problem emphasis | Same intro | adb | no | silent |
| `05_objects_initial.png` | REAL | `launch_repair` challenge | Tapped **Дальше** from intro | adb | no | silent |
| `06_wrong_choice_before.png` | REAL | Challenge before wrong | DPAD attempts then about to tap rock | adb | no | silent |
| `07_wrong_choice_reaction.png` | REAL | Soft-reject after rock | Tapped **Камень** | adb | no | silent |
| `08_help_level1.png` | REAL | After Help #1 | Tapped **Помоги**; may be FEEDBACK chrome | adb | no | silent |
| `09_help_visual_highlight.png` | REAL | Help visual (slots) | Same Help #1 window | adb | no | silent |
| `10a_before_repeat.png` | REAL | Before Repeat | After Help #2 dismissed | adb | no | silent |
| `10_repeat_state.png` | REAL | After Repeat | Tapped **Повтори**; installed cells still 0 | adb | no | **Cannot verify spoken repeat from silent screenrecord** |
| `11_first_cell_selected.png` | REAL | Pre first install | Energy targets enumerated | adb | no | silent |
| `12_first_cell_installed.png` | REAL | 1/3 installed | Tapped first energy | adb | no | silent |
| `13_second_cell_installed.png` | REAL | 2/3 installed | Tapped second energy | adb | no | silent |
| `14_all_cells_installed.png` | REAL | Success feedback / 3 cells | Tapped third energy | adb | no | silent |
| `15_engine_activation.png` | REAL | Engine activation UI | Same success window as 14 | adb | no | silent |
| `16_lumi_success_reaction.png` | REAL | Lumi on success | Same success window as 14 | adb | no | silent |
| `17_ship_moving.png` | REAL | `launch_flight` STORY | Tapped **Дальше** after success | adb | no | silent |
| `18_next_scene.png` | REAL | `nav_maze` PATH_MAZE | Tapped **Дальше** from flight | adb | no | silent |
| `20_focus_object_1.png` | REAL | After DPAD | `DPAD_DOWN`+`LEFT` | adb keyevent | no | silent |
| `21_focus_object_2.png` | REAL | After DPAD RIGHT | `DPAD_RIGHT` | adb keyevent | no | silent |
| `22_focus_wrong_object.png` | REAL | Approach rock | Before rock tap | adb | no | silent |
| `23_focus_repeat.png` | REAL | Chrome before Help | After wrong reaction | adb | no | silent |
| `24_focus_help.png` | REAL | After Help cycle | Help #2 dismissed | adb | no | silent |
| `30_normal_text.png` | REAL | Challenge text visible | Second open, progress cleared, NON_READER off | adb | no | n/a |
| `31_non_reader_sim.png` | **DEBUG_ONLY** | Same challenge stage | `familytv_debug.non_reader_simulation=true` then reopen; **not** a Parent Mode child setting | adb | no | n/a |
| `40_energy_objects.png` | REAL | Object tray clarity | Second open, cleared progress, on challenge | adb | no | n/a |
| `41_ship_slots.png` | REAL | Empty slots clarity | Same second open | adb | no | n/a |
| `42_ship_before_after_comparison.png` | REAL | Flight beat (after) | Same as `17`; compare to `03`/`04` for before | adb | no | silent |
| `50_world_before_success.png` | REAL | World before complete repair | After Repeat, before energy installs | adb | no | silent |
| `51_world_after_success.png` | REAL | World after repair | Success window | adb | no | silent |
| `60_help_0.png` | REAL | Help level 0 | Challenge before any Help | adb | no | silent |
| `61_help_1.png` | REAL | Help level 1 | After Help #1 | adb | no | silent |
| `62_help_2.png` | REAL | Help level 2 | After Help #2 | adb | no | silent |
| `70_ship_end_of_launch.png` | REAL | End of launch success | Success window | adb | no | silent |
| `71_next_scene_start.png` | REAL | Next scene start | Same as `18` | adb | no | silent |

---

## Focus (factual)

- Focus capture used `adb shell input keyevent` DPAD, not a physical remote.
- Whether focus rim is obvious at 2–3 m is for the reviewer to judge from `20`–`24`.
- First energy tile often reports **inflated accessibility bounds** on this device; install taps used left-edge compensation (`x1+88`). Visual layout still shows six object cells.

## Help (factual)

| Shot | Observed in capture notes |
|------|---------------------------|
| `60_help_0` | Pre-help challenge UI |
| `61_help_1` / `08` / `09` | After Help #1 — guide copy about empty slots; yellow slot stroke expected from scaffold HINT1 |
| `62_help_2` | After Help #2 — FEEDBACK path then dismiss via **Дальше**; energy highlight expected if HINT2_VISUAL applied |

If reviewer sees only text change on a given Help press, that is a valid observation of the current build.

## Repeat (factual)

- `10a` → `10`: Repeat pressed with **zero** cells installed; UI progress text remained pre-install.
- Screenrecord has **no audio**, so narration replay cannot be confirmed from the video file alone.

## Non-reader (factual)

- `31_non_reader_sim.png` uses debug pref `NonReaderSimulation` (`familytv_debug`).
- It reduces text alpha in Adventure UI; it is **QA simulation**, not Household Parent setting.

## World consequence (factual)

- Before: `50_world_before_success.png` (and intro `03`/`04`).
- After: `51_world_after_success.png` / `14`–`16` / `70`.
- Next continuity: `70` → `17` → `71`/`18` (nav maze; ship still appears in mid-stage chrome).

## Logs / performance

| File | Content |
|------|---------|
| `logs/meminfo_home.txt` | dumpsys meminfo at cold Home |
| `logs/meminfo_space_start.txt` | intro |
| `logs/meminfo_space_mid.txt` | after first install |
| `logs/meminfo_space_end.txt` | after next scene |
| `logs/space_launch_logcat.txt` | filtered logcat for capture window |
| `logs/capture_notes.txt` | machine capture trace |
| `logs/capture_run.log` | stdout of capture script |

**Log scan (factual):** no `FATAL EXCEPTION`; no `ANR in ru.familytv` in filtered log.

## Capture limitations

1. `adb screenrecord` = **no internal audio**.
2. Input = adb tap/keyevent, not physical Bluetooth remote hardware path.
3. `01_home_space` may show scrolled Home shelf; Space was still entered via Details+Start.
4. Several success shots (`14`–`16`, `51`, `70`) are the **same success frame** captured sequentially without waiting for distinct animation frames.
5. Product was **not** modified during this review capture.

## Settings snapshot

See `DEVICE_STATE.md`.
