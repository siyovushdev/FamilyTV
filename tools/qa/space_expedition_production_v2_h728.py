#!/usr/bin/env python3
"""H728 visual correction pass for Space Expedition V2.

Starts ONLY ru.familytv/.home.HomeActivity. Never launches Stitch preview Activities.
Does not reset app data. Uses the real persisted journey/adventure.
Does not overwrite docs/v1/space-expedition-production/.
"""
import json
import re
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/v1/space-expedition-production-v2'
ADB = ['adb', '-s', '192.168.68.105:5555']
PKG = 'ru.familytv'
HOME = PKG + '/.home.HomeActivity'
SPACE = 'SpaceExpeditionActivity'
VIDEO = 'space-expedition-v2-full.mp4'
TAG = 'FamilySpace:I'
HOME_TAG = 'FamilyHome:I'
LEFT, RIGHT, UP, DOWN, OK, BACK = 21, 22, 19, 20, 23, 4
FORBIDDEN = (
    'StitchHomePreview', 'StitchWatchPreview', 'StitchGamesPreview',
    'StitchExplore', 'StitchStory', 'StitchMovePreview',
)
checks, measurements = [], []
recording = None
for directory in ['logs', 'screenshots', 'recordings']:
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
    raise AssertionError(('space timeout', screen, actual, logs()[-2000:]))


def space_focus():
    state = last_space()
    return state[1] if state else None


def focus_key(target, timeout=8):
    deadline = time.monotonic() + timeout
    prefix = target.split(':')[0] + ':'
    while time.monotonic() < deadline:
        cur = space_focus()
        if cur == target:
            return cur
        if not cur or not cur.startswith(prefix):
            if cur == 'help' or cur == 'pause' or cur == 'back':
                key(DOWN, .2)
            elif cur == 'check':
                key(UP, .2)
            elif prefix == 'cat:' and cur and cur.startswith('item:'):
                key(DOWN, .22)
            elif prefix == 'item:' and cur and cur.startswith('cat:'):
                key(UP, .22)
            else:
                key(DOWN, .2)
            continue
        space = last_space()
        controls = (space[8] if space else '').split(',')
        group = [k for k in controls if k.startswith(prefix)]
        if cur in group and target in group:
            if group.index(cur) < group.index(target):
                key(RIGHT, .22)
            else:
                key(LEFT, .22)
            continue
        key(RIGHT, .2)
    raise AssertionError(('focus', target, space_focus(), last_space()))


def place_sort(item, zone):
    focus_key(item)
    key(OK, .45)
    focus_key(zone)
    key(OK, .55)


def focus_cell(target, timeout=8):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        cur = space_focus()
        if cur == target:
            return cur
        if not cur or not cur.startswith('cell:'):
            key(DOWN, .22)
            continue
        cx, cy = [int(p) for p in cur.replace('cell:', '').split(',')]
        tx, ty = [int(p) for p in target.replace('cell:', '').split(',')]
        if cy < ty:
            key(DOWN, .22)
        elif cy > ty:
            key(UP, .22)
        elif cx < tx:
            key(RIGHT, .22)
        elif cx > tx:
            key(LEFT, .22)
        else:
            return cur
    raise AssertionError(('cell focus', target, space_focus(), last_space()))


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
    space = last_space()
    sample = {
        'surface': label, 'pid': int(pid), 'pssKiB': int(match[1]),
        'modelMappings': models, 'ai': int(space[-1]) if space else 0,
        'space': space, 'resumed': resumed(),
    }
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
    ADB + ['shell', 'screenrecord', '--size', '1920x1080', '--bit-rate', '8000000', '--time-limit', '180', '/sdcard/' + VIDEO],
    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
)
time.sleep(1)
launch = adb('shell', 'am', 'start', '-W', '-n', HOME)
(OUT / 'logs' / 'launch.txt').write_text(launch)
assert 'HomeActivity' in launch
wait_resumed('home.HomeActivity')
time.sleep(1.8)
assert 'StitchHome' not in resumed()
memory('home')

go_world(0)
key(DOWN, .7)
shot('00_home_mission')
key(OK, 1.2)
top = wait_resumed(SPACE)
assert not any(bad in top for bad in FORBIDDEN), top
entry = wait_space('ENTRY')
assert entry[-1] == '0', entry
checks.append({'name': 'entry', 'space': entry, 'resumed': top})
shot('01_entry')
memory('entry')

key(OK, 1.0)
journey = wait_space('JOURNEY')
assert journey[-1] == '0', journey
shot('02_journey')
memory('journey')

