#!/usr/bin/env python3
"""Install attribute_apply package C, then walk bundled A and package C on H728.

A is the color card (blue wing). C is the second color extra, «Зелёное крыло».
Wrong color stays on the wing. Launch is a separate action after correct.
"""
import argparse
import re
import time
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs/v1/attribute-apply-package-c"
ZIP = ROOT / "dist/attribute-apply-c-v1.zip"
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


def paint_ok(name, **fields):
    key(23, 1.45)
    return checkpoint(name, **fields)


def wait_launched(name, content, painted):
    key(23, 0.2)
    deadline = time.monotonic() + 5
    state = ""
    while time.monotonic() < deadline:
        state = latest()
        if re.search(r"\blaunched=true(?:\s|$)", state) and re.search(r"\bfeedback=CORRECT(?:\s|$)", state):
            break
        time.sleep(0.15)
    else:
        raise SystemExit(("launch did not finish", state))
    return checkpoint(name, content=content, feedback="CORRECT", launched="true", painted=painted, focusKey="next")


def walk(name, content, wrong, correct):
    checkpoint(f"{name}_ready", world="COLOR", content=content, feedback="READY", painted="null", focusKey="choice:1", launched="false")
    key(21, 0.4)
    checkpoint(f"{name}_wrong_focused", world="COLOR", content=content, focusKey="choice:0", painted="null")
    paint_ok(f"{name}_wrong", world="COLOR", content=content, feedback="WRONG", painted=wrong, launched="false")
    key(19, 0.35)
    key(23, 0.45)
    checkpoint(f"{name}_help", world="COLOR", content=content, feedback="HELP", painted=wrong, focusKey="choice:1", launched="false")
    paint_ok(f"{name}_correct", world="COLOR", content=content, feedback="CORRECT", painted=correct, launched="false", focusKey="next")
    wait_launched(f"{name}_launched", content, correct)
    key(4, 0.8)
    checkpoint(f"{name}_back", world="HOME", focusKey="game:1", content="none")


epoch = adb("shell", "date", "+%s").strip() + ".000"
assert ZIP.is_file(), ZIP
installed = adb(
    "shell", "ls",
    "/storage/emulated/0/Android/data/ru.familytv/files/FamilyTV/packages/attribute-apply-c/1/package.json",
    check=False,
)
print(installed, flush=True)
if "package.json" not in installed:
    import_dir = "/storage/emulated/0/Android/data/ru.familytv/files/FamilyTV/import-packages"
    adb("shell", "mkdir", "-p", import_dir)
    remote_zip = f"{import_dir}/attribute-apply-c-v1.zip"
    pushed = subprocess.run(ADB + ["push", str(ZIP), remote_zip], check=False, capture_output=True, text=True)
    print(pushed.stdout, pushed.stderr, flush=True)
    listed = adb("shell", "ls", "-l", import_dir)
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
key(22, 0.4)
checkpoint("03_color_card", world="HOME", focusKey="game:1", content="none")
key(20, 0.4)
checkpoint("04_bundled_b_focused", world="HOME", focusKey="paint", content="none")
key(22, 0.4)
checkpoint("05_package_c_focused", world="HOME", focusKey="paint:1", content="none")
key(19, 0.4)
checkpoint("06_color_card_again", world="HOME", focusKey="game:1", content="none")
key(23, 0.7)
walk("a", "game-color", wrong="RED", correct="BLUE")
key(20, 0.4)
key(22, 0.4)
checkpoint("c_focused", world="HOME", focusKey="paint:1", content="none")
key(23, 0.7)
walk("c", "game-color-package-c", wrong="BLUE", correct="GREEN")
(OUT / "logs" / "familygames.txt").write_text(family())
print("PASS attribute_apply A and package C")
