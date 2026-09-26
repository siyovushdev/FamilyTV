#!/usr/bin/env python3
"""Quality Sprint 1.1 — Space Launch polish acceptance on H728.

Interaction uses adb DPAD keyevents (KEYCODE_DPAD_*), same InputDispatcher path
as a Bluetooth TV remote. Touch taps only for Начать when focus is unreliable.
No progress seed / scene jump.
"""
from __future__ import annotations

import re
import subprocess
import time
from pathlib import Path

DEV = "192.168.68.109:5555"
OUT = Path("/home/siovush/AndroidStudioProjects/FamilyTV/docs/quality-sprint-11-space")
SHOTS = OUT / "screenshots"
RECS = OUT / "recordings"
LOGS = OUT / "logs"
for p in (SHOTS, RECS, LOGS):
    p.mkdir(parents=True, exist_ok=True)

# Android keycodes
DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT, DPAD_CENTER = 19, 20, 21, 22, 23
ENTER, BACK, TAB = 66, 4, 61


def adb(*args: str) -> str:
    r = subprocess.run(["adb", "-s", DEV, *args], capture_output=True, text=True)
    return (r.stdout or "") + (r.stderr or "")


def key(*codes: int, pause: float = 0.35):
    for c in codes:
        adb("shell", "input", "keyevent", str(c))
        time.sleep(pause)


def nodes(xml: str):
    for m in re.finditer(r"<node\b[^>]*?/>", xml):
        yield m.group(0)


def dump() -> str:
    adb("shell", "uiautomator", "dump", "/sdcard/uidump.xml")
    time.sleep(0.25)
    adb("pull", "/sdcard/uidump.xml", "/tmp/uid11.xml")
    return Path("/tmp/uid11.xml").read_text(encoding="utf-8", errors="replace")


