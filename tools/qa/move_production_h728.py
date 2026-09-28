#!/usr/bin/env python3
"""H728 D-pad acceptance of production Move V1 / «Разбудим звёзды».

Every run enters through HomeActivity. The debug preview Activity is never launched
as production evidence. ADB keyevents stand in for the physical remote.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/v1/move-production'
parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='192.168.68.105:5555')
args = parser.parse_args()
ADB = ['adb', '-s', args.serial]
HOME = 'ru.familytv/.home.HomeActivity'
VIDEO = 'move-production-full.mp4'
TAG = 'FamilyMove:I'
MOVE = 'Разбудим звёзды'
checks, measurements = [], []
recording = None
for directory in ['logs', 'screenshots', 'recordings']:
    (OUT / directory).mkdir(parents=True, exist_ok=True)


def adb(*parts, binary=False, check=True, timeout=30):
    result = subprocess.run(ADB + list(parts), capture_output=True, check=check, timeout=timeout)
    return result.stdout if binary else result.stdout.decode(errors='replace')


def logs(tag=TAG):
    return adb('logcat', '-d', '-v', 'threadtime', '-T', epoch, '-s', tag)


def state_lines():
    return [line for line in logs().splitlines() if ' screen=' in line]


def latest():
    lines = state_lines()
    assert lines, 'No production move log'
    return lines[-1]


def matches(line, fields):
    return all(re.search(r'\s' + k + '=' + re.escape(str(v)) + r'(?:\s|$)', line) for k, v in fields.items())


def field(line, name):
    return re.search(r'\s' + name + r'=(\S+)', line.split(' screen=', 1)[1] if name != 'screen' else line)[1]


def wait(timeout=30, **fields):
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


def checkpoint(name, capture=True, settle=0.0, **fields):
    state = wait(**fields)
    assert 'AI=0' in state, state
    top = resumed()
    assert 'MoveActivity' in top and 'StitchMove' not in top, top
    if settle:
        time.sleep(settle)
    checks.append({'name': name, 'resumed': top, 'state': state})
    print(name, state.split(': ', 1)[-1][:180], flush=True)
    if capture:
        shot(name)
    return state


def ui(name=None):
    remote = '/sdcard/familytv-move-production-ui.xml'
    adb('shell', 'uiautomator', 'dump', remote)
    content = adb('shell', 'cat', remote)
    if name:
        (OUT / 'logs' / f'accessibility-{name}.xml').write_text(content)
    return list(ET.fromstring(content).iter('node'))


def focused_node():
    return next((n for n in ui() if n.get('focused') == 'true'), None)


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
    sample = {'surface': label, 'pid': int(pid), 'pssKiB': int(match[1]), 'modelMappings': models}
    if state_lines():
        sample['ai'] = field(latest(), 'AI')
        assert sample['ai'] == '0'
    measurements.append(sample)
    print('memory', sample, flush=True)
    return sample


def store(name):
    content = adb('shell', 'run-as', 'ru.familytv', 'cat', f'shared_prefs/{name}.xml', check=False)
    return content if content.startswith('<?xml') else ''


def history_events():
    xml = store('familytv_activity')
    if not xml:
        return []
    text = ''.join(n.text or '' for n in ET.fromstring(xml).iter() if n.get('name', '').startswith('day_') or (n.tag == 'string' and 'kind' in (n.text or '')))
    return text


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


def focus_move_from_home():
    for _ in range(8):
        key(UP, .15)
    for _ in range(16):
        for _ in range(12):
            node = focused_node()
            label = node is not None and ((node.get('content-desc') or '') + (node.get('text') or ''))
            if label and MOVE in label:
                return label
            before = node.get('bounds') if node is not None else None
            key(RIGHT, .28)
            node = focused_node()
            if node is None or node.get('bounds') == before:
                break
        for _ in range(12):
            key(LEFT, .08)
        key(DOWN, .4)
    raise AssertionError('Move catalog card not reachable with D-pad')


def to_ending_from_ready():
    wait(screen='READY')
    key(OK, .8)
    wait(screen='REACH', timeout=10)
    return wait(screen='ENDING', phase='ACTIONS', timeout=60)


adb('logcat', '-c', check=False)
epoch = adb('shell', 'date', "'+%m-%d %H:%M:%S.000'").strip()
(OUT / 'logs/device.txt').write_text(
    'model=' + adb('shell', 'getprop', 'ro.product.model')
    + 'android=' + adb('shell', 'getprop', 'ro.build.version.release')
    + adb('shell', 'wm', 'size') + adb('shell', 'wm', 'density')
    + 'animator_duration_scale=' + adb('shell', 'settings', 'get', 'global', 'animator_duration_scale')
    + '\ninput=ADB keyevent (physical remote NOT VERIFIED)\n'
)
STORES = ['familytv_stories', 'familytv_activity', 'familytv_journey_progress', 'familytv_discovery_journal', 'familytv_trial']
stores_before = {name: store(name) for name in STORES}
try:
    adb('shell', 'am', 'force-stop', 'ru.familytv')
    (OUT / 'logs/launch.txt').write_text(adb('shell', 'am', 'start', '-W', '-n', HOME))
    time.sleep(2.5)
    assert 'HomeActivity' in resumed()
    memory('home')
    focus = focus_move_from_home()
    checks.append({'name': 'home-move-focus', 'focus': focus})
    recording = subprocess.Popen(
        ADB + ['shell', 'screenrecord', '--size', '1920x1080', '--bit-rate', '6000000', '--time-limit', '180', '/sdcard/' + VIDEO],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    time.sleep(1.5)
    shot('01_home_move_focus')

    key(OK, 1.4)
    checkpoint('02_move_home', settle=.6, screen='MOVE_HOME', focus='place:STARS')
    render = [line for line in logs().splitlines() if ' render size=' in line][-1]
    assert 'render size=1920x1080 scale=1.0' in render, render
    memory('move-home')

    key(LEFT, .4)
    key(OK, .5)
    soon = wait(timeout=6, screen='MOVE_HOME')
    assert 'soon=' in soon and 'soon=null' not in soon, soon
    checks.append({'name': 'future-place-honest', 'state': soon})
    while field(latest(), 'focus') != 'place:STARS':
        key(RIGHT, .25)
        if field(latest(), 'focus') == 'start':
            key(LEFT, .25)
            break
    key(OK, 1.0)
    checkpoint('03_ready', settle=.5, screen='READY')
    memory('ready')

    key(OK, .8)
    checkpoint('04_reach', settle=.4, screen='REACH', phase='INTRO')
    memory('reach')
    awake = wait(screen='REACH', phase='CONSEQUENCE', timeout=16)
    assert float(field(awake, 'stars')) > 0.75

    wait(screen='COMETS', phase='LEFT', timeout=16)
    key(PAUSE_KEY, .5)
    paused = wait(paused='true', screen='COMETS', phase='LEFT')
    shot('05_comets')
    shot('11_pause')
    elapsed = field(paused, 'elapsed')
    key(REWIND, .4)
    assert field(latest(), 'screen') == 'COMETS' and field(latest(), 'phase') == 'LEFT'
    checks.append({'name': 'repeat-no-advance', 'state': latest()})
    time.sleep(3.0)
    still = latest()
    assert field(still, 'paused') == 'true' and field(still, 'phase') == 'LEFT'
    assert field(still, 'elapsed') == elapsed
    checks.append({'name': 'pause-holds-beat', 'paused': paused, 'still': still})
    key(OK, .6)
    resumed_beat = wait(paused='false', screen='COMETS')
    assert field(resumed_beat, 'phase') in ('LEFT', 'RIGHT')
    shot('12_resume')
    checks.append({'name': 'resume-same-beat', 'state': resumed_beat})

    checkpoint('06_freeze', screen='FREEZE', phase='MOVE', timeout=16)
    key(MENU, .5)
    help_line = wait(screen='FREEZE', timeout=6)
    assert field(help_line, 'help') != '0' or 'action=Help' in logs()
    checks.append({'name': 'help-on-freeze', 'state': latest()})
    memory('freeze')

    checkpoint('07_balance', screen='BALANCE', phase='ACTIVE', timeout=16)
    checkpoint('08_ending_breath', screen='ENDING', phase='BREATH_IN', timeout=16)
    checkpoint('09_ending_actions', screen='ENDING', phase='ACTIONS', timeout=16)
    memory('ending-1')
    assert field(latest(), 'actions') == 'true'
    assert 'visit_completed' in logs()
    assert 'history=MOVE' in logs()

    key(OK, 1.0)
    checkpoint('10_replay', settle=.5, screen='READY')
    assert field(latest(), 'completed') == 'true'
    key(OK, .8)
    wait(screen='REACH', timeout=10)
    wait(screen='ENDING', phase='ACTIONS', timeout=60)
    memory('ending-2')
    assert logs().count('visit_completed') == 1, 'replay duplicated completion'

    key(RIGHT, .3)
    wait(screen='ENDING', focus='world')
    key(OK, .8)
    checkpoint('13_move_home_return', settle=.5, screen='MOVE_HOME')
    key(BACK, 1.5)
    top = resumed()
    assert 'HomeActivity' in top, top
    node = focused_node()
    label = (node.get('content-desc') if node is not None else '') or ''
    assert MOVE in label, ('home focus not restored', label)
    checks.append({'name': '14_home_return', 'resumed': top, 'focus': label})
    print('14_home_return', label, flush=True)
    shot('14_home_return')
    time.sleep(1)
    stop_recording()

    # Multi-replay memory: Ready → Ending three times in one visit.
    focus_move_from_home()
    key(OK, 1.4)
    wait(screen='MOVE_HOME', timeout=12)
    key(OK, 1.0)
    wait(screen='READY')
    before = memory('multi-before')
    series = []
    for i in range(3):
        to_ending_from_ready()
        series.append(memory(f'multi-ending-{i + 1}'))
        if i < 2:
            key(OK, .8)
            wait(screen='READY', timeout=10)
    (OUT / 'logs/multi-replay-memory.json').write_text(json.dumps({'before': before, 'endings': series}, indent=2) + '\n')
    growth = series[-1]['pssKiB'] - series[0]['pssKiB']
    assert growth < 80_000, ('monotonic runaway', series)
    checks.append({'name': 'multi-replay-memory', 'pss': [s['pssKiB'] for s in series], 'growthKiB': growth})

    full = adb('logcat', '-d', '-v', 'threadtime', '-T', epoch)
    assert 'StitchMovePreviewActivity' not in full
    assert not re.search(r'FATAL EXCEPTION|ANR in ru\.familytv|Fatal signal', full)
    assert not re.search(r'\bAI=1\b', logs() + logs('FamilyHome:I'))
    checks.append({'name': 'production-only-no-crash-no-ai', 'ai': 0, 'debugEntry': False})
    print('PASS', len(checks), 'checks', flush=True)
finally:
    stop_recording()
    full = adb('logcat', '-d', '-v', 'threadtime', '-T', epoch)
    (OUT / 'logs/state.log').write_text(logs())
    (OUT / 'logs/logcat.txt').write_text('\n'.join(s for s in full.splitlines() if any(t in s for t in
        ['FamilyMove', 'FamilyHome', 'VoiceRuntime', 'ru.familytv', 'AndroidRuntime', 'FATAL EXCEPTION', 'Fatal signal', 'ANR'])) + '\n')
    (OUT / 'logs/checks.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2) + '\n')
    (OUT / 'logs/performance.json').write_text(json.dumps(measurements, indent=2) + '\n')
    stores_after = {name: store(name) for name in STORES}
    (OUT / 'logs/stores-delta.json').write_text(json.dumps({
        name: {'changed': stores_before[name] != stores_after[name], 'afterHasMove': 'MOVE' in stores_after[name] or 'Разбудим' in stores_after[name]}
        for name in STORES
    }, ensure_ascii=False, indent=2) + '\n')
