# Quality Sprint 1.1 — Space Launch Interaction Polish

Device: H728 ADT-3 (`192.168.68.109:5555`), 1920×1080  
Build: debug APK installed via `./gradlew :app:installDebug`  
Date: 2026-09-21  

Interaction input for acceptance: `adb shell input keyevent` DPAD_* / CENTER (same `KeyEvent` path as a Bluetooth TV remote). Touch `input tap` used only for «Начать» / «Дальше» when ENTER missed focus. Agent cannot press a physical handset; see Known limitations.

`adb screenrecord` has **no internal audio** (expected).

---

## What changed (code)

Files:
- `app/src/main/java/ru/familytv/ui/SpaceLaunchBoard.kt`
- `app/src/main/java/ru/familytv/adventure/AdventureActivity.kt`

1. **Layout / safe area (Space only)**  
   - Dropped ScrollView for Space adventures; fixed vertical layout.  
   - Compact header (Lumi 72dp), shorter guide/prompt (max 2 lines), no mic on launch challenge.  
   - Bottom padding 56dp overscan reserve; actions band kept on-screen.  
   - Measured white control pixels end ~y=959 → ~120px margin on 1080p.

2. **D-pad object focus**  
   - Object row: fixed 80dp shells (160×160px bounds; first tile no longer oversized).  
   - Focus: teal 6dp stroke + scale 1.18 + elevation (distinct from Help yellow).  
   - Explicit LEFT/RIGHT wrap; DOWN → Repeat/Help; UP trapped on object row (no escape to «Домой»).

3. **Wrong-action visual**  
   - Lean toward slots (~280ms) + soft slot flash + return (~320ms); supporting text kept.

4. **Engine activation + Lumi**  
   - Sequenced ~1.6–2s: slot pulse → energy flow → engine glow → ship vibrate → lift.  
   - Lumi `CELEBRATING` / `SURPRISED` + `CELEBRATE` motion during success; «Дальше» focus delayed ~1.7s.

5. **Help (Space)**  
   - Help applies scaffold then returns to `CHALLENGE_ACTIVE` (no Дальше gate) so visuals stay interactive.

---

## BEFORE / AFTER (factual)

| Topic | BEFORE | AFTER |
|-------|--------|-------|
| Bottom controls | Repeat/Help near/clipped at 1080 bottom | Controls fully in frame; ~120px bottom margin measured |
| Object hit/focus bounds | First energy a11y/hit inflated | Each object 160×160px fixed shell |
| Object focus | Weak / easy to miss at distance | Teal stroke + scale + elevation on focused tile |
| Wrong feedback | Mostly text | Lean-to-slot + slot reject flash + return (~0.6s) |
| Engine success | Mostly static powered state | Slot/engine/ship motion sequence ~1.5–2s + Lumi celebrate |
| Next scene | Same mission continuity | Preserved (PATH / even-number course) |

---

## Physical remote / DPAD result

Verified via DPAD keyevents on device:

- LEFT/RIGHT across object row (6 tiles, 160×160)  
- CENTER select (wrong rock → soft reject; energy → install)  
- DOWN to Repeat / Help; UP returns to objects (UP no longer opens Home)  
- BACK not required for happy path  
- No focus trap observed after UP trap fix  
- First object bounds normal (160×160)

---

## PSS (dumpsys meminfo)

| Moment | TOTAL PSS |
|--------|-----------|
| Home | 115105 kB (~112 MB) |
| Space before interaction | 123727 kB (~121 MB) |
| Space after success | 150446 kB (~147 MB) |
| Next scene | 142735 kB (~139 MB) |

Prior Space interaction peak cited ~166 MB; this run stayed below that at success.

---

## Crashes / ANR

No `FATAL EXCEPTION` / `ANR in ru.familytv` in warn logcat for the acceptance window.  
Only `WindowManager` `dispatchAppVisibility` warnings on activity exit (common; not a crash).

---

## Evidence paths

- Report: `docs/quality-sprint-11-space/REPORT.md`  
- Screenshots: `docs/quality-sprint-11-space/screenshots/` (`01`…`18` + entry shots)  
- Recording: `docs/quality-sprint-11-space/recordings/space_launch_polished_full.mp4` (~180s, silent)  
- Logs: `docs/quality-sprint-11-space/logs/` (`accept_run.log`, `pss.log`, `focus_bounds.log`, `crash_scan.txt`)  
- Driver: `docs/quality-sprint-11-space/run_acceptance.py`  
- ZIP: `/tmp/familytv-space-sprint11-review.zip`

---

## Recording coverage checklist

Home → Details → Start Space (no progress seed / no scene jump):

1. Intro — yes  
2. Engine problem — yes  
3. DPAD focus movement — yes  
4. Focus across ≥3 objects — yes  
5. Intentional wrong object — yes (rock)  
6. Wrong reaction — yes (lean + text)  
7–9. Help / visual help / Repeat — yes  
10–12. Correct installs — yes (1→2→3)  
13–15. Engine activation / Lumi / departure beat — yes (end-state frames; motion in video)  
16. Next scene — yes (even-path course, ship powered)

---

## Known limitations

- Interaction driven by **adb DPAD keyevents**, not a handset in an agent’s hand. Same KeyEvent path as TV remotes; physical handset not pressed by the agent.  
- `screenrecord` is **silent**.  
- Still frames of engine peak can look similar once the ~2s sequence has finished; motion is in the MP4.  
- On success FEEDBACK, prompt text from the challenge can still show under the success guide line.  
- `18_non_reader_success.png` may reuse the verified all-cells success frame when a live non-reader recapture hit the device cast idle screen; non-reader text dimming is shown in `17_non_reader_focus.png`.  
- Forest/Weather/Story untouched (Space-only scope).
