#!/usr/bin/env python3
"""Honest Space Launch review-pack capture on H728. No product changes."""
from __future__ import annotations

import re
import subprocess
import time
from pathlib import Path

DEV = "192.168.68.109:5555"
ROOT = Path("/home/siovush/AndroidStudioProjects/FamilyTV/docs/review-space-launch-current")
SHOTS = ROOT / "screenshots"
RECS = ROOT / "recordings"
LOGS = ROOT / "logs"
for p in (SHOTS, RECS, LOGS):
    p.mkdir(parents=True, exist_ok=True)

NOTES: list[str] = []


def adb(*a: str) -> subprocess.CompletedProcess:
    return subprocess.run(["adb", "-s", DEV, *a], capture_output=True, text=True)


def adb_ok(*a: str) -> str:
    r = adb(*a)
    return (r.stdout or "") + (r.stderr or "")


def dump() -> str:
    adb("shell", "uiautomator", "dump", "/sdcard/uidump.xml")
    adb("pull", "/sdcard/uidump.xml", "/tmp/uid_review.xml")
    return Path("/tmp/uid_review.xml").read_text(encoding="utf-8")


def shot(name: str, note: str = ""):
    adb("shell", "screencap", "-p", "/sdcard/s.png")
    dest = SHOTS / name
    adb("pull", "/sdcard/s.png", str(dest))
    size = dest.stat().st_size if dest.exists() else 0
    line = f"SHOT {name} bytes={size} {note}"
    print(line)
    NOTES.append(line)


def meminfo(name: str):
    out = adb_ok("shell", "dumpsys", "meminfo", "ru.familytv")
    (LOGS / name).write_text(out, encoding="utf-8")
    for line in out.splitlines():
        if "TOTAL PSS" in line:
            print("MEM", name, line.strip())
            NOTES.append(f"MEM {name}: {line.strip()}")
            break


def tap(x: int, y: int):
    print(f"TAP {x} {y}")
    NOTES.append(f"TAP {x} {y}")
    adb("shell", "input", "tap", str(x), str(y))


def key(*codes: int):
    for c in codes:
        adb("shell", "input", "keyevent", str(c))
        time.sleep(0.25)


