#!/usr/bin/env python3
"""H728 acceptance driver for Space launch vertical slice — no seeded progress."""
from __future__ import annotations

import re
import subprocess
import time
from pathlib import Path

DEV = "192.168.68.109:5555"
OUT = Path("/home/siovush/AndroidStudioProjects/FamilyTV/docs/quality-sprint-1-space")
SHOTS = OUT / "screenshots"
RECS = OUT / "recordings"
SHOTS.mkdir(parents=True, exist_ok=True)
RECS.mkdir(parents=True, exist_ok=True)


def adb(*args: str) -> str:
    r = subprocess.run(["adb", "-s", DEV, *args], capture_output=True, text=True)
    if r.returncode != 0 and args[:1] != ("shell",):
        print("adb fail", args, r.stderr[:200])
    return (r.stdout or "") + (r.stderr or "")


def dump() -> str:
    adb("shell", "uiautomator", "dump", "/sdcard/uidump.xml")
    adb("pull", "/sdcard/uidump.xml", "/tmp/uid.xml")
    return Path("/tmp/uid.xml").read_text(encoding="utf-8")


def nodes(xml: str):
    for m in re.finditer(r"<node [^/]*?/>", xml):
        yield m.group(0)


def bounds(node: str):
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    if not b:
        return None
    x1, y1, x2, y2 = map(int, b.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2, x1, y1, x2, y2


def find_tap(xml: str, label: str, prefer_clickable: bool = True):
    # Prefer android.widget.Button / ImageButton regardless of attr order
    patterns = [
        rf'class="android\.widget\.(?:Image)?Button"[^>]*?(?:text|content-desc)="{re.escape(label)}"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
        rf'(?:text|content-desc)="{re.escape(label)}"[^>]*?class="android\.widget\.(?:Image)?Button"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
        rf'class="android\.widget\.(?:Image)?Button"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*?(?:text|content-desc)="{re.escape(label)}"',
    ]
    for pat in patterns:
        m = re.search(pat, xml)
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            print("pick-btn", label, x1, y1, x2, y2)
            return (x1 + x2) // 2, (y1 + y2) // 2
    # focusable / clickable LinearLayout tiles (space objects)
    for n in nodes(xml):
        texts = re.findall(r'(?:text|content-desc)="([^"]*)"', n)
        if label not in texts:
            continue
        if 'clickable="true"' not in n and 'focusable="true"' not in n:
            continue
        b = bounds(n)
        if not b:
            continue
        print("pick-tile", label, b[0], b[1], texts)
        return b[0], b[1]
    # last resort: any node with label, smallest width
    cands = []
    for n in nodes(xml):
        texts = re.findall(r'(?:text|content-desc)="([^"]*)"', n)
        if label not in texts:
            continue
        b = bounds(n)
        if not b:
            continue
        cands.append((b[4] - b[2], b[0], b[1]))
    if not cands:
        return None
    cands.sort()
    print("pick-fallback", label, cands[0])
    return cands[0][1], cands[0][2]


def tap_xy(x: int, y: int):
    print(f"tap {x} {y}")
    adb("shell", "input", "tap", str(x), str(y))


def tap_label(label: str) -> bool:
    xml = dump()
    pt = find_tap(xml, label)
    if not pt:
        print("MISS", label)
        return False
    tap_xy(*pt)
    return True


def shot(name: str):
    adb("shell", "screencap", "-p", "/sdcard/s.png")
    adb("pull", "/sdcard/s.png", str(SHOTS / name))
    print("shot", name, (SHOTS / name).stat().st_size)


def focus():
    out = adb("shell", "dumpsys", "window")
    for line in out.splitlines():
        if "mCurrentFocus" in line:
            print(line.strip())
            return line


def main():
    adb("shell", "run-as", "ru.familytv", "rm", "-f", "shared_prefs/familytv_adventure_progress.xml")
    adb("shell", "am", "force-stop", "ru.familytv")
    time.sleep(1)
    adb("shell", "am", "start", "-n", "ru.familytv/.home.HomeActivity")
    time.sleep(2)
    shot("00_home.png")
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
    shot("01_details.png")

    adb("shell", "pkill", "screenrecord")
    time.sleep(0.5)
    adb("shell", "rm", "-f", "/sdcard/ftv_space_slice.mp4")
    rec = subprocess.Popen(
        ["adb", "-s", DEV, "shell", "screenrecord", "--time-limit", "140", "/sdcard/ftv_space_slice.mp4"]
    )
    time.sleep(1)

    assert tap_label("Начать")
    time.sleep(3)
    focus()
    shot("before_problem.png")

    # Intro → challenge via ENTER (Дальше has requestFocus)
    adb("shell", "input", "keyevent", "66")
    time.sleep(3)
    focus()
    xml = dump()
    if "Энергия" not in xml and "Камень" not in xml:
        pt = find_tap(xml, "Дальше")
        if pt:
            tap_xy(*pt)
            time.sleep(3)
            xml = dump()
    shot("02_challenge.png")
    assert "Камень" in xml or "Энергия" in dump(), "did not reach launch_repair"

    # Wrong
    assert tap_label("Камень")
    time.sleep(2)
    shot("wrong_action.png")

    # Help
    assert tap_label("Помоги")
    time.sleep(2)
    xml = dump()
    if find_tap(xml, "Дальше"):
        tap_label("Дальше")
        time.sleep(1.2)
    shot("help_visual.png")

    # Repeat
    assert tap_label("Повтори")
    time.sleep(2)

    # Install energy cells (unique tile content-desc)
    xml = dump()
    cells = []
    for n in nodes(xml):
        if 'content-desc="Энергия"' in n and 'clickable="true"' in n:
            b = bounds(n)
            if b:
                cells.append((b[0], b[1]))
    # Fallback: any Энергия desc
    if len(cells) < 3:
        cells = []
        for n in nodes(xml):
            if 'content-desc="Энергия"' in n:
                b = bounds(n)
                if b:
                    cells.append((b[0], b[1]))
    # de-dupe by x
    uniq = []
    for x, y in sorted(cells, key=lambda p: p[0]):
        if not uniq or abs(uniq[-1][0] - x) > 40:
            uniq.append((x, y))
    print("cells", uniq)
    assert len(uniq) >= 3, uniq
    for i, (x, y) in enumerate(uniq[:3]):
        tap_xy(x, y)
        time.sleep(1.6)
        if i == 0:
            shot("object_installed.png")

    time.sleep(2)
    shot("ship_repaired.png")

    # Success → flight
    time.sleep(1)
    xml = dump()
    if find_tap(xml, "Дальше"):
        tap_label("Дальше")
    else:
        adb("shell", "input", "keyevent", "66")
    time.sleep(3)
    shot("engine_started.png")

    # Flight → nav
    xml = dump()
    if find_tap(xml, "Дальше"):
        tap_label("Дальше")
    else:
        adb("shell", "input", "keyevent", "66")
    time.sleep(3)
    shot("next_scene.png")

    time.sleep(2)
    adb("shell", "pkill", "-INT", "screenrecord")
    time.sleep(2)
    adb("pull", "/sdcard/ftv_space_slice.mp4", str(RECS / "space_launch_vertical_slice.mp4"))
    mem = adb("shell", "dumpsys", "meminfo", "ru.familytv")
    for line in mem.splitlines():
        if "TOTAL PSS" in line or "TOTAL RSS" in line:
            print(line.strip())
    print("DONE")


if __name__ == "__main__":
    main()
