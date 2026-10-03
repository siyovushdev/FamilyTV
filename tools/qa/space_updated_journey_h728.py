#!/usr/bin/env python3
"""H728: Success → Updated Journey on shared journey_bg.jpg. No commit."""
import json
import re
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/v1/space-expedition-native'
ADB = ['adb', '-s', '192.168.68.105:5555']
PKG = 'ru.familytv'
HOME = PKG + '/.home.HomeActivity'
SPACE = 'SpaceExpeditionActivity'
VIDEO = 'success_to_journey.mp4'
TAG = 'FamilySpace:I'
HOME_TAG = 'FamilyHome:I'
LEFT, RIGHT, UP, DOWN, OK, BACK = 21, 22, 19, 20, 23, 4
FORBIDDEN = (
    'StitchHomePreview', 'StitchWatchPreview', 'StitchGamesPreview',
    'StitchExplore', 'StitchStory', 'StitchMovePreview',
)
recording = None
checks = []
for directory in ['logs', 'android', 'recordings']:
    (OUT / directory).mkdir(parents=True, exist_ok=True)


def adb(*parts, binary=False, check=True, timeout=40):
    result = subprocess.run(ADB + list(parts), capture_output=True, check=check, timeout=timeout)
    return result.stdout if binary else result.stdout.decode(errors='replace')


def key(code, delay=.5):
    adb('shell', 'input', 'keyevent', str(code))
    time.sleep(delay)


def shot(name):
    path = OUT / 'android' / (name + '.png')
    path.write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    print('Captured', name, path.stat().st_size, flush=True)
    return path


def logs(tag=TAG):
    return adb('logcat', '-d', '-v', 'threadtime', '-T', epoch, '-s', tag)


def last_space():
    matches = re.findall(
        r'screen=(\S+) focus=(\S+) node=(\S+) scene=(\S+) mechanic=(\S+) helping=(\S+) paused=(\S+) reacted=(\S+) controls=(\S+) AI=(\d+)',
        logs(),
    )
    return matches[-1] if matches else None


def last_home_focus():
    matches = re.findall(r'focus=(\S+) lastWorld=', logs(HOME_TAG))
    return matches[-1] if matches else None


def resumed():
    activity = adb('shell', 'dumpsys', 'activity', 'activities')
    return next((s.strip() for s in activity.splitlines() if 'ResumedActivity' in s), '')


def wait_resumed(fragment, timeout=22):
    deadline = time.monotonic() + timeout
    top = ''
    while time.monotonic() < deadline:
        top = resumed()
        if fragment in top and not any(bad in top for bad in FORBIDDEN):
            return top
        time.sleep(.12)
    raise AssertionError(('resumed timeout', fragment, top))


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
    raise AssertionError(('timeout', screen, actual, resumed(), logs()[-1500:]))


def set_scene(scene):
    adb('shell', 'am', 'force-stop', PKG)
    time.sleep(.3)
    prefs = adb('shell', 'run-as', PKG, 'cat', 'shared_prefs/familytv_adventure_progress.xml')
    for old in ('intro', 'launch_repair', 'launch_flight', 'nav_maze', 'repair_pattern', 'planet_sort', 'science_talk'):
        prefs = prefs.replace(f'>{old}<', f'>{scene}<')
    tmp = Path('/tmp/familytv_adventure_progress.xml')
    tmp.write_text(prefs)
    adb('push', str(tmp), '/data/local/tmp/familytv_adventure_progress.xml')
    adb('shell', 'cat /data/local/tmp/familytv_adventure_progress.xml | run-as ru.familytv tee shared_prefs/familytv_adventure_progress.xml >/dev/null')
    print('scene', scene, flush=True)


def go_watch_mission():
    adb('shell', 'am', 'start', '-W', '-n', HOME)
    wait_resumed('home.HomeActivity')
    time.sleep(1.4)
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
    key(OK, 1.2)


def focus_launch():
    for _ in range(12):
        cur = last_space()
        if cur and cur[1] == 'node:launch':
            return cur
        key(LEFT, .18)
    return last_space()


def stop_recording():
    global recording
    if recording is None:
        return
    pid = adb('shell', 'pidof', 'screenrecord', check=False).strip()
    if pid:
        adb('shell', 'kill', '-2', *pid.split(), check=False)
    try:
        recording.communicate(timeout=15)
    except subprocess.TimeoutExpired:
        recording.terminate()
        recording.communicate(timeout=5)
    recording = None
    time.sleep(1)
    adb('pull', '/sdcard/' + VIDEO, str(OUT / 'recordings' / VIDEO), timeout=90)


apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
assert apk.is_file(), apk
info = adb('shell', 'getprop ro.product.model; getprop ro.soc.model; getprop ro.hardware; getprop ro.build.version.release; wm size; wm density')
assert 'h728' in info.lower(), info
(OUT / 'logs' / 'updated-journey-device.txt').write_text(info)
install = adb('install', '-r', str(apk), timeout=120)
(OUT / 'logs' / 'updated-journey-install.txt').write_text(install)

epoch = adb('shell', 'date +%s').strip() + '.000'
adb('logcat', '-c')
set_scene('repair_pattern')
time.sleep(.3)
go_watch_mission()
wait_space('ENTRY')
assert last_space()[-1] == '0', last_space()
key(OK, 0.85)
wait_space('JOURNEY')
focus_launch()
key(OK, 1.0)
challenge = wait_space('CHALLENGE')
assert challenge[4] == 'PATTERN_SEQUENCE', challenge
assert challenge[-1] == '0', challenge

for _ in range(6):
    cur = last_space()
    if cur and cur[1] == 'opt:6':
        break
    key(RIGHT, .16)
print('correct-focus', last_space(), flush=True)

recording = subprocess.Popen(
    ADB + ['shell', 'screenrecord', '--size', '1920x1080', '--bit-rate', '8000000', '--time-limit', '25', '/sdcard/' + VIDEO],
    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
)
time.sleep(0.8)
key(OK, .2)
success = wait_space('SUCCESS', timeout=6)
assert success[0] == 'SUCCESS', success
assert success[-1] == '0', success
time.sleep(1.6)  # IGNITE / TRAVEL without dumpsys
updated = wait_space('JOURNEY', timeout=8)
assert updated[7] == 'true', updated
assert updated[-1] == '0', updated
time.sleep(0.35)
shot('updated_journey_final')
checks.append({'name': 'updated-journey', 'space': updated})

# Focus still works on the same world
key(RIGHT, .25)
moved = last_space()
assert moved[0] == 'JOURNEY', moved
assert moved[1].startswith('node:') or moved[1] in ('continue', 'journal', 'back'), moved
checks.append({'name': 'focus', 'space': moved})

key(BACK, .7)
entry = wait_space('ENTRY')
assert entry[0] == 'ENTRY', entry
assert entry[-1] == '0', entry
checks.append({'name': 'back', 'space': entry})

fatal = adb('logcat', '-d', '-v', 'threadtime', '-T', epoch, '-s', 'AndroidRuntime:E', 'ActivityManager:I')
anr = 'ANR in ru.familytv' in fatal or 'FATAL EXCEPTION' in fatal
assert not anr, fatal[-2000:]
checks.append({'name': 'stability', 'anr_or_fatal': anr, 'ai': updated[-1]})

(OUT / 'logs' / 'updated-journey-state.log').write_text(logs())
(OUT / 'logs' / 'updated-journey-checks.json').write_text(json.dumps(checks, indent=2, ensure_ascii=False))
stop_recording()
print('PASS', json.dumps(checks, ensure_ascii=False), flush=True)
