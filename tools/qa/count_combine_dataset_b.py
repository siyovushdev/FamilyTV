#!/usr/bin/env python3
"""H728 smoke: Play → second COUNT_COMBINE set → wrong → help → correct → back.

Does not replace tools/qa/games_production.py. Dataset A stays on the first card.
Dataset B is the «Ещё яблоки» button under the same games hub.
"""
import argparse
import time
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs/v1/count-combine-b"
p = argparse.ArgumentParser()
p.add_argument("--serial", default="192.168.68.105:5555")
args = p.parse_args()
ADB = ["adb", "-s", args.serial]
for folder in ("screenshots", "logs"):
    (OUT / folder).mkdir(parents=True, exist_ok=True)

def adb(*parts, binary=False, check=True, timeout=30):
    result = subprocess.run(ADB + list(parts), check=check, capture_output=True, timeout=timeout)
    return result.stdout if binary else result.stdout.decode(errors="replace")

def logs():
    return adb("logcat", "-d", "-v", "threadtime", "-T", epoch, "-s", "FamilyGames:I")

def latest():
    lines = [line for line in logs().splitlines() if "world=" in line]
    assert lines, "No production state log"
    return lines[-1]

def key(code, delay=0.35):
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

def ui():
    remote = "/sdcard/familytv-count-combine-b.xml"
    adb("shell", "uiautomator", "dump", remote)
    return adb("shell", "cat", remote)

def focused_desc(xml):
    match = re.search(r'<node[^>]*focused="true"[^>]*>', xml)
    if not match:
        return ""
    desc = re.search(r'content-desc="([^"]*)"', match.group(0))
    text = re.search(r'text="([^"]*)"', match.group(0))
    return (desc.group(1) if desc else "") or (text.group(1) if text else "")

epoch = adb("shell", "date", "+%s").strip() + ".000"
adb("shell", "am", "force-stop", "ru.familytv")
adb("shell", "am", "start", "-W", "-n", "ru.familytv/.home.HomeActivity")
time.sleep(2)
for _ in range(18):
    desc = focused_desc(ui())
    if desc == "Играть":
        break
    if desc in ("Смотреть видео", "Смотреть"):
        key(22, 0.3)
    elif desc in ("Наши открытия", "Наши истории", "Карта"):
        key(21, 0.3)
    else:
        key(19, 0.3)
else:
    raise SystemExit("Could not focus Играть")
screenshot("00_home_play")
key(23, 1.5)
checkpoint("01_games_home", world="HOME", focusKey="game:0", content="none")
key(20, 0.4)
xml = ui()
assert "Ещё яблоки" in xml, xml
checkpoint("02_more_focused", world="HOME", focusKey="more", content="none")
key(23, 0.6)
state = checkpoint("03_dataset_b_world", world="MATH", content="game-math-baskets", combined="false", choices="0", focusKey="world", round="0")
assert "content=game-math" not in state.split("content=")[1].split()[0]
assert "Яблоки: 1 и 1" in ui()
key(23, 1.5)
checkpoint("04_combined", world="MATH", content="game-math-baskets", combined="true", feedback="READY", focusKey="choice:0", equation="?")
key(23, 0.4)
checkpoint("05_wrong", world="MATH", content="game-math-baskets", feedback="WRONG", mathChoice="1", equation="?")
key(19, 0.35)
start = len(logs().splitlines())
key(23, 0.15)
deadline = time.monotonic() + 8
segment = ""
while time.monotonic() < deadline:
    segment = "\n".join(logs().splitlines()[start:])
    if "count_end " in segment:
        break
    time.sleep(0.12)
else:
    raise SystemExit(("count did not finish", segment))
assert "count_narration=Считаем этот набор. Один, два." in segment, segment
assert "Посчитаем вместе. Один, два, три, четыре, пять." not in segment
assert re.findall(r"\bcount=(\d+)\b", segment) == ["1", "2"], segment
checkpoint("06_help", world="MATH", content="game-math-baskets", feedback="HELP", focusKey="help", equation="?", counting="false")
# Help sends every non-up key to the current math choice. Down lands on choice 0, right moves to 2.
key(20, 0.4)
key(22, 0.4)
key(23, 0.8)
checkpoint("07_success", world="MATH", content="game-math-baskets", feedback="CORRECT", equation="2", mathChoice="2", round="0")
key(4, 0.8)
checkpoint("08_back_hub", world="HOME", focusKey="game:0", content="none")
print("PASS dataset B smoke")