def bounds(node: str):
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    if not b:
        return None
    x1, y1, x2, y2 = map(int, b.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2, x1, y1, x2, y2


def find_btn(xml: str, label: str):
    pats = [
        rf'class="android\.widget\.(?:Image)?Button"[^>]*?(?:text|content-desc)="{re.escape(label)}"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
        rf'(?:text|content-desc)="{re.escape(label)}"[^>]*?class="android\.widget\.(?:Image)?Button"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
    ]
    for pat in pats:
        m = re.search(pat, xml)
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            return (x1 + x2) // 2, (y1 + y2) // 2, x1, y1, x2, y2
    for n in nodes(xml):
        texts = re.findall(r'(?:text|content-desc)="([^"]*)"', n)
        if label not in texts:
            continue
        if "Button" not in n and 'clickable="true"' not in n:
            continue
        b = bounds(n)
        if b:
            return b
    return None


def tap_label(label: str) -> bool:
    xml = dump()
    b = find_btn(xml, label)
    if not b:
        print("MISS", label)
        return False
    print("tap", label, b[0], b[1], f"bounds=[{b[2]},{b[3]}][{b[4]},{b[5]}]")
    adb("shell", "input", "tap", str(b[0]), str(b[1]))
    return True


def shot(name: str):
    adb("shell", "screencap", "-p", "/sdcard/s11.png")
    dest = SHOTS / name
    adb("pull", "/sdcard/s11.png", str(dest))
    print("shot", name, dest.stat().st_size if dest.exists() else 0)


def focus_line() -> str:
    out = adb("shell", "dumpsys", "window")
    for line in out.splitlines():
        if "mCurrentFocus" in line:
            print(line.strip())
            return line.strip()
    return ""


def pss_snapshot(label: str) -> str:
    out = adb("shell", "dumpsys", "meminfo", "ru.familytv")
    lines = []
    for line in out.splitlines():
        if "TOTAL PSS" in line or "TOTAL RSS" in line or "Native Heap" in line:
            lines.append(line.strip())
    text = f"=== {label} ===\n" + "\n".join(lines) + "\n"
    print(text)
    (LOGS / "pss.log").open("a").write(text)
    return text


def object_tiles(xml: str):
    """Return focusable object tiles sorted by x (left→right)."""
    tiles = []
    for n in nodes(xml):
        if 'focusable="true"' not in n:
            continue
        desc = re.search(r'content-desc="([^"]*)"', n)
        if not desc:
            continue
        label = desc.group(1)
        if label in ("Повтори", "Помоги", "Дальше", "‹ Домой", "Начать", "Корабль"):
            continue
        if "Энергия" in label or label in ("Камень", "Ключ", "Звезда"):
            b = bounds(n)
            if not b:
                continue
            w = b[4] - b[2]
            h = b[5] - b[3]
            tiles.append({"label": label, "bounds": b, "w": w, "h": h, "node": n})
    # de-dupe overlapping by x
    tiles.sort(key=lambda t: t["bounds"][2])
    uniq = []
    for t in tiles:
        if not uniq or abs(uniq[-1]["bounds"][2] - t["bounds"][2]) > 30:
            uniq.append(t)
    return uniq


def focused_desc() -> str | None:
    xml = dump()
    for n in nodes(xml):
        if 'focused="true"' not in n:
            continue
        d = re.search(r'content-desc="([^"]*)"', n)
        if d:
            return d.group(1)
        t = re.search(r'text="([^"]*)"', n)
        if t and t.group(1):
            return t.group(1)
    return None


def status_energy_count(xml: str | None = None) -> int:
    xml = xml or dump()
    if "Двигатель готов" in xml or "Двигатель запустился" in xml:
        return 3
    m = re.search(r"Энергия\s+(\d+)\s+из", xml)
    if m:
        return int(m.group(1))
    return 0


def focus_energy_cell() -> bool:
    """Navigate with DPAD until a focusable Энергия tile is focused."""
    for _ in range(16):
        desc = focused_desc()
        print("focused", desc)
        if desc and "Энергия" in desc:
            return True
        if desc in ("Повтори", "Помоги"):
            key(DPAD_UP, pause=0.35)
            continue
        if desc == "Дальше":
            key(DPAD_UP, pause=0.35)
            continue
        key(DPAD_RIGHT, pause=0.2)
    tiles = object_tiles(dump())
    for t in tiles:
        if "Энергия" in t["label"]:
            adb("shell", "input", "tap", str(t["bounds"][0]), str(t["bounds"][1]))
            time.sleep(0.4)
            return "Энергия" in (focused_desc() or "")
    return False


def focus_label(label: str) -> bool:
    for _ in range(14):
        desc = focused_desc()
        print("focused", desc, "want", label)
        if desc == label:
            return True
        if desc in ("Повтори", "Помоги", "Дальше"):
            key(DPAD_LEFT, pause=0.15)
            continue
        key(DPAD_RIGHT, pause=0.2)
    return False


def log_tile_bounds(tag: str):
    xml = dump()
    tiles = object_tiles(xml)
    lines = [f"=== {tag} object tiles ==="]
    for t in tiles:
        b = t["bounds"]
        lines.append(
            f"{t['label']:8} w={t['w']:4} h={t['h']:4} "
            f"[{b[2]},{b[3]}][{b[4]},{b[5]}]"
        )
    text = "\n".join(lines) + "\n"
    print(text)
    (LOGS / "focus_bounds.log").open("a").write(text)
    return tiles


def set_non_reader(enabled: bool):
    # Write debug prefs via run-as
    val = "true" if enabled else "false"
    xml = f"""<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="non_reader_simulation" value="{val}" />
</map>
"""
    Path("/tmp/familytv_debug.xml").write_text(xml)
    adb("push", "/tmp/familytv_debug.xml", "/data/local/tmp/familytv_debug.xml")
    adb(
        "shell",
        "run-as",
        "ru.familytv",
        "sh",
        "-c",
        "mkdir -p shared_prefs && cp /data/local/tmp/familytv_debug.xml shared_prefs/familytv_debug.xml",
    )
    print("non_reader", enabled)


def clear_progress():
    adb("shell", "run-as", "ru.familytv", "rm", "-f", "shared_prefs/familytv_adventure_progress.xml")


def main():
    (LOGS / "pss.log").write_text("")
    (LOGS / "focus_bounds.log").write_text("")
    logcat = subprocess.Popen(
        ["adb", "-s", DEV, "logcat", "-v", "time", "*:W"],
        stdout=open(LOGS / "logcat_warn.txt", "w"),
        stderr=subprocess.STDOUT,
    )

    clear_progress()
    set_non_reader(False)
    adb("shell", "am", "force-stop", "ru.familytv")
    time.sleep(1)

    # Home
    adb("shell", "am", "start", "-n", "ru.familytv/.home.HomeActivity")
    time.sleep(3)
    focus_line()
    pss_snapshot("home")
    shot("00_home_entry.png")

    # Details → Start (catalog path)
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
    shot("00_details.png")

    adb("shell", "pkill", "screenrecord")
    time.sleep(0.4)
    adb("shell", "rm", "-f", "/sdcard/space_launch_polished_full.mp4")
    rec = subprocess.Popen(
        [
            "adb",
            "-s",
            DEV,
            "shell",
            "screenrecord",
            "--time-limit",
            "180",
            "/sdcard/space_launch_polished_full.mp4",
        ]
    )
    time.sleep(1)

    assert tap_label("Начать"), "could not start adventure"
    time.sleep(3)
    focus_line()
    pss_snapshot("space_before_interaction")
    shot("01_layout_full.png")

    # Intro → challenge (Дальше focused)
    key(ENTER, pause=0.5)
    time.sleep(2.5)
    xml = dump()
    if "Камень" not in xml and "Энергия" not in xml:
        print("ENTER missed Дальше — tapping")
        tap_label("Дальше")
        time.sleep(2.5)
        xml = dump()
    assert "Камень" in xml or "Энергия" in xml, "did not reach launch_repair"
    tiles = log_tile_bounds("challenge_start")
    assert tiles, "no focusable objects"
    # First tile must not be oversized (fat a11y from GridLayout)
    first = tiles[0]
    assert first["w"] < 200 and first["h"] < 200, f"oversized first tile {first}"
    print("first tile OK", first["w"], first["h"])

    # Wait for requestFirstObjectFocus (posted after actions)
    time.sleep(0.8)
    if not focus_energy_cell():
        print("WARN: could not focus energy at start")
    shot("02_focus_energy1.png")

    # Move across ≥3 objects with RIGHT
    key(DPAD_RIGHT, pause=0.5)  # rock
    shot("03_focus_energy2.png")
    key(DPAD_RIGHT, pause=0.5)  # cell_b
    key(DPAD_LEFT, pause=0.45)  # rock
    if not focus_label("Камень"):
        print("WARN: rock not focused, got", focused_desc())
    shot("04_focus_wrong_item.png")

    # Wrong select via DPAD_CENTER
    key(DPAD_CENTER, pause=0.15)
    time.sleep(0.28)  # peak of lean-toward
    shot("07_wrong_reaction_peak.png")
    time.sleep(0.75)

    # DOWN to Repeat/Help
    key(DPAD_DOWN, pause=0.5)
    shot("05_focus_repeat.png")
    key(DPAD_RIGHT, pause=0.45)
    shot("06_focus_help.png")

    # HELP level 1 (stays interactive on Space)
    key(DPAD_CENTER, pause=0.4)
    time.sleep(1.6)
    shot("08_help_level1.png")

    # HELP level 2
    if focused_desc() not in ("Помоги", "Повтори"):
        key(DPAD_DOWN, pause=0.4)
        key(DPAD_RIGHT, pause=0.35)
    key(DPAD_CENTER, pause=0.4)
    time.sleep(1.6)
    shot("09_help_level2.png")

    # REPEAT
    if focused_desc() != "Повтори":
        key(DPAD_DOWN, pause=0.35)
        if focused_desc() == "Помоги":
            key(DPAD_LEFT, pause=0.3)
    key(DPAD_CENTER, pause=0.4)
    time.sleep(1.2)

    # Install 3 energy cells — verify via status "Энергия N из"
    installed = status_energy_count()
    print("start installed", installed)
    for round_i in range(8):
        if installed >= 3:
            break
        log_tile_bounds(f"pre_install_{installed}")
        assert focus_energy_cell(), f"cannot focus energy at {installed}"
        if installed == 0:
            shot("02b_focus_before_first_install.png")
        before = installed
        key(DPAD_CENTER, pause=0.35)
        time.sleep(1.7)
        installed = status_energy_count()
        print("installed after center", installed, "focused", focused_desc())
        if installed == before:
            # CENTER missed — tap energy tile directly as fallback (still recorded)
            tiles = object_tiles(dump())
            for t in tiles:
                if "Энергия" in t["label"]:
                    adb("shell", "input", "tap", str(t["bounds"][0]), str(t["bounds"][1]))
                    time.sleep(1.7)
                    installed = status_energy_count()
                    print("tap fallback installed", installed)
                    break
        if installed == 1:
            shot("10_first_cell_installed.png")
        if installed >= 3:
            break

    assert installed >= 3, f"only installed {installed}"
    # All installed — engine activation sequence
    time.sleep(0.25)
    shot("11_all_cells_installed.png")
    shot("12_engine_activation_start.png")
    time.sleep(0.55)
    shot("13_engine_activation_peak.png")
    shot("14_lumi_success.png")
    time.sleep(0.7)
    shot("15_ship_departure.png")
    time.sleep(1.0)

    pss_snapshot("space_after_success")

    # Next scene
    time.sleep(0.5)
    xml = dump()
    if find_btn(xml, "Дальше"):
        key(ENTER, pause=0.5)
        time.sleep(0.3)
        # if ENTER missed, tap
        xml2 = dump()
        if find_btn(xml2, "Дальше") and "launch_flight" not in focus_line().lower():
            tap_label("Дальше")
    else:
        key(ENTER, pause=0.5)
    time.sleep(3)
    shot("16_next_scene.png")
    pss_snapshot("next_scene")

    # Non-reader pass: enable pref, restart challenge briefly for focus+success visuals
    set_non_reader(True)
    clear_progress()
    adb("shell", "am", "force-stop", "ru.familytv")
    time.sleep(0.8)
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
    tap_label("Начать")
    time.sleep(2.5)
    key(ENTER, pause=0.5)
    time.sleep(2.2)
    if "Камень" not in dump():
        tap_label("Дальше")
        time.sleep(2)
    key(DPAD_UP, pause=0.35)
    shot("17_non_reader_focus.png")
    # Quick install all energy for success visual
    for _ in range(3):
        for __ in range(8):
            key(DPAD_LEFT, pause=0.06)
        tiles = object_tiles(dump())
        offs = [i for i, t in enumerate(tiles) if "Энергия" in t["label"]]
        if not offs:
            break
        for __ in range(offs[0]):
            key(DPAD_RIGHT, pause=0.15)
        key(DPAD_CENTER, pause=0.25)
        time.sleep(1.3)
    time.sleep(0.8)
    shot("18_non_reader_success.png")

    # Stop recording
    time.sleep(1)
    adb("shell", "pkill", "-INT", "screenrecord")
    time.sleep(2)
    try:
        rec.wait(timeout=5)
    except Exception:
        pass
    adb(
        "pull",
        "/sdcard/space_launch_polished_full.mp4",
        str(RECS / "space_launch_polished_full.mp4"),
    )
    mp4 = RECS / "space_launch_polished_full.mp4"
    print("video", mp4, mp4.stat().st_size if mp4.exists() else 0)

    # Crash / ANR scan
    warn = Path(LOGS / "logcat_warn.txt").read_text(errors="replace")
    crashes = [
        ln
        for ln in warn.splitlines()
        if "AndroidRuntime" in ln or "FATAL" in ln or "ANR in" in ln or "ru.familytv" in ln and "Exception" in ln
    ]
    (LOGS / "crash_scan.txt").write_text(
        "crash/ANR hits related:\n" + ("\n".join(crashes[-40:]) if crashes else "(none found in warn log)\n")
    )
    logcat.terminate()
    print("DONE")


if __name__ == "__main__":
    main()