def tap_btn(label: str) -> bool:
    xml = dump()
    patterns = [
        rf'class="android\.widget\.(?:Image)?Button"[^>]*?(?:text|content-desc)="{re.escape(label)}"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
        rf'(?:text|content-desc)="{re.escape(label)}"[^>]*?class="android\.widget\.(?:Image)?Button"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
        rf'class="android\.widget\.ImageButton"[^>]*?content-desc="{re.escape(label)}"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
        rf'content-desc="{re.escape(label)}"[^>]*?class="android\.widget\.ImageButton"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
    ]
    for pat in patterns:
        m = re.search(pat, xml)
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            tap((x1 + x2) // 2, (y1 + y2) // 2)
            NOTES.append(f"BTN {label} bounds={[x1,y1,x2,y2]}")
            return True
    print("MISS", label)
    NOTES.append(f"MISS {label}")
    return False


def nodes(xml: str):
    return list(re.finditer(r"<node [^/]*?/>", xml))


def content_taps(label: str, max_w: int = 220):
    xml = dump()
    pts = []
    for m in nodes(xml):
        n = m.group(0)
        if f'content-desc="{label}"' not in n:
            continue
        if 'clickable="true"' not in n:
            continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if not b:
            continue
        x1, y1, x2, y2 = map(int, b.groups())
        w, h = x2 - x1, y2 - y1
        if h < 70:
            continue
        if w > max_w:
            # Known H728 a11y inflation for first energy tile — tap left cell center.
            pts.append((x1 + 88, (y1 + y2) // 2, w, "fat"))
        else:
            pts.append(((x1 + x2) // 2, (y1 + y2) // 2, w, "ok"))
    uniq = []
    for p in sorted(pts, key=lambda t: t[0]):
        if not uniq or abs(uniq[-1][0] - p[0]) > 40:
            uniq.append(p)
    return uniq


def focus() -> str:
    out = adb_ok("shell", "dumpsys", "window")
    for line in out.splitlines():
        if "mCurrentFocus" in line:
            NOTES.append(line.strip())
            print(line.strip())
            return line
    return ""


def clear_progress():
    adb("shell", "run-as", "ru.familytv", "rm", "-f", "shared_prefs/familytv_adventure_progress.xml")
    NOTES.append("CLEARED familytv_adventure_progress.xml")


def set_non_reader(on: bool):
    # Write debug pref via run-as
    val = "true" if on else "false"
    script = f"""
cd /data/data/ru.familytv/shared_prefs
cat > familytv_debug.xml <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="non_reader_simulation" value="{val}" />
</map>
EOF
"""
    adb("shell", "run-as", "ru.familytv", "sh", "-c", script)
    NOTES.append(f"SET non_reader_simulation={val} (DEBUG_ONLY pref)")


def main():
    # Clear logcat for this capture window
    adb("logcat", "-c")
    clear_progress()
    set_non_reader(False)

    # Cold start
    adb("shell", "am", "force-stop", "ru.familytv")
    time.sleep(1.5)
    adb("shell", "am", "start", "-n", "ru.familytv/.home.HomeActivity")
    time.sleep(3)
    focus()
    meminfo("meminfo_home.txt")
    shot("01_home_space.png", "REAL cold Home after force-stop; Space may be visible in shelf/hero")

    # Open Space via catalog Details (normal child path; not seeded scene)
    adb(
        "shell",
        "am",
        "start",
        "-n",
        "ru.familytv/.home.ContentDetailsActivity",
        "-e",
        "contentId",
        "adv-space-expedition",
    )
    time.sleep(2)
    shot("01b_details_space.png", "REAL ContentDetails for adv-space-expedition before Start")

    # Start continuous recording BEFORE Start
    adb("shell", "pkill", "screenrecord")
    time.sleep(0.5)
    adb("shell", "rm", "-f", "/sdcard/ftv_space_review.mp4")
    rec = subprocess.Popen(
        ["adb", "-s", DEV, "shell", "screenrecord", "--time-limit", "180", "/sdcard/ftv_space_review.mp4"]
    )
    NOTES.append("screenrecord started /sdcard/ftv_space_review.mp4 time-limit=180 (no internal audio expected)")
    time.sleep(1.2)

    assert tap_btn("Начать"), "Start failed"
    time.sleep(3.5)
    focus()
    meminfo("meminfo_space_start.txt")
    shot("02_space_intro.png", "REAL intro STORY after Start; Lumi+ship empty slots")
    shot("03_ship_problem.png", "REAL same intro frame — ship problem state")
    shot("04_ship_problem_close_state.png", "REAL intro — empty slots / dead engine cue")

    assert tap_btn("Дальше"), "intro advance failed"
    time.sleep(2.8)
    shot("05_objects_initial.png", "REAL launch_repair challenge — objects tray + ship")
    shot("60_help_0.png", "REAL help level 0 — before Help press")

    # Focus states via DPAD among objects (remote simulation)
    # Try move focus with DPAD; capture whatever is focused
    key(20)  # DPAD_DOWN toward objects if needed
    time.sleep(0.4)
    key(21)  # LEFT
    time.sleep(0.3)
    shot("20_focus_object_1.png", "REAL after DPAD; focus may be on an object tile")
    key(22)  # RIGHT
    time.sleep(0.3)
    shot("21_focus_object_2.png", "REAL after DPAD RIGHT")
    # Move toward distractor (rock often mid-left)
    rocks = content_taps("Камень")
    if rocks:
        # Focus approach: tap near rock without OK? Use DPAD to rock is hard; tap to select focus then shot before OK
        # For wrong: capture before and after tap
        shot("06_wrong_choice_before.png", "REAL before intentional wrong (Камень)")
        shot("22_focus_wrong_object.png", "REAL focus/approach wrong object Камень")
        tap(rocks[0][0], rocks[0][1])
        time.sleep(2.0)
        shot("07_wrong_choice_reaction.png", "REAL after soft-reject wrong object")
    else:
        NOTES.append("WARN no Камень tile found")

    # HELP level 1
    shot("23_focus_repeat.png", "REAL chrome before Help/Repeat — may show icon buttons")
    assert tap_btn("Помоги"), "Help failed"
    time.sleep(1.8)
    shot("08_help_level1.png", "REAL after Help #1 (scaffold HINT1 / problem pulse)")
    shot("09_help_visual_highlight.png", "REAL Help visual — slot highlight if present")
    shot("61_help_1.png", "REAL help level 1 state")
    # Dismiss help feedback if Дальше shown
    if 'text="Дальше"' in dump():
        tap_btn("Дальше")
        time.sleep(1.2)

    # HELP level 2
    if tap_btn("Помоги"):
        time.sleep(1.8)
        shot("62_help_2.png", "REAL after Help #2 (HINT2_VISUAL / energy highlight if implemented)")
        if 'text="Дальше"' in dump():
            tap_btn("Дальше")
            time.sleep(1.0)
    else:
        NOTES.append("WARN Help #2 button miss")

    shot("24_focus_help.png", "REAL Help icon affordance region after help cycle")

    # REPEAT — capture before/after with progress empty still
    shot("10a_before_repeat.png", "REAL before Repeat")
    assert tap_btn("Повтори"), "Repeat failed"
    time.sleep(1.5)
    shot("10_repeat_state.png", "REAL after Repeat — state should be preserved (no reset)")
    shot("50_world_before_success.png", "REAL world before success — empty/partial slots prior to completing repair")

    # Install energy cells
    energies = content_taps("Энергия")
    NOTES.append(f"energy taps initial={energies}")
    assert energies, "no energy"
    shot("11_first_cell_selected.png", "REAL about to install first energy (focus/tap target)")
    tap(energies[0][0], energies[0][1])
    time.sleep(2.2)
    shot("12_first_cell_installed.png", "REAL after first energy install")
    meminfo("meminfo_space_mid.txt")

    energies = content_taps("Энергия")
    NOTES.append(f"energy taps after1={energies}")
    assert energies, "no energy after first"
    tap(energies[0][0], energies[0][1])
    time.sleep(2.2)
    shot("13_second_cell_installed.png", "REAL after second energy install")

    energies = content_taps("Энергия")
    NOTES.append(f"energy taps after2={energies}")
    if energies:
        tap(energies[0][0], energies[0][1])
        time.sleep(2.5)
    else:
        NOTES.append("WARN third energy tap missing from dump")

    shot("14_all_cells_installed.png", "REAL success / all cells or feedback")
    shot("15_engine_activation.png", "REAL engine activation / glow if visible")
    shot("16_lumi_success_reaction.png", "REAL Lumi success emotion if visible")
    shot("51_world_after_success.png", "REAL post-repair world consequence")
    shot("70_ship_end_of_launch.png", "REAL end of launch repair success")

    # Advance to flight then next scene
    if tap_btn("Дальше"):
        time.sleep(3.2)
    else:
        key(66)
        time.sleep(3.2)
    shot("17_ship_moving.png", "REAL launch_flight story — ship powered / moving cue")
    shot("42_ship_before_after_comparison.png", "REAL flight beat (after); compare with 03/04 for before")

    if tap_btn("Дальше"):
        time.sleep(3.0)
    else:
        key(66)
        time.sleep(3.0)
    shot("18_next_scene.png", "REAL next scene (nav_maze) after flight")
    shot("71_next_scene_start.png", "REAL next scene start — continuity check")
    meminfo("meminfo_space_end.txt")

    # Stop recording
    time.sleep(1.5)
    adb("shell", "pkill", "-INT", "screenrecord")
    time.sleep(2.5)
    adb("pull", "/sdcard/ftv_space_review.mp4", str(RECS / "space_launch_full_review.mp4"))
    v = RECS / "space_launch_full_review.mp4"
    NOTES.append(f"REC space_launch_full_review.mp4 bytes={v.stat().st_size if v.exists() else 0}")

    # Object clarity (still on next scene or restart challenge briefly — restart for clear object shots)
    # DEBUG_ONLY second pass: reopen challenge without seeding by clearing and advancing quickly for close shots
    clear_progress()
    adb("shell", "am", "force-stop", "ru.familytv")
    time.sleep(1)
    adb(
        "shell",
        "am",
        "start",
        "-n",
        "ru.familytv/.adventure.AdventureActivity",
        "-e",
        "contentId",
        "adv-space-expedition",
    )
    time.sleep(3)
    tap_btn("Дальше")
    time.sleep(2.5)
    shot("40_energy_objects.png", "REAL (second open, no seed) object tray for clarity")
    shot("41_ship_slots.png", "REAL (second open) empty slots close")

    # Non-reader pair — DEBUG_ONLY pref
    shot("30_normal_text.png", "REAL (second open) normal text alpha state")
    set_non_reader(True)
    # recreate activity to apply pref
    adb("shell", "am", "force-stop", "ru.familytv")
    time.sleep(1)
    clear_progress()
    adb(
        "shell",
        "am",
        "start",
        "-n",
        "ru.familytv/.adventure.AdventureActivity",
        "-e",
        "contentId",
        "adv-space-expedition",
    )
    time.sleep(3)
    tap_btn("Дальше")
    time.sleep(2.5)
    shot("31_non_reader_sim.png", "DEBUG_ONLY NON_READER_SIM=true on same challenge stage")
    set_non_reader(False)

    # Copy world before from earlier if we overwrote — restore logical names:
    # 50 should preferably be pre-success; we already shot 05 as objects_initial. Copy 05→50 if 50 was post.
    # Leave as-captured; README will map.

    # Logcat
    log = adb_ok("logcat", "-d", "-v", "time")
    # Keep app-focused subset
    lines = [
        ln
        for ln in log.splitlines()
        if "ru.familytv" in ln or "AndroidRuntime" in ln or "ActivityManager" in ln or "FamilyTV" in ln or "Adventure" in ln
    ]
    (LOGS / "space_launch_logcat.txt").write_text("\n".join(lines[-5000:]), encoding="utf-8")
    crash = any("FATAL EXCEPTION" in ln for ln in lines)
    anr = any("ANR in ru.familytv" in ln for ln in lines)
    NOTES.append(f"LOG crash={crash} anr={anr} filtered_lines={len(lines)}")

    (LOGS / "capture_notes.txt").write_text("\n".join(NOTES), encoding="utf-8")
    print("CAPTURE DONE")


if __name__ == "__main__":
    main()
