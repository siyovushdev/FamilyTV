#!/usr/bin/env python3
"""H728 SORT_AND_CLASSIFY V1 visual check. Does not reset progress."""
import json
import re
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/v1/space-sort-classify'
ADB = ['adb', '-s', '192.168.68.105:5555']
PKG = 'ru.familytv'
HOME = PKG + '/.home.HomeActivity'
SPACE = 'SpaceExpeditionActivity'
VIDEO = 'space-sort-classify.mp4'
TAG = 'FamilySpace:I'
HOME_TAG = 'FamilyHome:I'
LEFT, RIGHT, UP, DOWN, OK, BACK = 21, 22, 19, 20, 23, 4
FORBIDDEN = (
    'StitchHomePreview', 'StitchWatchPreview', 'StitchGamesPreview',
    'StitchExplore', 'StitchStory', 'StitchMovePreview',
)
for directory in ['logs', 'screenshots', 'recordings']:
    (OUT / directory).mkdir(parents=True, exist_ok=True)
recording = None


def adb(*parts, binary=False, check=True, timeout=40):
    result = subprocess.run(ADB + list(parts), capture_output=True, check=check, timeout=timeout)
    return result.stdout if binary else result.stdout.decode(errors='replace')


def key(code, delay=.5):
    adb('shell', 'input', 'keyevent', str(code))
    time.sleep(delay)


def shot(name):
    (OUT / 'screenshots' / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    print('Captured', name, flush=True)


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


def wait_space(screen=None, timeout=12):
    deadline = time.monotonic() + timeout
    actual = None
    while time.monotonic() < deadline:
        wait_resumed(SPACE, 4)
        actual = last_space()
        if actual and (screen is None or actual[0] == screen):
            return actual
        time.sleep(.12)
    raise AssertionError(('space timeout', screen, actual, logs()[-2500:]))


def go_world(index):
    key(DOWN, .15)
    key(DOWN, .15)
    key(UP, .22)
    for _ in range(6):
        key(LEFT, .12)
    for _ in range(index):
        key(RIGHT, .35)
    deadline = time.monotonic() + 6
    wanted = ['watch', 'games', 'explore', 'create', 'move'][index]
    while time.monotonic() < deadline:
        if last_home_focus() == wanted:
            return
        time.sleep(.08)
    raise AssertionError(('world focus', wanted, last_home_focus()))


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
(OUT / 'logs' / 'device.txt').write_text(info)
install = adb('install', '-r', str(apk), timeout=120)
(OUT / 'logs' / 'install.txt').write_text(install)
epoch = adb('shell', 'date +%s').strip() + '.000'
adb('logcat', '-c')
adb('shell', 'am', 'force-stop', PKG)
time.sleep(.5)
recording = subprocess.Popen(
    ADB + ['shell', 'screenrecord', '--size', '1920x1080', '--bit-rate', '8000000', '--time-limit', '120', '/sdcard/' + VIDEO],
    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
)
time.sleep(1)
launch = adb('shell', 'am', 'start', '-W', '-n', HOME)
assert 'HomeActivity' in launch
wait_resumed('home.HomeActivity')
time.sleep(1.6)
go_world(0)
key(DOWN, .7)
shot('00_home_mission')
key(OK, 1.2)
wait_resumed(SPACE)
entry = wait_space('ENTRY')
assert entry[-1] == '0', entry
shot('01_entry')
key(OK, 1.2)
challenge = wait_space('CHALLENGE')
assert challenge[3] == 'planet_sort', challenge
assert challenge[4] == 'SORT_AND_CLASSIFY', challenge
assert challenge[-1] == '0', challenge
assert 'item:' not in challenge[8], challenge
shot('02_object_and_worlds')
print('IDLE', challenge, flush=True)

key(RIGHT, .45)
focus_land = wait_space()
shot('03_focus_land')
print('FOCUS', focus_land, flush=True)
assert focus_land[1] == 'cat:land', focus_land

key(OK, .7)
wrong = wait_space()
time.sleep(.25)
shot('04_wrong_reject')
print('WRONG', wrong, flush=True)
assert wrong[0] == 'CHALLENGE', wrong
assert wrong[4] == 'SORT_AND_CLASSIFY', wrong

key(UP, .4)
key(OK, .6)
help_state = wait_space()
shot('05_help_highlight')
print('HELP', help_state, flush=True)
assert help_state[5] == 'true', help_state
assert help_state[0] == 'CHALLENGE', help_state

key(LEFT, .4)
key(OK, .8)
time.sleep(.55)
placed = wait_space()
shot('06_correct_next_item')
print('PLACED', placed, flush=True)
assert placed[0] == 'CHALLENGE', placed
assert placed[3] == 'planet_sort', placed

key(BACK, .8)
journey = wait_space('JOURNEY')
shot('07_back_journey')
print('JOURNEY', journey, flush=True)
key(OK, 1.0)
resumed_scene = wait_space('CHALLENGE')
shot('08_resume_sort')
print('RESUME', resumed_scene, flush=True)
assert resumed_scene[3] == 'planet_sort', resumed_scene
assert resumed_scene[-1] == '0', resumed_scene

key(BACK, .7)
wait_space('JOURNEY')
key(BACK, .7)
wait_space('ENTRY')
shot('09_entry_again')

fatal = adb('logcat', '-d', '-v', 'threadtime', '-T', epoch, '-s', 'AndroidRuntime:E', 'ActivityManager:I')
assert 'ANR in ru.familytv' not in fatal
assert 'FATAL EXCEPTION' not in fatal
summary = {
    'entry': entry,
    'idle': challenge,
    'focus': focus_land,
    'wrong': wrong,
    'help': help_state,
    'placed': placed,
    'resume': resumed_scene,
    'ai': challenge[-1],
}
(OUT / 'logs' / 'logcat.txt').write_text(logs() + '\n' + logs(HOME_TAG))
(OUT / 'logs' / 'checks.json').write_text(json.dumps(summary, indent=2, ensure_ascii=False))
stop_recording()
print('PASS', json.dumps(summary, ensure_ascii=False), flush=True)
