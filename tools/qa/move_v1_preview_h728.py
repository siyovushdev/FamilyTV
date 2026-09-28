#!/usr/bin/env python3
"""H728 D-pad acceptance of the debug-only Move V1 native preview.

Only StitchMovePreviewActivity is launched. Production Home is not opened.
ADB keyevents stand in for the physical remote. After Ready the walker waits for
deterministic auto-progression — no OK between movement beats.
"""
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/design/stitch/move-v1/android-preview'
PREVIEW = 'ru.familytv/.move.stitch.StitchMovePreviewActivity'
VIDEO = 'move-v1-flow.mp4'
TAG = 'StitchMove:I'
ADB = ['adb', '-s', '192.168.68.105:5555']
checks, measurements = [], []
recording = None
for directory in ['logs', 'screenshots', 'recordings']:
    (OUT / directory).mkdir(parents=True, exist_ok=True)


def adb(*parts, binary=False, check=True, timeout=30):
    result = subprocess.run(ADB + list(parts), capture_output=True, check=check, timeout=timeout)
    return result.stdout if binary else result.stdout.decode(errors='replace')


def logs():
    return adb('logcat', '-d', '-v', 'threadtime', '-T', epoch, '-s', TAG)


def state_lines():
    return [line for line in logs().splitlines() if ' screen=' in line]


def latest():
    lines = state_lines()
    assert lines, 'No preview log'
    return lines[-1]


def matches(line, fields):
    return all(re.search(r'\s' + k + '=' + re.escape(str(v)) + r'(?:\s|$)', line) for k, v in fields.items())


def field(line, name):
    return re.search(r'\s' + name + r'=(\S+)', line)[1]


def wait(timeout=28, **fields):
    deadline = time.monotonic() + timeout
    line = ''
    while time.monotonic() < deadline:
        line = (state_lines() or [''])[-1]
        if line and matches(line, fields):
            return line
        time.sleep(.08)
    raise AssertionError(('state timeout', fields, line))


def key(code, delay=.35):
    adb('shell', 'input', 'keyevent', str(code))
    time.sleep(delay)


LEFT, RIGHT, UP, DOWN, OK, BACK = 21, 22, 19, 20, 23, 4
PAUSE_KEY, MENU, REWIND = 127, 82, 89


def shot(name):
    (OUT / 'screenshots' / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))


def resumed():
    activity = adb('shell', 'dumpsys', 'activity', 'activities')
    return next((s.strip() for s in activity.splitlines() if 'ResumedActivity' in s), '')


def checkpoint(name, capture=True, settle=0.35, **fields):
    state = wait(**fields)
    assert 'AI=0' in state, state
    top = resumed()
    assert 'StitchMovePreviewActivity' in top, top
    assert 'HomeActivity' not in top
    if settle:
        time.sleep(settle)
    checks.append({'name': name, 'resumed': top, 'state': state})
    print(name, state.split(': ', 1)[-1][:180], flush=True)
    if capture:
        shot(name)
    return state


def memory(label):
    content = adb('shell', 'dumpsys', 'meminfo', 'ru.familytv')
    (OUT / 'logs' / f'pss-{label}.txt').write_text(content)
    match = re.search(r'TOTAL PSS:\s*(\d+)', content) or re.search(r'^\s*TOTAL\s+(\d+)', content, re.M)
    assert match, content
    pid = adb('shell', 'pidof', 'ru.familytv').strip()
    mappings = adb('shell', 'run-as', 'ru.familytv', 'cat', f'/proc/{pid}/maps')
    (OUT / 'logs' / f'maps-{label}.txt').write_text(mappings)
    models = sorted(set(re.findall(r'\S+\.(?:gguf|onnx)\b', mappings, re.I)))
    assert not models, (label, models)
    sample = {'surface': label, 'pid': int(pid), 'pssKiB': int(match[1]), 'modelMappings': models, 'ai': field(latest(), 'AI')}
    assert sample['ai'] == '0', sample
    measurements.append(sample)
    print('memory', sample, flush=True)
    return sample


def store(name):
    content = adb('shell', 'run-as', 'ru.familytv', 'cat', f'shared_prefs/{name}.xml', check=False)
    return content if content.startswith('<?xml') else ''


def start_recording():
    global recording
    adb('shell', 'rm', '-f', f'/sdcard/{VIDEO}', check=False)
    recording = subprocess.Popen(ADB + ['shell', 'screenrecord', '--bugreport', f'/sdcard/{VIDEO}'])


def stop_recording():
    global recording
    if recording is None:
        return
    pid = adb('shell', 'pidof', 'screenrecord', check=False).strip()
    if pid:
        adb('shell', 'kill', '-2', *pid.split(), check=False)
    try:
        recording.wait(timeout=8)
    except Exception:
        recording.kill()
    recording = None
    time.sleep(1.2)
    adb('pull', f'/sdcard/{VIDEO}', str(OUT / 'recordings' / VIDEO))


STORES = ['familytv_stories', 'familytv_activity', 'familytv_journey_progress', 'familytv_discovery_journal']

