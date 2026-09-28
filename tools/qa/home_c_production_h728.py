#!/usr/bin/env python3
"""H728 production acceptance for Home C / Visual Playground / Cosmic Hearth V2.

Starts ONLY ru.familytv/.home.HomeActivity. Never launches Stitch preview Activities.
ADB D-pad keys stand in for the physical remote. App data is not reset.
"""
import argparse
import json
import re
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/v1/home-c-production'
parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='192.168.68.105:5555')
args = parser.parse_args()
ADB = ['adb', '-s', args.serial]
PKG = 'ru.familytv'
HOME = PKG + '/.home.HomeActivity'
VIDEO = 'home-c-production-full.mp4'
TAG = 'FamilyHome:I'
LEFT, RIGHT, UP, DOWN, OK, BACK = 21, 22, 19, 20, 23, 4
WORLD_FOCUS = ['watch', 'games', 'explore', 'create', 'move']
DESTINATION = {
    'watch': 'WatchActivity',
    'games': 'GameHubActivity',
    'explore': 'ExploreActivity',
    'create': 'CreativeStudioActivity',
    'move': 'MoveActivity',
}
FORBIDDEN = (
    'StitchHomePreview', 'StitchWatchPreview', 'StitchGamesPreview',
    'StitchExplore', 'StitchStory', 'StitchMovePreview',
)
checks, measurements = [], []
recording = None
for directory in ['logs', 'screenshots', 'recordings']:
    (OUT / directory).mkdir(parents=True, exist_ok=True)


def adb(*parts, binary=False, check=True, timeout=30):
    result = subprocess.run(ADB + list(parts), capture_output=True, check=check, timeout=timeout)
    return result.stdout if binary else result.stdout.decode(errors='replace')


def key(code, delay=.45):
    adb('shell', 'input', 'keyevent', str(code))
    time.sleep(delay)


