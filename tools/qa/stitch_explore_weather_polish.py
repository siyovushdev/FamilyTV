#!/usr/bin/env python3
"""H728 D-pad acceptance of the Explore/Weather UX polish: one Lumi and Ask Lumi gating.

Same rules as stitch_explore_weather.py: no app-data reset, no state injection, no animation-scale
changes. Output goes to ux-polish/ and never replaces the full android-preview evidence.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/design/stitch/explore-weather-v1/ux-polish'
parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='192.168.68.105:5555')
args = parser.parse_args()
ADB = ['adb', '-s', args.serial]
COMPONENT = 'ru.familytv/.explore.stitch.StitchExploreWeatherPreviewActivity'
NEXT = 'Спрятать лёд от солнца'
EXPLANATION = 'Луми объясняет: солнце, тепло, лёд, вода'
VIDEO = 'explore-weather-polish-ask-lumi.mp4'
checks = []
recording = None
for directory in ['logs', 'screenshots', 'recordings']:
    (OUT / directory).mkdir(parents=True, exist_ok=True)


def adb(*parts, binary=False, check=True, timeout=30):
    result = subprocess.run(ADB + list(parts), capture_output=True, check=check, timeout=timeout)
    return result.stdout if binary else result.stdout.decode(errors='replace')


def preference_hashes():
    names = adb('shell', 'run-as', 'ru.familytv', 'ls', 'shared_prefs').splitlines()
    return {name: hashlib.sha256(adb('exec-out', 'run-as', 'ru.familytv', 'cat', 'shared_prefs/' + name, binary=True)).hexdigest()
            for name in names if name.endswith('.xml')}


def logs(tag='StitchWeather:I'):
    return adb('logcat', '-d', '-v', 'threadtime', '-T', epoch, '-s', tag)


def state_lines():
    return [line for line in logs().splitlines() if 'screen=' in line]


def latest():
    lines = state_lines()
    assert lines, 'No debug preview log'
    return lines[-1]


def matches(line, fields):
    return all(re.search(r'\b' + k + '=' + re.escape(str(v)) + r'(?:\s|$)', line) for k, v in fields.items())


def wait(timeout=24, **fields):
    deadline = time.monotonic() + timeout
    line = ''
    while time.monotonic() < deadline:
        line = (state_lines() or [''])[-1]
        if line and matches(line, fields):
            return line
        time.sleep(.08)
    raise AssertionError(('state timeout', fields, line))


def key(code, delay=.24):
    adb('shell', 'input', 'keyevent', str(code))
    time.sleep(delay)


def shot(name):
    (OUT / 'screenshots' / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))


def checkpoint(name, capture=True, settle=0.0, **fields):
    state = wait(**fields)
    assert 'AI=0' in state, state
    if settle: time.sleep(settle)
    checks.append({'name': name, 'state': state})
    print(name, state, flush=True)
    if capture:
        shot(name)
    return state


def premature_select(name, cause, since, then=None):
    """OK while the explanation runs: must be consumed, never open Build & Test or skip ahead.

    `since` is the checkpoint line that proved the running stage; the key is sent at once and the
    log is inspected afterwards, so slow ADB round-trips cannot push the press past DONE.
    """
    assert 'screen=ASK' in since and 'canBuild=false' in since, since
    key(23, .1)
    if then: then()
    time.sleep(.3)
    lines = state_lines()
    after = lines[lines.index(since) + 1:]
    selects = [line for line in after if 'action=Select' in line]
    assert selects, (name, after)
    assert all(matches(line, {'screen': 'ASK', 'canBuild': 'false'}) for line in selects), (name, selects)
    assert not any('screen=BUILD' in line for line in after), (name, after)
    state = latest()
    assert 'screen=ASK' in state and ('canBuild=true' in state or 'focus=build' not in state), state
    checks.append({'name': f'premature-select-{name}', 'cause': cause, 'select': selects[-1], 'after': state, 'buildOpened': False})
    print('premature select rejected', cause, flush=True)


def assert_sequence(name, start, end):
    """Every logged ASK state between two checkpoints: fixed cause order, next locked until DONE."""
    lines = state_lines()
    span = lines[lines.index(start):lines.index(end) + 1]
    assert all('screen=ASK' in line for line in span), (name, span)
    order = []
    for line in span:
        cause = re.search(r' cause=(\w+) beat=', line)
        if not order or order[-1] != cause[1]: order.append(cause[1])
        assert ('canBuild=true' in line) == (cause[1] == 'DONE'), (name, line)
    assert order == ['SUN', 'HEAT', 'ICE', 'WATER', 'DONE'], (name, order)
    checks.append({'name': f'sequence-{name}', 'order': order, 'loggedStates': len(span), 'nextLockedUntilDone': True})


def memory(name):
    content = adb('shell', 'dumpsys', 'meminfo', 'ru.familytv')
    (OUT / 'logs' / f'pss-{name}.txt').write_text(content)
    match = re.search(r'TOTAL PSS:\s*(\d+)', content) or re.search(r'^\s*TOTAL\s+(\d+)', content, re.M)
    assert match, content
    pid = adb('shell', 'pidof', 'ru.familytv').strip()
    mappings = adb('shell', 'run-as', 'ru.familytv', 'cat', f'/proc/{pid}/maps')
    assert not re.search(r'\.(gguf|onnx)(?:\s|$)', mappings, re.I), name
    checks.append({'name': f'memory-{name}', 'pid': pid, 'pssKiB': int(match[1]), 'aiMappings': 0})


def accessibility(name, focus=None, present=(), absent=()):
    remote = '/sdcard/familytv-weather-ui.xml'
    adb('shell', 'uiautomator', 'dump', remote)
    content = adb('shell', 'cat', remote)
    (OUT / 'logs' / f'accessibility-{name}.xml').write_text(content)
    nodes = list(ET.fromstring(content).iter('node'))
    focused = [n.get('content-desc') for n in nodes if n.get('focused') == 'true']
    descriptions = [n.get('content-desc') for n in nodes]
    focusable = [n.get('content-desc') for n in nodes if n.get('focusable') == 'true']
    if focus: assert focus in focused, (name, focused)
    for text in present: assert text in focusable, (name, text, focusable)
    for text in absent: assert text not in descriptions, (name, text, descriptions)
    for node in nodes:
        if node.get('focusable') == 'true':
            l, t, r, b = map(int, re.findall(r'\d+', node.get('bounds')))
            assert 88 <= l < r <= 1832 and 72 <= t < b <= 1008, (name, node.attrib)
    checks.append({'name': f'accessibility-{name}', 'focus': focused, 'focusable': focusable, 'absent': list(absent)})


def pause_resume(name):
    before = latest()
    pid_before = adb('shell', 'pidof', 'ru.familytv').strip()
    adb('shell', 'am', 'start', '-f', '0x58000000', '-a', 'android.settings.SETTINGS')
    paused = wait(paused='true', screen='ASK')
    time.sleep(1.3)
    assert latest() == paused, 'explanation advanced while paused'
    assert 'canBuild=false' in paused, paused
    key(4, .5)
    after = wait(paused='false', screen='ASK')
    assert adb('shell', 'pidof', 'ru.familytv').strip() == pid_before
    assert re.search(r'cause=(\S+)', paused)[1] == re.search(r'cause=(\S+)', after)[1], (paused, after)
    assert 'canBuild=false' in after and 'focus=build' not in after, after
    assert re.search(r'focus=(\S+)', before)[1] == re.search(r'focus=(\S+)', after)[1], (before, after)
    checks.append({'name': f'pause-{name}', 'before': before, 'paused': paused, 'resumed': after, 'pidPreserved': True})
    shot('extra_pause_resume_' + name)


def stop_recording():
    global recording
    if recording is None: return
    pid = adb('shell', 'pidof', 'screenrecord', check=False).strip()
    if pid: adb('shell', 'kill', '-2', pid, check=False)
    try: recording.communicate(timeout=10)
    except subprocess.TimeoutExpired: recording.terminate(); recording.communicate(timeout=5)
    recording = None
    time.sleep(1)
    adb('pull', '/sdcard/' + VIDEO, str(OUT / 'recordings' / VIDEO), timeout=60)


adb('shell', 'am', 'force-stop', 'ru.familytv')
adb('logcat', '-c', check=False)
epoch = adb('shell', 'date', "'+%m-%d %H:%M:%S.000'").strip()
before_prefs = preference_hashes()
(OUT / 'logs/preferences-before.json').write_text(json.dumps(before_prefs, indent=2))
(OUT / 'logs/device.txt').write_text('model=' + adb('shell', 'getprop', 'ro.product.model') + 'android=' + adb('shell', 'getprop', 'ro.build.version.release')
                                     + adb('shell', 'wm', 'size') + adb('shell', 'wm', 'density') + 'animator_duration_scale=' + adb('shell', 'settings', 'get', 'global', 'animator_duration_scale'))
adb('shell', 'am', 'start', '-n', COMPONENT)
wait(screen='HOME', focus='destination:WEATHER')
try:
    time.sleep(.8)
    key(23)
    checkpoint('01_intro_one_lumi', settle=.8, screen='INTRO', focus='start')
    memory('weather-intro')
    key(23)
    checkpoint('02_prediction_one_lumi', settle=.8, screen='PREDICTION', focus='place:SUN')
    accessibility('prediction', 'Положить лёд на солнце. Предположение')
    key(23, .3)
    wait(screen='EXPERIMENT')
    # Same deterministic clock; "Ускорить время" only shortens the wait before the polished stage.
    key(23, .3); key(23, .3)
    wait(screen='RESULT', focus='why')
    recording = subprocess.Popen(ADB + ['shell', 'screenrecord', '--size', '1920x1080', '--bit-rate', '6000000', '--time-limit', '120', '/sdcard/' + VIDEO],
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    time.sleep(2.2)
    checkpoint('result_before_why', capture=False, screen='RESULT', focus='why')
    key(23, .12)
    entry = checkpoint('ask_entry', capture=False, screen='ASK', cause='SUN', canBuild='false', focus='explanation:CHAIN')
    premature_select('entry', 'SUN', entry, then=lambda: shot('03_ask_lumi_running'))
    for cause in ['HEAT', 'ICE', 'WATER']:
        stage = checkpoint(f'ask_{cause.lower()}', capture=False, screen='ASK', cause=cause, canBuild='false')
        premature_select(cause.lower(), cause, stage, then=(lambda: shot('04_ask_lumi_water')) if cause == 'WATER' else None)
    first_done = checkpoint('05_ask_lumi_done', settle=.6, screen='ASK', cause='DONE', canBuild='true', focus='build')
    assert_sequence('first', entry, first_done)
    accessibility('ask-done', NEXT, present=[NEXT], absent=[EXPLANATION])
    time.sleep(1.2)
    key(23, .5)
    checkpoint('06_build_after_done', settle=.5, screen='BUILD_CHOOSE', focus='shelter:CANOPY')
    key(4, .6)
    checkpoint('back_to_done', capture=False, screen='ASK', cause='DONE', canBuild='true', focus='build')
    time.sleep(1)
    # Repeat after DONE through the real remote: RIGHT to Repeat, OK.
    key(22, .4)
    wait(screen='ASK', focus='repeat', canBuild='true')
    key(23, .4)
    repeat_start = checkpoint('07_repeat_blocks_next', settle=.2, screen='ASK', cause='SUN', canBuild='false', focus='repeat')
    # Back up to the explanation itself, then prove OK there does not skip or restart.
    key(19, .3)
    premature_select('repeat', None, wait(screen='ASK', focus='explanation:CHAIN', canBuild='false'))
    done = checkpoint('08_repeat_done', settle=.6, screen='ASK', cause='DONE', canBuild='true', focus='build')
    assert_sequence('repeat', repeat_start, done)
    time.sleep(1.5)
    stop_recording()

    # Outside the recording: accessibility of a running explanation, then pause during it.
    key(22); key(23)
    wait(screen='ASK', cause='SUN', canBuild='false')
    key(19); wait(screen='ASK', focus='explanation:CHAIN')
    accessibility('ask-running', EXPLANATION, present=[EXPLANATION], absent=[NEXT])
    pause_resume('explanation')
    checkpoint('pause_resume_done', capture=False, screen='ASK', cause='DONE', canBuild='true', focus='build')
    memory('ask-lumi')
    # Back chain unchanged: Ask -> Result -> Intro -> Explore.
    key(4); checkpoint('back_ask_to_result', capture=False, screen='RESULT', focus='why')
    key(4); checkpoint('back_result_to_intro', capture=False, screen='INTRO')
    key(4); checkpoint('back_intro_to_home', capture=False, screen='HOME', focus='destination:WEATHER')
finally:
    stop_recording()
    (OUT / 'logs/state.log').write_text(logs())
    (OUT / 'logs/android-runtime.log').write_text(logs('AndroidRuntime:E'))
    (OUT / 'logs/activity-manager.log').write_text(logs('ActivityManager:I'))
    (OUT / 'logs/checks.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2) + '\n')
    after_prefs = preference_hashes()
    (OUT / 'logs/preferences-after.json').write_text(json.dumps(after_prefs, indent=2))
    assert before_prefs == after_prefs, ('Production preference mutation', before_prefs, after_prefs)
    assert 'FATAL EXCEPTION' not in (OUT / 'logs/android-runtime.log').read_text()
    assert not re.search(r'ANR in ru.familytv|Process ru.familytv .* has died', (OUT / 'logs/activity-manager.log').read_text())
    state_log = (OUT / 'logs/state.log').read_text()
    assert 'AI=1' not in state_log
    leaked = [l for l in state_log.splitlines() if 'screen=ASK' in l and 'canBuild=false' in l and 'focus=build' in l]
    assert not leaked, leaked
    print(f'{len(checks)} device checks; preference hashes unchanged; AI=0; no model mappings / crash / ANR', flush=True)
