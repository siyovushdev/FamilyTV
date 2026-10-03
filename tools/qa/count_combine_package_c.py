#!/usr/bin/env python3
"""Install package C through Content Admin, then walk A and C on the games hub.

A is the bundled apple card. C is the second extra button, after bundled B.
C's content id is not in the APK catalog.
"""
import argparse
import re
import time
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs/v1/count-combine-package-c"
ZIP = ROOT / "dist/count-combine-c-v1.zip"
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


def count_until_end(start, narration, counts):
    deadline = time.monotonic() + 12
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
    return segment


def walk(name, content, wrong, correct, equation, narration, counts):
    key(23, 0.6)
    checkpoint(f"{name}_world", world="MATH", content=content, combined="false", choices="0", focusKey="world", round="0")
    key(23, 1.6)
    checkpoint(f"{name}_combined", world="MATH", content=content, combined="true", feedback="READY", focusKey="choice:0", equation="?")
    key(23, 0.45)
    checkpoint(f"{name}_wrong", world="MATH", content=content, feedback="WRONG", mathChoice=str(wrong), equation="?")
    key(19, 0.35)
    start = len(family().splitlines())
    key(23, 0.15)
    count_until_end(start, narration, counts)
    checkpoint(f"{name}_help", world="MATH", content=content, feedback="HELP", focusKey="help", equation="?", counting="false")
    key(20, 0.4)
    key(22, 0.4)
    key(23, 0.8)
    checkpoint(f"{name}_success", world="MATH", content=content, feedback="CORRECT", equation=str(equation), mathChoice=str(correct), round="0")
    key(4, 0.8)
    checkpoint(f"{name}_back", world="HOME", focusKey="game:0", content="none")


epoch = adb("shell", "date", "+%s").strip() + ".000"
assert ZIP.is_file(), ZIP
installed = adb("shell", "ls", "/storage/emulated/0/Android/data/ru.familytv/files/FamilyTV/packages/count-combine-c/1/package.json", check=False)
print(installed, flush=True)
if "package.json" not in installed:
    import_dir = "/storage/emulated/0/Android/data/ru.familytv/files/FamilyTV/import-packages"
    adb("shell", "mkdir", "-p", import_dir)
    remote_zip = f"{import_dir}/count-combine-c-v1.zip"
    pushed = subprocess.run(ADB + ["push", str(ZIP), remote_zip], check=False, capture_output=True, text=True)
    print(pushed.stdout, pushed.stderr, flush=True)
    if pushed.returncode != 0:
        adb("push", str(ZIP), "/data/local/tmp/count-combine-c-v1.zip")
        copied = adb("shell", "run-as", "ru.familytv", "cp", "/data/local/tmp/count-combine-c-v1.zip", remote_zip, check=False)
        print(copied, flush=True)
    listed = adb("shell", "ls", "-l", import_dir)
    print(listed, flush=True)
    (OUT / "logs" / "import-dir.txt").write_text(listed)
    adb("shell", "am", "force-stop", "ru.familytv")
    started = adb("shell", "am", "start", "-W", "-n", "ru.familytv/.admin.ContentAdminActivity", "--es", "ftvAction", "importZips")
    print(started, flush=True)
    time.sleep(2)
    screenshot("00_import")
adb("shell", "am", "force-stop", "ru.familytv")
adb("shell", "am", "start", "-W", "-n", "ru.familytv/.home.HomeActivity")
time.sleep(2.2)
focus_play()
screenshot("01_home_play")
key(23, 1.6)
checkpoint("02_games_home", world="HOME", focusKey="game:0", content="none")
key(20, 0.4)
checkpoint("03_bundled_b_focused", world="HOME", focusKey="more", content="none")
key(22, 0.4)
checkpoint("04_package_c_focused", world="HOME", focusKey="more:1", content="none")
key(19, 0.4)
checkpoint("05_apple_card", world="HOME", focusKey="game:0", content="none")
walk(
    "a",
    "game-math",
    wrong=4,
    correct=5,
    equation=5,
    narration="Посчитаем вместе. Один, два, три, четыре, пять.",
    counts=["1", "2", "3", "4", "5"],
)
key(20, 0.4)
key(22, 0.4)
checkpoint("c_focused", world="HOME", focusKey="more:1", content="none")
walk(
    "c",
    "game-math-package-c",
    wrong=3,
    correct=4,
    equation=4,
    narration="Считаем пакет. Один, два, три, четыре.",
    counts=["1", "2", "3", "4"],
)
(OUT / "logs" / "familygames.txt").write_text(family())
(OUT / "logs" / "familyhome.txt").write_text(logs("FamilyHome"))
print("PASS package C and bundled A")
