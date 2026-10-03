#!/usr/bin/env python3
"""H728 smoke for three representative M3 packages.

Easy math: Мало яблок (1+1). Hard math: Много яблок (3+3).
Color: Красное крыло. Each path is wrong → help → correct → success/launch → back.
"""
import argparse
import re
import time
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs/v1/games-content-m3"
ZIPS = [
    ROOT / "dist/count-combine-tiny-v1.zip",
    ROOT / "dist/count-combine-to-five-v1.zip",
    ROOT / "dist/count-combine-many-v1.zip",
    ROOT / "dist/attribute-apply-red-v1.zip",
]
p = argparse.ArgumentParser()
p.add_argument("--serial", default="192.168.68.105:5555")
args = p.parse_args()
ADB = ["adb", "-s", args.serial]
for folder in ("screenshots", "logs"):
    (OUT / folder).mkdir(parents=True, exist_ok=True)


def adb(*parts, binary=False, check=True, timeout=40):
    result = subprocess.run(ADB + list(parts), check=check, capture_output=True, timeout=timeout)
    return result.stdout if binary else result.stdout.decode(errors="replace")


def logs(tag):
    return adb("logcat", "-d", "-v", "threadtime", "-T", epoch, "-s", f"{tag}:I")


def family():
    return logs("FamilyGames")


def latest():
    lines = [line for line in family().splitlines() if "world=" in line]
    assert lines, "No production state log"
    return lines[-1]


def key(code, delay=0.4):
    adb("shell", "input", "keyevent", str(code))
    time.sleep(delay)


def screenshot(name):
    (OUT / "screenshots" / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))


def checkpoint(name, **fields):
    state = latest()
    for field, value in fields.items():
        assert re.search(r"\b" + field + "=" + re.escape(str(value)) + r"(?:\s|$)", state), (name, field, value, state)
    print(name, state, flush=True)
    screenshot(name)
    return state


def home_focus():
    lines = [line for line in logs("FamilyHome").splitlines() if " focus=" in line]
    if not lines:
        return ""
    match = re.search(r"\bfocus=(\S+)", lines[-1])
    return match.group(1) if match else ""


def focus_play():
    for _ in range(16):
        focus = home_focus()
        if focus == "games":
            return
        if focus == "watch":
            key(22, 0.3)
        elif focus in ("explore", "create", "move"):
            key(21, 0.3)
        elif focus in ("voice", "journal", "parent"):
            key(20, 0.35)
        else:
            key(19, 0.35)
    raise SystemExit(f"Could not focus games, last={home_focus()}\n{logs('FamilyHome')}")


def count_until_end(narration, counts):
    start = len(family().splitlines())
    key(23, 0.15)
    deadline = time.monotonic() + 14
    segment = ""
    while time.monotonic() < deadline:
        segment = "\n".join(family().splitlines()[start:])
        if "count_end " in segment:
            break
        time.sleep(0.12)
    else:
        raise SystemExit(("count did not finish", segment))
    assert f"count_narration={narration}" in segment, segment
    assert re.findall(r"\bcount=(\d+)\b", segment) == counts, segment


def walk_math(name, content, wrong, correct, equation, narration, counts):
    key(23, 0.7)
    checkpoint(f"{name}_world", world="MATH", content=content, combined="false", focusKey="world", round="0")
    key(23, 1.6)
    checkpoint(f"{name}_combined", world="MATH", content=content, combined="true", feedback="READY", focusKey="choice:0", equation="?")
    key(23, 0.45)
    checkpoint(f"{name}_wrong", world="MATH", content=content, feedback="WRONG", mathChoice=str(wrong), equation="?")
    key(19, 0.35)
    count_until_end(narration, counts)
    checkpoint(f"{name}_help", world="MATH", content=content, feedback="HELP", focusKey="help", counting="false")
    key(20, 0.4)
    key(22, 0.4)
    key(23, 0.8)
    checkpoint(f"{name}_success", world="MATH", content=content, feedback="CORRECT", equation=str(equation), mathChoice=str(correct), round="0")
    key(4, 0.8)
    checkpoint(f"{name}_back", world="HOME", focusKey="game:0", content="none")


