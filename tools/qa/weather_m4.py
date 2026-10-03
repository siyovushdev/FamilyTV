#!/usr/bin/env python3
"""H728 walk of production Weather after the definition migration.

Home → Исследовать → Погода → prediction → experiment → observation →
Почему? → Ask Lumi → Build & Test → result → Back.
"""
import re
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs/v1/weather-m4"
SERIAL = "192.168.68.105:5555"
ADB = ["adb", "-s", SERIAL]
HOME = "ru.familytv/.home.HomeActivity"


def adb(*parts, binary=False, check=True, timeout=30):
    result = subprocess.run(ADB + list(parts), capture_output=True, check=check, timeout=timeout)
    return result.stdout if binary else result.stdout.decode(errors="replace")


def logs(tag):
    return adb("logcat", "-d", "-v", "threadtime", "-T", epoch, "-s", tag)


def key(code, delay=0.35):
    adb("shell", "input", "keyevent", str(code))
    time.sleep(delay)


def shot(name):
    path = OUT / "screenshots" / f"{name}.png"
    path.write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    print("shot", name, flush=True)


def explore_lines():
    return [line for line in logs("FamilyExplore:I").splitlines() if "screen=" in line]


def latest():
    lines = explore_lines()
    assert lines, "no FamilyExplore state"
    return lines[-1]


def matches(line, fields):
    return all(re.search(r"\b" + key + "=" + re.escape(str(value)) + r"(?:\s|$)", line) for key, value in fields.items())


def wait(timeout=22, **fields):
    deadline = time.monotonic() + timeout
    line = ""
    while time.monotonic() < deadline:
        lines = explore_lines()
        line = lines[-1] if lines else ""
        if line and matches(line, fields):
            return line
        time.sleep(0.1)
    raise AssertionError(("timeout", fields, line))


def home_focus():
    lines = [line for line in logs("FamilyHome:I").splitlines() if " focus=" in line]
    if not lines:
        return ""
    match = re.search(r"\bfocus=(\S+)", lines[-1])
    return match.group(1) if match else ""


def focus_explore():
    for _ in range(16):
        focus = home_focus()
        if focus == "explore":
            return
        if focus in ("watch", "games"):
            key(22, 0.3)
        elif focus in ("create", "move"):
            key(21, 0.3)
        elif focus in ("voice", "journal", "parent"):
            key(20, 0.35)
        else:
            key(19, 0.35)
    raise SystemExit(f"could not focus explore, last={home_focus()}\n{logs('FamilyHome:I')}")


for folder in ("logs", "screenshots"):
    (OUT / folder).mkdir(parents=True, exist_ok=True)

adb("logcat", "-c", check=False)
epoch = adb("shell", "date", "'+%m-%d %H:%M:%S.000'").strip()
(OUT / "logs/device.txt").write_text(
    "model=" + adb("shell", "getprop", "ro.product.model")
    + "android=" + adb("shell", "getprop", "ro.build.version.release")
    + adb("shell", "wm", "size")
)
adb("shell", "am", "force-stop", "ru.familytv")
adb("shell", "am", "start", "-W", "-n", HOME)
time.sleep(2.2)
focus_explore()
shot("00_home_explore")
key(23, 1.2)
wait(screen="HOME", focus="destination:WEATHER")
shot("01_explore_home")
key(23, 0.8)
wait(screen="INTRO", focus="start")
shot("02_intro")
key(23, 0.5)
wait(screen="PREDICTION", focus="place:SUN")
key(22, 0.35)
wait(screen="PREDICTION", focus="place:SHADE")
shot("03_prediction")
key(23, 0.3)
wait(screen="EXPERIMENT", hypothesis="SHADE", focus="speed")
shot("04_experiment")
result = wait(timeout=20, screen="RESULT", focus="why", hypothesis="SHADE")
assert "experiment_observed content=adv-weather-lab experiment=where-melts-faster variant=shade" in logs("FamilyExplore:I")
shot("05_observation")
key(23, 0.2)
entry = wait(screen="ASK", cause="SUN", canBuild="false", focus="explanation:CHAIN")
key(20, 0.15)
wait(screen="ASK", focus="repeat", canBuild="false")
shot("06_ask_locked")
key(23, 0.25)
wait(screen="ASK", cause="SUN", canBuild="false", focus="repeat")
shot("07_help_repeat")
done = wait(timeout=16, screen="ASK", cause="DONE", canBuild="true", focus="build")
assert "canBuild=false" in entry
shot("08_ask_done")
key(23, 0.5)
wait(screen="BUILD_CHOOSE", focus="shelter:CANOPY")
shot("09_build_choose")
key(23, 0.4)
wait(screen="BUILD_PLACED", shelter="CANOPY", canCheck="true", focus="check")
shot("10_build_placed")
time.sleep(1.2)
assert matches(latest(), {"screen": "BUILD_PLACED", "canCheck": "true"}), latest()
key(23, 0.3)
wait(screen="BUILD_RUNNING", shelter="CANOPY")
shot("11_build_running")
final = wait(timeout=16, screen="BUILD_RESULT", shelter="CANOPY", focus="change", completed="true")
assert "visit_completed content=adv-weather-lab" in logs("FamilyExplore:I")
shot("12_result")
key(4, 0.5)
wait(screen="BUILD_CHOOSE", focus="shelter:CANOPY")
key(4, 0.5)
wait(screen="ASK", cause="DONE", canBuild="true", focus="build")
key(4, 0.5)
wait(screen="RESULT", focus="why")
key(4, 0.5)
wait(screen="INTRO", focus="start")
key(4, 0.8)
wait(screen="HOME", focus="destination:WEATHER", visit="false")
assert "visit_finished content=adv-weather-lab reason=explore_home completed=true" in logs("FamilyExplore:I")
shot("13_back_explore")
key(4, 1.2)
time.sleep(0.8)
assert home_focus() == "explore", home_focus()
shot("14_back_home")
text = logs("FamilyExplore:I")
assert "narration=LOCAL_TTS" in text, "narration did not use the TTS fallback"
assert "AI=1" not in text
assert "rejected content=" not in text
(OUT / "logs/familyexplore.txt").write_text(text)
print("PASS", result.split("screen=")[-1][:80], final.split("focus=")[-1][:40], flush=True)
