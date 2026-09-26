#!/usr/bin/env python3
"""H728 debug Games V2 acceptance. Only remote events; no app data/settings mutation.

Install the debug APK first. Produces unedited screenshots, continuous screenrecord,
activity/focus checks, bounded logcat and memory snapshots. Does not clear logs/data.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/design/stitch/games-v2/android-preview'
parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='192.168.68.105:5555')
args = parser.parse_args()
ADB = ['adb', '-s', args.serial]
COMPONENT = 'ru.familytv/.games.stitch.StitchGamesPreviewActivity'
for folder in ['screenshots', 'recordings', 'logs']:
    (OUT / folder).mkdir(parents=True, exist_ok=True)
checks = []

def adb(*parts, binary=False, timeout=30):
    r = subprocess.run(ADB + list(parts), check=True, capture_output=True, timeout=timeout)
    return r.stdout if binary else r.stdout.decode(errors='replace')

def key(code, delay=.6):
    adb('shell', 'input', 'keyevent', str(code))
    time.sleep(delay)

def checkpoint(name, world, feedback=None, **fields):
    activity = adb('shell', 'dumpsys', 'activity', 'activities')
    resumed = next((s.strip() for s in activity.splitlines() if 'ResumedActivity' in s), '')
    assert '.games.stitch.StitchGamesPreviewActivity' in resumed, resumed
    logs = adb('logcat', '-d', '-v', 'threadtime', '-T', epoch, '-s', 'StitchGames:I')
    latest = [s for s in logs.splitlines() if 'world=' in s][-1]
    expected = dict(world=world, **fields)
    if feedback:
        expected['feedback'] = feedback
    for field, value in expected.items():
        assert f'{field}={value}' in latest, (name, expected, latest)
    checks.append(dict(name=name, resumed=resumed, state=latest))
    print(name, latest, flush=True)
    (OUT / 'screenshots' / f'{name}.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))

def memory(name):
    (OUT / 'logs' / f'memory-{name}.txt').write_text(adb('shell', 'dumpsys', 'meminfo', 'ru.familytv'))

info = {key: adb('shell', 'getprop', value).strip() for key, value in {
    'model': 'ro.product.model', 'hardware': 'ro.hardware', 'android': 'ro.build.version.release', 'abi': 'ro.product.cpu.abi'
}.items()}
info['size'] = adb('shell', 'wm', 'size').strip()
info['density'] = adb('shell', 'wm', 'density').strip()
info['animator_duration_scale'] = adb('shell', 'settings', 'get', 'global', 'animator_duration_scale').strip()
(OUT / 'logs/device.json').write_text(json.dumps(info, indent=2))
epoch = adb('shell', 'date', '+%s').strip() + '.000'
adb('shell', 'am', 'force-stop', 'ru.familytv')
start = adb('shell', 'am', 'start', '-W', '-n', COMPONENT)
(OUT / 'logs/launch.txt').write_text(start)
time.sleep(2)
remote = '/sdcard/familytv-games-v2-preview.mp4'
record_log = (OUT / 'logs/screenrecord.txt').open('wb')
record = subprocess.Popen(ADB + ['shell', 'screenrecord', '--size', '1920x1080', '--bit-rate', '5000000', '--time-limit', '170', remote], stdout=record_log, stderr=subprocess.STDOUT)
time.sleep(1)
try:
    checkpoint('01_games_home', 'HOME')
    memory('home')
    key(23, 1)
    checkpoint('02_math_game', 'MATH', 'READY', combined='false')
    key(23, 2)
    checkpoint('09_math_combined', 'MATH', 'READY', combined='true')
    key(21); key(23, .9)
    checkpoint('04_math_wrong', 'MATH', 'WRONG', round='0')
    key(22)
    checkpoint('12_math_retry_focus', 'MATH', 'WRONG', round='0')
    key(19); key(23, 4)
    checkpoint('03_math_help', 'MATH', 'HELP', round='0')
    key(23, 1)
    checkpoint('05_math_correct', 'MATH', 'CORRECT', round='0')
    memory('math')
    key(4); key(22)
    checkpoint('13_color_home_focus', 'HOME')
    key(23, 1)
    checkpoint('06_color_game', 'COLOR', 'READY', painted='null')
    key(21); key(23, 1.6)
    checkpoint('07_color_wrong', 'COLOR', 'WRONG', painted='RED')
    key(22)
    checkpoint('14_color_retry_focus', 'COLOR', 'WRONG', painted='RED')
    key(23, 1.6)
    checkpoint('08_color_correct', 'COLOR', 'CORRECT', painted='BLUE', launched='false')
    memory('color')
    key(23, .45)
    (OUT / 'screenshots/10_rocket_launch.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    time.sleep(2)
    checkpoint('11_color_after_launch', 'COLOR', 'CORRECT', painted='BLUE', launched='true')
    key(23, 1)
    checkpoint('15_calm_return', 'HOME')
finally:
    pid = adb('shell', 'pidof', 'screenrecord').strip()
    if pid:
        adb('shell', 'kill', '-2', *pid.split())
    record.wait(timeout=20)
    record_log.close()
    adb('pull', remote, str(OUT / 'recordings/games-v2-flow.mp4'), timeout=60)

# A second pass verifies all three math rounds and no automatic progression.
key(21); key(23)
for i in range(3):
    key(23, 1.8)  # combine
    key(23, .8)   # correct count (middle)
    time.sleep(1.3)
    checkpoint(f'16_math_round_{i + 1}_waits', 'MATH', 'CORRECT', round=str(i))
    key(23, .8)   # explicit next, final goes Home
checkpoint('17_math_finite_return', 'HOME')

# Pause/resume retains wrong paint and the focused choice; no second rocket.
key(22); key(23); key(21); key(23, 1.6)
adb('shell', 'am', 'start', '-a', 'android.settings.SETTINGS')
time.sleep(1)
key(4, 1)
checkpoint('18_color_resume', 'COLOR', 'WRONG', painted='RED')
key(20); key(23, 1.2)
checkpoint('20_repeat_retains_scene', 'COLOR', 'WRONG', painted='RED')
key(22); key(19); key(23, .8)
checkpoint('21_color_help_retains_paint', 'COLOR', 'HELP', painted='RED')
# Return with Android Back callback; Home remembers Color card.
key(4)
checkpoint('19_back_restores_color', 'HOME')
key(22); key(23); key(22); key(23)
checkpoint('22_future_worlds_stay_in_preview', 'HOME')
key(21); key(21)  # leave Color selected

logs = adb('logcat', '-d', '-v', 'threadtime', '-T', epoch)
(OUT / 'logs/logcat.txt').write_text('\n'.join(line for line in logs.splitlines() if any(token in line for token in ['StitchGames', 'ru.familytv', 'AndroidRuntime', 'FATAL EXCEPTION', 'Fatal signal'])) + '\n')
(OUT / 'logs/checks.json').write_text(json.dumps(checks, indent=2, ensure_ascii=False))
# /proc maps is best-effort on debug builds; never reads app user content.
map_result = subprocess.run(ADB + ['shell', 'run-as', 'ru.familytv', 'cat', '/proc/' + adb('shell', 'pidof', 'ru.familytv').strip() + '/maps'], capture_output=True)
map_text = map_result.stdout.decode(errors='replace')
(OUT / 'logs/model-mappings.txt').write_text('\n'.join(s for s in map_text.splitlines() if '.gguf' in s or '.onnx' in s) or f'No .gguf/.onnx mappings; read exit={map_result.returncode}\n')
print('Acceptance complete:', len(checks), 'checkpoints', flush=True)