def walk_color(name, content, wrong, correct):
    key(23, 0.7)
    checkpoint(f"{name}_ready", world="COLOR", content=content, feedback="READY", painted="null", focusKey="choice:1", launched="false")
    key(21, 0.4)
    key(23, 1.45)
    checkpoint(f"{name}_wrong", world="COLOR", content=content, feedback="WRONG", painted=wrong, launched="false")
    key(19, 0.35)
    key(23, 0.45)
    checkpoint(f"{name}_help", world="COLOR", content=content, feedback="HELP", painted=wrong, focusKey="choice:1", launched="false")
    key(23, 1.45)
    checkpoint(f"{name}_correct", world="COLOR", content=content, feedback="CORRECT", painted=correct, launched="false", focusKey="next")
    key(23, 0.2)
    deadline = time.monotonic() + 5
    state = ""
    while time.monotonic() < deadline:
        state = latest()
        if re.search(r"\blaunched=true(?:\s|$)", state):
            break
        time.sleep(0.15)
    else:
        raise SystemExit(("launch did not finish", state))
    checkpoint(f"{name}_launched", content=content, feedback="CORRECT", launched="true", painted=correct)
    key(4, 0.8)
    checkpoint(f"{name}_back", world="HOME", focusKey="game:1", content="none")


epoch = adb("shell", "date", "+%s").strip() + ".000"
for zip_path in ZIPS:
    assert zip_path.is_file(), zip_path
import_dir = "/storage/emulated/0/Android/data/ru.familytv/files/FamilyTV/import-packages"
adb("shell", "mkdir", "-p", import_dir)
for name in adb("shell", "ls", "-1", import_dir, check=False).split():
    if name.endswith(".zip"):
        adb("shell", "rm", "-f", f"{import_dir}/{name}")
for zip_path in ZIPS:
    pushed = subprocess.run(ADB + ["push", str(zip_path), f"{import_dir}/{zip_path.name}"], check=False, capture_output=True, text=True)
    print(pushed.stdout or pushed.stderr, flush=True)
listed = adb("shell", "ls", "-l", import_dir)
(OUT / "logs" / "import-dir.txt").write_text(listed)
print(listed, flush=True)
adb("shell", "am", "force-stop", "ru.familytv")
print(adb("shell", "am", "start", "-W", "-n", "ru.familytv/.admin.ContentAdminActivity", "--es", "ftvAction", "importZips"), flush=True)
time.sleep(2.5)
screenshot("00_import")
adb("shell", "am", "force-stop", "ru.familytv")
adb("shell", "am", "start", "-W", "-n", "ru.familytv/.home.HomeActivity")
time.sleep(2.2)
focus_play()
screenshot("01_home_play")
key(23, 1.6)
checkpoint("02_games_home", world="HOME", focusKey="game:0", content="none")
key(20, 0.35)
key(22, 0.35)
checkpoint("03_tiny_focused", world="HOME", focusKey="more:1", content="none")
walk_math(
    "tiny",
    "game-math-tiny",
    wrong=1,
    correct=2,
    equation=2,
    narration="Считаем вместе. Один, два.",
    counts=["1", "2"],
)
key(20, 0.35)
for _ in range(3):
    key(22, 0.3)
checkpoint("04_many_focused", world="HOME", focusKey="more:3", content="none")
walk_math(
    "many",
    "game-math-many",
    wrong=5,
    correct=6,
    equation=6,
    narration="Считаем большую кучу. Один, два, три, четыре, пять, шесть.",
    counts=["1", "2", "3", "4", "5", "6"],
)
key(22, 0.35)
checkpoint("05_color_card", world="HOME", focusKey="game:1", content="none")
key(20, 0.35)
key(22, 0.3)
key(22, 0.3)
checkpoint("06_red_focused", world="HOME", focusKey="paint:2", content="none")
walk_color("red", "game-color-red", wrong="BLUE", correct="RED")
(OUT / "logs" / "familygames.txt").write_text(family())
print("PASS M3 representative packs")
