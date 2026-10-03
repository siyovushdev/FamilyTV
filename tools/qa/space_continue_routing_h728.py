#!/usr/bin/env python3
"""H728 Continue/current-node routing check. Does not reset progress."""
import json
import re
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/v1/space-expedition-routing'
ADB = ['adb', '-s', '192.168.68.105:5555']
PKG = 'ru.familytv'
HOME = PKG + '/.home.HomeActivity'
SPACE = 'SpaceExpeditionActivity'
TAG = 'FamilySpace:I'
HOME_TAG = 'FamilyHome:I'
LEFT, RIGHT, UP, DOWN, OK, BACK = 21, 22, 19, 20, 23, 4
FORBIDDEN = (
    'StitchHomePreview', 'StitchWatchPreview', 'StitchGamesPreview',
    'StitchExplore', 'StitchStory', 'StitchMovePreview',
)
for directory in ['logs', 'screenshots']:
    (OUT / directory).mkdir(parents=True, exist_ok=True)


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
launch = adb('shell', 'am', 'start', '-W', '-n', HOME)
(OUT / 'logs' / 'launch.txt').write_text(launch)
assert 'HomeActivity' in launch
wait_resumed('home.HomeActivity')
time.sleep(1.8)

go_world(0)
key(DOWN, .7)
shot('00_home_mission')
key(OK, 1.2)
top = wait_resumed(SPACE)
assert not any(bad in top for bad in FORBIDDEN), top
entry = wait_space('ENTRY')
shot('01_entry')
print('ENTRY', entry, flush=True)
assert entry[1] == 'continue', entry

key(OK, 1.1)
after = wait_space()
shot('02_after_continue')
print('AFTER_CONTINUE', after, flush=True)
assert after[0] in ('INTRO', 'CHALLENGE', 'HUB'), after
assert after[0] != 'JOURNEY', after
assert after[0] != 'ENTRY', after

key(BACK, .8)
back = wait_space()
shot('03_after_back')
print('AFTER_BACK', back, flush=True)
assert back[0] in ('JOURNEY', 'ENTRY'), back
if back[0] == 'JOURNEY':
    assert back[1].startswith('node:') or back[1] == 'continue', back
    key(BACK, .8)
    entry_again = wait_space('ENTRY')
    shot('04_entry_again')
    print('ENTRY_AGAIN', entry_again, flush=True)
    assert entry_again[0] == 'ENTRY', entry_again

fatal = adb('logcat', '-d', '-v', 'threadtime', '-T', epoch, '-s', 'AndroidRuntime:E', 'ActivityManager:I')
assert 'ANR in ru.familytv' not in fatal
assert 'FATAL EXCEPTION' not in fatal
(OUT / 'logs' / 'logcat.txt').write_text(logs() + '\n' + logs(HOME_TAG))
(OUT / 'logs' / 'state.log').write_text(logs())
summary = {
    'entry': entry,
    'after_continue': after,
    'after_back': back,
    'resumed': resumed(),
}
(OUT / 'logs' / 'checks.json').write_text(json.dumps(summary, indent=2, ensure_ascii=False))
print('PASS', json.dumps(summary, ensure_ascii=False), flush=True)