def shot(name):
    (OUT / 'screenshots' / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    print('Captured', name, flush=True)


def home_logs():
    return adb('logcat', '-d', '-v', 'threadtime', '-T', epoch, '-s', TAG)


def last_focus():
    matches = re.findall(r'focus=(\S+) lastWorld=', home_logs())
    return matches[-1] if matches else None


def last_open():
    matches = re.findall(r'open=(\S+) destination=(\S+) AI=(\d+)', home_logs())
    return matches[-1] if matches else None


def resumed():
    activity = adb('shell', 'dumpsys', 'activity', 'activities')
    return next((s.strip() for s in activity.splitlines() if 'ResumedActivity' in s), '')


def wait_resumed(fragment, timeout=20):
    deadline = time.monotonic() + timeout
    top = ''
    while time.monotonic() < deadline:
        top = resumed()
        if fragment in top and not any(bad in top for bad in FORBIDDEN):
            return top
        time.sleep(.12)
    raise AssertionError(('resumed timeout', fragment, top))


def wait_focus(expected, timeout=8):
    deadline = time.monotonic() + timeout
    actual = None
    while time.monotonic() < deadline:
        actual = last_focus()
        if actual == expected:
            return actual
        time.sleep(.08)
    raise AssertionError(('focus timeout', expected, actual, home_logs()[-1200:]))


def wait_home(expected_focus=None, timeout=20):
    top = wait_resumed('home.HomeActivity', timeout)
    assert 'StitchHome' not in top, top
    if expected_focus:
        wait_focus(expected_focus)
    return top


def go_world(index):
    key(DOWN, .15)
    key(DOWN, .15)
    key(UP, .22)
    for _ in range(6):
        key(LEFT, .12)
    for _ in range(index):
        key(RIGHT, .35)
    wait_focus(WORLD_FOCUS[index])


def open_and_return(index, shot_name, pss_name):
    world = WORLD_FOCUS[index]
    go_world(index)
    key(OK, .8)
    top = wait_resumed(DESTINATION[world])
    opened = last_open()
    assert opened and opened[2] == '0', opened
    assert not any(bad in top for bad in FORBIDDEN), top
    checks.append({'name': f'open-{world}', 'resumed': top, 'open': opened})
    print('open', world, top, opened, flush=True)
    time.sleep(.6)
    key(BACK, .8)
    wait_home(world)
    time.sleep(.5)
    shot(shot_name)
    memory(pss_name)
    checks.append({'name': f'return-{world}', 'resumed': resumed(), 'focus': last_focus()})
    print('return', world, last_focus(), flush=True)


def memory(label):
    content = adb('shell', 'dumpsys', 'meminfo', PKG)
    (OUT / 'logs' / f'pss-{label}.txt').write_text(content)
    match = re.search(r'TOTAL PSS:\s*(\d+)', content) or re.search(r'^\s*TOTAL\s+(\d+)', content, re.M)
    assert match, content[:400]
    pid = adb('shell', 'pidof', PKG).strip()
    mappings = adb('shell', 'run-as', PKG, 'cat', f'/proc/{pid}/maps')
    (OUT / 'logs' / f'maps-{label}.txt').write_text(mappings)
    models = sorted(set(re.findall(r'\S+\.(?:gguf|onnx)\b', mappings, re.I)))
    assert not models, (label, models)
    sample = {'surface': label, 'pid': int(pid), 'pssKiB': int(match[1]), 'modelMappings': models, 'ai': 0, 'focus': last_focus()}
    measurements.append(sample)
    print('memory', sample, flush=True)
    return sample


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


info = adb('shell', 'getprop ro.product.model; getprop ro.soc.model; getprop ro.hardware; getprop ro.build.version.release; wm size; wm density')
assert 'h728' in info.lower(), info
(OUT / 'logs' / 'device.txt').write_text(info)
epoch = adb('shell', 'date +%s').strip() + '.000'
adb('logcat', '-c')
adb('shell', 'am', 'force-stop', PKG)
time.sleep(.4)
recording = subprocess.Popen(
    ADB + ['shell', 'screenrecord', '--size', '1920x1080', '--bit-rate', '8000000', '--time-limit', '180', '/sdcard/' + VIDEO],
    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
)
time.sleep(1)
launch = adb('shell', 'am', 'start', '-W', '-n', HOME)
(OUT / 'logs' / 'launch.txt').write_text(launch)
assert 'HomeActivity' in launch
assert 'StitchHome' not in launch
wait_home()
time.sleep(1.6)
assert 'AI=0' in home_logs()
memory('cold-home')

go_world(0)
shot('01_home_watch_focus')
go_world(1)
shot('02_home_games_focus')
go_world(2)
shot('03_home_explore_focus')
go_world(3)
shot('04_home_create_focus')
go_world(4)
shot('05_home_move_focus')

# Repeated focus cycling before destination hops.
for _ in range(2):
    for index in range(5):
        go_world(index)
memory('focus-cycles')

open_and_return(0, '06_watch_return_focus', 'watch-return')
open_and_return(1, '07_games_return_focus', 'games-return')
open_and_return(2, '08_explore_return_focus', 'explore-return')
open_and_return(3, '09_create_return_focus', 'create-return')
open_and_return(4, '10_move_return_focus', 'move-return')

go_world(0)
key(DOWN, .6)
shot('11_home_current_mission')
mission_top = resumed()
if 'HomeActivity' in mission_top:
    key(OK, .9)
    time.sleep(.8)
    after = resumed()
    checks.append({'name': 'mission-ok', 'before': mission_top, 'after': after, 'empty': 'HomeActivity' in after})
    if 'HomeActivity' not in after:
        assert any(name in after for name in ('JourneyMapActivity', 'WatchActivity', 'ContentDetailsActivity')), after
        assert not any(bad in after for bad in FORBIDDEN), after
        key(BACK, .9)
        wait_home()
        shot('11_home_current_mission')

go_world(2)
key(UP, .5)
wait_focus('journal')
shot('12_home_journal_entry')
key(OK, .8)
journal = wait_resumed('DiscoveryJournalActivity')
assert not any(bad in journal for bad in FORBIDDEN), journal
checks.append({'name': 'journal', 'resumed': journal})
key(BACK, .8)
wait_home()

go_world(3)
key(UP, .5)
wait_focus('parent')
shot('13_home_parent_entry')
key(OK, .8)
parent = wait_resumed('ParentModeActivity')
assert not any(bad in parent for bad in FORBIDDEN), parent
pin = adb('exec-out', 'screencap', '-p', binary=True)
(OUT / 'screenshots' / '13_home_parent_entry.png').write_bytes(pin)
checks.append({'name': 'parent', 'resumed': parent, 'pinVisible': True})
key(BACK, .8)
wait_home()
shot('14_home_after_parent')

logcat = adb('logcat', '-d', '-v', 'threadtime', '-T', epoch)
(OUT / 'logs' / 'logcat.txt').write_text(logcat)
assert not re.search(r'Fatal Exception|ANR in ru.familytv|NativeQwen.create|VoiceRuntime.get\(', logcat)
assert 'voice_control_only' in home_logs() or 'AI=0' in home_logs()
(OUT / 'logs' / 'state.log').write_text(home_logs())
(OUT / 'logs' / 'checks.json').write_text(json.dumps(checks, indent=2, ensure_ascii=False))
(OUT / 'logs' / 'performance.json').write_text(json.dumps(measurements, indent=2))
stop_recording()
print('PASS Home C production', json.dumps({'checks': len(checks), 'pss': measurements}, ensure_ascii=False), flush=True)