key(OK, 1.2)
scene = wait_space()
assert scene[0] in ('INTRO', 'CHALLENGE'), scene
if scene[0] == 'INTRO':
    key(OK, .8)
    challenge = wait_space('CHALLENGE')
else:
    challenge = scene
shot('03_problem_in_world')
time.sleep(.35)
shot('04_physical_choices')
memory('challenge')
assert challenge[-1] == '0', challenge
mechanic = challenge[4]
checks.append({'name': 'challenge', 'space': challenge, 'mechanic': mechanic})

if mechanic == 'PATTERN_SEQUENCE':
    key(OK, .9)
    wrong = wait_space()
    shot('05_wrong_world_reaction')
    checks.append({'name': 'wrong', 'space': wrong, 'beat': wrong})
    key(UP, .4)
    key(OK, .7)
    help_state = wait_space()
    shot('06_help_simplified')
    checks.append({'name': 'help', 'space': help_state, 'helping': help_state[5]})
    key(DOWN, .35)
    focus_key('opt:6')
    key(OK, .6)
elif mechanic == 'PATH_MAZE':
    focus_cell('cell:0,0')
    key(OK, .8)
    wrong = wait_space()
    shot('05_wrong_world_reaction')
    checks.append({'name': 'wrong', 'space': wrong})
    key(UP, .4)
    key(OK, .7)
    help_state = wait_space()
    shot('06_help_simplified')
    checks.append({'name': 'help', 'space': help_state, 'helping': help_state[5]})
    for cell in ('cell:1,1', 'cell:2,1', 'cell:3,1'):
        focus_cell(cell)
        key(OK, .55)
elif mechanic == 'SORT_AND_CLASSIFY':
    place_sort('item:whale', 'cat:land')
    wrong = wait_space()
    shot('05_wrong_world_reaction')
    checks.append({'name': 'wrong', 'space': wrong})
    key(UP, .35)
    key(UP, .35)
    key(OK, .7)
    help_state = wait_space()
    shot('06_help_simplified')
    checks.append({'name': 'help', 'space': help_state, 'helping': help_state[5]})
    place_sort('item:whale', 'cat:water')
    place_sort('item:tree', 'cat:land')
    place_sort('item:cloud', 'cat:air')
    place_sort('item:fish', 'cat:water')
    if last_space() and last_space()[0] == 'CHALLENGE' and 'check' in (last_space()[8] or ''):
        key(DOWN, .3)
        key(DOWN, .3)
        key(OK, .6)
else:
    key(OK, .8)
    shot('05_wrong_world_reaction')
    key(UP, .4)
    key(OK, .7)
    shot('06_help_simplified')
    key(DOWN, .35)
    key(RIGHT, .35)
    key(OK, .8)

success = wait_space('SUCCESS', timeout=6)
shot('07_correct_object_installed')
time.sleep(.7)
shot('08_ship_reacts')
time.sleep(.8)
shot('09_ship_moves')
memory('success')
checks.append({'name': 'success', 'space': last_space()})

updated = wait_space('JOURNEY', timeout=8)
shot('10_journey_updated')
memory('updated-journey')
checks.append({'name': 'updated-journey', 'space': updated, 'reacted': updated[7]})
assert updated[7] == 'true', updated

key(BACK, .7)
wait_space('ENTRY')
key(BACK, 1.0)
wait_resumed('home.HomeActivity')
time.sleep(.6)
shot('11_home_return')
memory('home-return')
checks.append({'name': 'home-return', 'focus': last_home_focus(), 'resumed': resumed()})

fatal = adb('logcat', '-d', '-v', 'threadtime', '-T', epoch, '-s', 'AndroidRuntime:E', 'ActivityManager:I')
anr = 'ANR in ru.familytv' in fatal or 'FATAL EXCEPTION' in fatal
checks.append({'name': 'stability', 'anr_or_fatal': anr})
assert not anr, fatal[-2000:]

(OUT / 'logs' / 'logcat.txt').write_text(logs() + '\n' + logs(HOME_TAG))
(OUT / 'logs' / 'state.log').write_text(logs())
(OUT / 'logs' / 'checks.json').write_text(json.dumps(checks, indent=2, ensure_ascii=False))
(OUT / 'logs' / 'performance.json').write_text(json.dumps(measurements, indent=2))
stop_recording()
print('PASS', json.dumps({'checks': len(checks), 'pss': measurements}, ensure_ascii=False), flush=True)
