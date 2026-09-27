#!/usr/bin/env python3
"""H728 D-pad acceptance of the isolated Explore/Weather debug preview.

No app-data reset, production routes, synthetic state injection, or animation-scale changes.
The continuous recording and PNGs are original ADB captures of one real interaction.
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
OUT = ROOT / 'docs/design/stitch/explore-weather-v1/android-preview'
parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='192.168.68.105:5555')
args = parser.parse_args()
ADB = ['adb', '-s', args.serial]
COMPONENT = 'ru.familytv/.explore.stitch.StitchExploreWeatherPreviewActivity'
checks = []
recording = None
for directory in ['logs', 'screenshots', 'recordings']:
    (OUT / directory).mkdir(parents=True, exist_ok=True)


def adb(*parts, binary=False, check=True, timeout=30):
    result = subprocess.run(ADB + list(parts), capture_output=True, check=check, timeout=timeout)
    return result.stdout if binary else result.stdout.decode(errors='replace')


def preference_hashes():
    # Persist hashes only, never household data.
    names = adb('shell', 'run-as', 'ru.familytv', 'ls', 'shared_prefs').splitlines()
    return {name: hashlib.sha256(adb('exec-out', 'run-as', 'ru.familytv', 'cat', 'shared_prefs/' + name, binary=True)).hexdigest()
            for name in names if name.endswith('.xml')}


def logs(tag='StitchWeather:I'):
    return adb('logcat', '-d', '-v', 'threadtime', '-T', epoch, '-s', tag)


def latest():
    lines = [line for line in logs().splitlines() if 'screen=' in line]
    assert lines, 'No debug preview log'
    return lines[-1]


def wait(**fields):
    deadline = time.monotonic() + 24
    while time.monotonic() < deadline:
        line = latest()
        if all(re.search(r'\b' + key + '=' + re.escape(str(value)) + r'(?:\s|$)', line) for key, value in fields.items()):
            return line
        time.sleep(.09)
    raise AssertionError(('state timeout', fields, line))


def key(code, delay=.24):
    adb('shell', 'input', 'keyevent', str(code))
    time.sleep(delay)


def shot(name):
    (OUT / 'screenshots' / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))


def checkpoint(name, capture=True, **fields):
    state = wait(**fields)
    assert 'AI=0' in state, state
    checks.append({'name': name, 'state': state})
    print(name, state, flush=True)
    if capture:
        shot(name)


def memory(name):
    content = adb('shell', 'dumpsys', 'meminfo', 'ru.familytv')
    (OUT / 'logs' / f'pss-{name}.txt').write_text(content)
    match = re.search(r'TOTAL PSS:\s*(\d+)', content) or re.search(r'^\s*TOTAL\s+(\d+)', content, re.M)
    assert match, content
    pid = adb('shell', 'pidof', 'ru.familytv').strip()
    mappings = adb('shell', 'run-as', 'ru.familytv', 'cat', f'/proc/{pid}/maps')
    (OUT / 'logs' / f'maps-{name}.txt').write_text(mappings)
    assert not re.search(r'\.(gguf|onnx)(?:\s|$)', mappings, re.I), name
    checks.append({'name': f'memory-{name}', 'pid': pid, 'pssKiB': int(match[1]), 'aiMappings': 0})


def accessibility(name, focus=None, present=(), absent=()):
    remote = '/sdcard/familytv-weather-ui.xml'
    adb('shell', 'uiautomator', 'dump', remote)
    content = adb('shell', 'cat', remote)
    (OUT / 'logs' / f'accessibility-{name}.xml').write_text(content)
    nodes = list(ET.fromstring(content).iter('node'))
    assert not any(n.get('scrollable') == 'true' for n in nodes)
    focused = [n.get('content-desc') for n in nodes if n.get('focused') == 'true']
    descriptions = [n.get('content-desc') for n in nodes]
    if focus: assert focus in focused, (name, focused)
    for text in present: assert text in descriptions, (name, text, descriptions)
    for text in absent: assert text not in descriptions, (name, text)
    for node in nodes:
        if node.get('focusable') == 'true':
            l, t, r, b = map(int, re.findall(r'\d+', node.get('bounds')))
            assert 88 <= l < r <= 1832 and 72 <= t < b <= 1008, (name, node.attrib)
    checks.append({'name': f'accessibility-{name}', 'focus': focused, 'noScroll': True, 'safeArea': True})


def pause_resume(name, screen):
    before = latest()
    pid_before = adb('shell', 'pidof', 'ru.familytv').strip()
    adb('shell', 'am', 'start', '-f', '0x58000000', '-a', 'android.settings.SETTINGS')
    wait(paused='true', screen=screen)
    paused = latest()
    time.sleep(1.3)
    assert latest() == paused
    key(4, .5)
    after = wait(paused='false', screen=screen)
    assert adb('shell', 'pidof', 'ru.familytv').strip() == pid_before
    before_elapsed = int(re.search(r'elapsed=(\d+)', paused)[1])
    after_elapsed = int(re.search(r'elapsed=(\d+)', after)[1])
    assert after_elapsed <= before_elapsed + 700, (paused, after)
    for field in ['hypothesis', 'shelter', 'focus']:
        assert re.search(r'\b' + field + r'=(\S+)', before)[1] == re.search(r'\b' + field + r'=(\S+)', after)[1]
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
    adb('pull', '/sdcard/explore-weather-v1-flow.mp4', str(OUT / 'recordings/explore-weather-v1-flow.mp4'), timeout=60)


adb('shell', 'am', 'force-stop', 'ru.familytv')
epoch = adb('shell', 'date', "'+%m-%d %H:%M:%S.000'").strip()
before_prefs = preference_hashes()
(OUT / 'logs/preferences-before.json').write_text(json.dumps(before_prefs, indent=2))
(OUT / 'logs/device.txt').write_text(adb('shell', 'getprop') + '\n' + adb('shell', 'wm', 'size') + adb('shell', 'wm', 'density') + 'animator_duration_scale=' + adb('shell', 'settings', 'get', 'global', 'animator_duration_scale'))
adb('shell', 'am', 'start', '-n', COMPONENT)
wait(screen='HOME', focus='destination:WEATHER')
try:
    recording = subprocess.Popen(ADB + ['shell', 'screenrecord', '--size', '1920x1080', '--bit-rate', '6000000', '--time-limit', '170', '/sdcard/explore-weather-v1-flow.mp4'], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    time.sleep(.6)
    checkpoint('01_explore_home', screen='HOME', focus='destination:WEATHER')
    memory('explore-home')
    accessibility('home', 'Погода. Исследовать', present=['Опыты. Скоро, пока недоступно'])
    # Include honest future destinations and the full right side in the same recording.
    key(22); key(22); key(23)
    checkpoint('extra_future_experiments', screen='HOME', focus='destination:EXPERIMENTS')
    key(21); key(21); key(23)
    checkpoint('02_weather_intro', screen='INTRO', focus='start')
    memory('weather-intro')
    key(23)
    checkpoint('03_prediction_sun_focus', screen='PREDICTION', focus='place:SUN')
    key(22)
    checkpoint('04_prediction_shade_focus', screen='PREDICTION', focus='place:SHADE')
    accessibility('prediction', 'Положить лёд в тень. Предположение')
    key(23, .08); key(20, .08)
    checkpoint('extra_ice_placement', screen='PLACING_ICE', hypothesis='SHADE', focus='repeat')
    checkpoint('05_experiment_start', screen='EXPERIMENT', beat='START', hypothesis='SHADE')
    memory('experiment')
    checkpoint('06_experiment_mid', screen='EXPERIMENT', beat='MID')
    key(23)
    checkpoint('07_experiment_result', screen='EXPERIMENT', beat='END')
    checkpoint('08_observation', screen='RESULT', focus='why', hypothesis='SHADE')
    accessibility('observation', 'Почему?')
    key(23, .08)
    checkpoint('09_ask_lumi_sun', screen='ASK', cause='SUN')
    checkpoint('10_ask_lumi_heat', screen='ASK', cause='HEAT')
    checkpoint('11_ask_lumi_ice', screen='ASK', cause='ICE')
    checkpoint('12_ask_lumi_water', screen='ASK', cause='WATER')
    wait(screen='ASK', cause='DONE')
    # Actual repeat through the remote, keeping both results and the hypothesis.
    key(22); key(23, .08)
    checkpoint('extra_repeat_sun', screen='ASK', cause='SUN', focus='repeat', hypothesis='SHADE')
    wait(cause='DONE')
    key(21); key(23)
    checkpoint('13_build_test_choose', screen='BUILD_CHOOSE', focus='shelter:CANOPY', canCheck='false')
    memory('build-test')
    accessibility('build-choose', 'Поставить: Навес', absent=['Проверить'])
    key(23, .08); key(20, .08)
    checkpoint('extra_cover_placement_focus', capture=False, screen='BUILD_PLACED', canCheck='false', focus='repeat')
    checkpoint('14_build_test_placed', screen='BUILD_PLACED', canCheck='true', shelter='CANOPY', focus='check')
    accessibility('build-placed', 'Проверить')
    key(23, 2.5)
    checkpoint('15_build_test_running', screen='BUILD_RUNNING', shelter='CANOPY')
    checkpoint('16_build_test_result', screen='BUILD_RESULT', shelter='CANOPY', focus='change')
    key(4); wait(screen='BUILD_CHOOSE', focus='shelter:CANOPY')
    key(4); wait(screen='ASK', cause='DONE')
    key(4); wait(screen='RESULT')
    key(4); wait(screen='INTRO')
    key(4)
    checkpoint('17_return_explore_home', screen='HOME', focus='destination:WEATHER')
    stop_recording()

    # Additional lifecycle and both-object checks outside the continuous acceptance recording.
    for direction, destination in [(21, 'FOREST'), (21, 'SPACE')]:
        key(direction); key(23); checkpoint('extra_future_' + destination.lower(), screen='HOME', focus='destination:' + destination)
    key(22); key(22); key(22); key(23)
    checkpoint('extra_future_how', screen='HOME', focus='destination:HOW')
    key(21); key(23); key(23); key(21); key(23)
    wait(screen='EXPERIMENT')
    pause_resume('experiment', 'EXPERIMENT')
    checkpoint('extra_sun_hypothesis_result', screen='RESULT', hypothesis='SUN')
    key(23); key(23)
    wait(screen='BUILD_CHOOSE')
    key(22)
    checkpoint('extra_leaves_focus', screen='BUILD_CHOOSE', focus='shelter:LEAVES')
    key(23, 2)
    pause_resume('placed', 'BUILD_PLACED')
    checkpoint('extra_leaves_placed', screen='BUILD_PLACED', shelter='LEAVES', canCheck='true')
    key(23); pause_resume('build-running', 'BUILD_RUNNING')
    checkpoint('extra_leaves_result', screen='BUILD_RESULT', shelter='LEAVES')
    key(4); key(4); key(22); key(23)
    wait(screen='ASK', cause='SUN')
    pause_resume('explanation', 'ASK')
    wait(cause='DONE')
    key(4); key(4); key(4)
    wait(screen='HOME')
    accessibility('returned-home', 'Погода. Исследовать')
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
    assert 'AI=1' not in (OUT / 'logs/state.log').read_text()
    print(f'{len(checks)} device checks; preference hashes unchanged; AI=0; no model mappings / crash / ANR', flush=True)