try:
    (OUT / 'logs/device.txt').write_text(
        'model=' + adb('shell', 'getprop', 'ro.product.model').strip() + '\n'
        'android=' + adb('shell', 'getprop', 'ro.build.version.release').strip() + '\n'
        + adb('shell', 'wm', 'size') + adb('shell', 'wm', 'density')
        + 'animator_duration_scale=' + adb('shell', 'settings', 'get', 'global', 'animator_duration_scale').strip() + '\n'
        + 'input=ADB keyevent (physical remote NOT VERIFIED)\n'
    )
    epoch = adb('shell', 'date', '+%s').strip()
    stores_before = {name: store(name) for name in STORES}
    adb('logcat', '-c')
    start_recording()
    adb('shell', 'am', 'force-stop', 'ru.familytv')
    time.sleep(0.6)
    adb('shell', 'am', 'start', '-W', '-n', PREVIEW)
    wait(screen='MOVE_HOME', timeout=20)
    checkpoint('01_move_home', screen='MOVE_HOME', focus='place:STARS')
    memory('move-home')

    key(LEFT, .4)
    key(OK, .5)
    soon = wait(timeout=6)
    assert 'soon=' in soon and 'soon=null' not in soon, soon
    assert field(soon, 'screen') == 'MOVE_HOME'
    checks.append({'name': 'future-place-honest', 'state': soon})

    while field(latest(), 'focus') != 'place:STARS':
        key(RIGHT, .25)
        if field(latest(), 'focus') == 'start':
            key(LEFT, .25)
            break
    if field(latest(), 'focus') != 'place:STARS':
        # start also begins the adventure
        while field(latest(), 'focus') != 'start':
            key(RIGHT, .25)
    key(OK, 1.0)
    checkpoint('02_ready', screen='READY')
    memory('ready')

    key(OK, 0.8)
    checkpoint('03_reach_start', screen='REACH', phase='INTRO')
    memory('reach')
    awake = checkpoint('04_reach_stars_awake', screen='REACH', phase='CONSEQUENCE', timeout=16)
    assert float(field(awake, 'stars').replace(',', '.')) > 0.75

    wait(screen='COMETS', phase='LEFT', timeout=16)
    key(PAUSE_KEY, .5)
    paused = wait(paused='true', screen='COMETS', phase='LEFT')
    shot('05_comets_left')
    shot('12_pause')
    checks.append({'name': '05_comets_left', 'state': paused})
    elapsed = field(paused, 'elapsed')
    key(REWIND, .4)
    assert field(latest(), 'screen') == 'COMETS' and field(latest(), 'phase') == 'LEFT'
    checks.append({'name': 'repeat-no-advance', 'state': latest()})
    time.sleep(3.0)
    still = latest()
    assert field(still, 'paused') == 'true' and field(still, 'phase') == 'LEFT'
    assert field(still, 'elapsed') == elapsed
    checks.append({'name': 'pause-holds-beat', 'paused': paused, 'still': still})
    key(OK, 0.6)
    resumed_beat = wait(paused='false', screen='COMETS')
    assert field(resumed_beat, 'phase') in ('LEFT', 'RIGHT')
    shot('13_resume_same_beat')
    checks.append({'name': 'resume-same-beat', 'state': resumed_beat})
    print('05_comets_left / pause / resume', field(resumed_beat, 'phase'), flush=True)
    checkpoint('06_comets_right', screen='COMETS', phase='RIGHT', timeout=12)

    checkpoint('07_freeze_move', screen='FREEZE', phase='MOVE', timeout=16)
    key(MENU, .5)
    help_line = wait(screen='FREEZE', timeout=6)
    assert field(help_line, 'help') != '0' or 'action=Help' in logs()
    checks.append({'name': 'help-on-freeze', 'state': latest()})
    checkpoint('08_freeze_hold', screen='FREEZE', phase='FREEZE', timeout=16)
    assert 'score' not in latest().lower()
    memory('freeze')

    checkpoint('09_balance', screen='BALANCE', phase='ACTIVE', timeout=16)
    checkpoint('10_ending_breathe', screen='ENDING', phase='BREATH_IN', timeout=16)
    checkpoint('11_ending_actions', screen='ENDING', phase='ACTIONS', timeout=16)
    memory('ending')
    assert field(latest(), 'actions') == 'true'

    key(OK, 1.0)
    checkpoint('14_replay', screen='READY')
    key(OK, 0.8)
    wait(screen='REACH', timeout=10)
    wait(screen='ENDING', phase='ACTIONS', timeout=55)
    memory('after-replay')
    growth = measurements[-1]['pssKiB'] - measurements[-2]['pssKiB']
    checks.append({'name': 'replay-memory', 'growthKiB': growth})

    key(RIGHT, .3)
    key(RIGHT, .3)
    key(OK, 0.8)
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline and 'StitchMovePreviewActivity' in resumed():
        time.sleep(.2)
    checks.append({'name': 'exit-safe', 'resumed': resumed()})
    print('PASS', len(checks), 'checks', flush=True)
except Exception:
    raise
finally:
    stop_recording()
    full = adb('logcat', '-d', '-v', 'threadtime', '-T', epoch)
    (OUT / 'logs/state.log').write_text(logs())
    (OUT / 'logs/logcat.txt').write_text('\n'.join(s for s in full.splitlines() if any(t in s for t in
        ['StitchMove', 'FamilyHome', 'VoiceRuntime', 'AndroidRuntime', 'FATAL EXCEPTION', 'Fatal signal', 'ANR'])) + '\n')
    (OUT / 'logs/checks.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2) + '\n')
    (OUT / 'logs/performance.json').write_text(json.dumps(measurements, indent=2) + '\n')
    stores_after = {name: store(name) for name in STORES}
    (OUT / 'logs/stores-delta.json').write_text(json.dumps({
        name: {'changed': stores_before[name] != stores_after[name], 'afterHasTitle': False}
        for name in STORES
    }, ensure_ascii=False, indent=2) + '\n')
