#!/usr/bin/env python3
"""Capture ONLY Space PATTERN_SEQUENCE Challenge Active on H728."""
import re
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/v1/space-expedition-challenge-active'
ADB = ['adb', '-s', '192.168.68.105:5555']
PKG = 'ru.familytv'
HOME = PKG + '/.home.HomeActivity'
SPACE = 'SpaceExpeditionActivity'
LEFT, RIGHT, UP, DOWN, OK = 21, 22, 19, 20, 23
TAG = 'FamilySpace:I'
HOME_TAG = 'FamilyHome:I'
OUT.mkdir(parents=True, exist_ok=True)


def adb(*parts, binary=False, timeout=40):
    result = subprocess.run(ADB + list(parts), capture_output=True, timeout=timeout)
    return result.stdout if binary else result.stdout.decode(errors='replace')


def key(code, delay=.5):
    adb('shell', 'input', 'keyevent', str(code))
    time.sleep(delay)


def last_space():
    logs = adb('logcat', '-d', '-v', 'threadtime', '-T', epoch, '-s', TAG)
    matches = re.findall(
        r'screen=(\S+) focus=(\S+) node=(\S+) scene=(\S+) mechanic=(\S+)',
        logs,
    )
    return matches[-1] if matches else None


def last_home_focus():
    logs = adb('logcat', '-d', '-v', 'threadtime', '-T', epoch, '-s', HOME_TAG)
    matches = re.findall(r'focus=(\S+) lastWorld=', logs)
    return matches[-1] if matches else None


def resumed():
    activity = adb('shell', 'dumpsys', 'activity', 'activities')
    return next((s.strip() for s in activity.splitlines() if 'ResumedActivity' in s), '')


def wait_space(screen=None, timeout=14):
    deadline = time.monotonic() + timeout
    actual = None
    while time.monotonic() < deadline:
        if SPACE not in resumed():
            time.sleep(.12)
            continue
        actual = last_space()
        if actual and (screen is None or actual[0] == screen):
            return actual
        time.sleep(.12)
    raise AssertionError(('timeout', screen, actual, resumed()))


apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
assert apk.is_file()
print(adb('install', '-r', str(apk), timeout=120)[-400:], flush=True)
epoch = adb('shell', 'date +%s').strip() + '.000'
adb('logcat', '-c')
adb('shell', 'am', 'force-stop', PKG)
time.sleep(.3)
prefs = adb('shell', 'run-as', PKG, 'cat', 'shared_prefs/familytv_adventure_progress.xml')
prefs = prefs.replace('>launch_flight<', '>repair_pattern<').replace('>science_talk<', '>repair_pattern<').replace('>planet_sort<', '>repair_pattern<')
Path('/tmp/familytv_adventure_progress.xml').write_text(prefs)
adb('push', '/tmp/familytv_adventure_progress.xml', '/data/local/tmp/familytv_adventure_progress.xml')
adb('shell', 'cat /data/local/tmp/familytv_adventure_progress.xml | run-as ru.familytv tee shared_prefs/familytv_adventure_progress.xml >/dev/null')
print('scene', [ln for ln in adb('shell', 'run-as', PKG, 'cat', 'shared_prefs/familytv_adventure_progress.xml').splitlines() if 'space-expedition:scene' in ln], flush=True)
time.sleep(.3)
print(adb('shell', 'am', 'start', '-W', '-n', HOME)[-200:], flush=True)
deadline = time.monotonic() + 12
while time.monotonic() < deadline:
    if 'HomeActivity' in resumed():
        break
    time.sleep(.12)
time.sleep(1.6)
key(DOWN, .2)
key(DOWN, .2)
key(UP, .25)
for _ in range(6):
    key(LEFT, .1)
deadline = time.monotonic() + 5
while time.monotonic() < deadline:
    if last_home_focus() == 'watch':
        break
    time.sleep(.08)
key(DOWN, .7)
key(OK, 1.3)
entry = wait_space('ENTRY')
print('entry', entry, flush=True)
key(OK, 1.0)
wait_space('JOURNEY')
# Launch node still holds the production adventure even if already completed.
for _ in range(10):
    cur = last_space()
    if cur and cur[1] == 'node:launch':
        break
    key(LEFT, .22)
print('journey-focus', last_space(), flush=True)
key(OK, 1.2)
challenge = wait_space('CHALLENGE')
print('challenge', challenge, flush=True)
assert challenge[4] == 'PATTERN_SEQUENCE', challenge
assert challenge[3] == 'repair_pattern', challenge
time.sleep(1.2)
if (last_space() or ['', ''])[1] != 'opt:6':
    key(RIGHT, .45)
time.sleep(0.6)
state = last_space()
print('focus', state, flush=True)
png = adb('exec-out', 'screencap', '-p', binary=True)
(OUT / 'android.png').write_bytes(png)
print('wrote', OUT / 'android.png', len(png), flush=True)
print('PASS', state, flush=True)
