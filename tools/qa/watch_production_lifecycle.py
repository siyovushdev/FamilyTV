#!/usr/bin/env python3
"""Resume the real production player after an external Activity, without changing settings/data.
Run after watch_production_acceptance.py, which leaves Home focused on Watch.
"""
import argparse
import json
import re
import subprocess
import time
from pathlib import Path
p = argparse.ArgumentParser(); p.add_argument('--device', required=True); a = p.parse_args()
ADB = ['adb', '-s', a.device]
OUT = Path(__file__).resolve().parents[2] / 'docs/v1/watch-production'
PKG = 'ru.familytv'
def adb(*args, binary=False):
    b = subprocess.run(ADB + list(args), capture_output=True, check=True, timeout=45).stdout
    return b if binary else b.decode(errors='replace')
def key(k, delay=.5):
    adb('shell', 'input', 'keyevent', 'KEYCODE_' + k); time.sleep(delay)
def logs():
    return adb('logcat', '-d', '--pid=' + adb('shell', 'pidof', PKG).strip(), '-v', 'threadtime')
def resumed():
    return re.search(r'(?:topResumedActivity|mResumedActivity).*', adb('shell', 'dumpsys', 'activity', 'activities'))[0]
assert 'h728' in adb('shell', 'getprop', 'ro.soc.model').lower()
adb('shell', 'am', 'start', '-W', '-n', PKG + '/.home.HomeActivity'); time.sleep(.7)
for _ in range(3): key('DPAD_CENTER')
key('DPAD_CENTER', .7); key('MEDIA_PAUSE', 2)
assert 'VideoPlayerActivity' in resumed(), resumed()
key('MEDIA_PLAY', .9); key('MEDIA_PAUSE', .2)
before = logs()
start = adb('shell', 'am', 'start', '-W', '-a', 'android.settings.SETTINGS')
(OUT / 'logs/lifecycle-background-start.txt').write_text(start)
time.sleep(.5)
assert 'VideoPlayerActivity' not in resumed(), resumed()
key('BACK', 2)
assert 'VideoPlayerActivity' in resumed(), resumed()
after = logs()
new = after[len(before):] if after.startswith(before) else after
released = re.findall(r'released id=video-warm positionMs=(\d+) playing=(\w+)', new)
requests = re.findall(r'resume_request id=video-warm positionMs=(\d+)', new)
ready = re.findall(r'ready id=video-warm positionMs=(\d+)', new)
assert released and requests and ready, new
position = int(released[-1][0])
assert 500 < position < 2500, position
assert released[-1][1] == 'false', released
assert int(requests[-1]) == position, (released, requests)
assert abs(int(ready[-1]) - position) < 150, (position, ready)
assert not re.search(r'FamilyPlayer: playing=true|playback_error|FATAL EXCEPTION', new), new
(OUT / 'screenshots/13_player_lifecycle_paused.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
(OUT / 'logs/lifecycle-logcat.txt').write_text(new)
(OUT / 'logs/lifecycle-result.json').write_text(json.dumps({'released_position_ms': position,
    'resume_request_ms': int(requests[-1]), 'ready_position_ms': int(ready[-1]),
    'paused_preserved': True, 'same_production_activity': True}, indent=2))
for _ in range(4): key('BACK')
assert 'ru.familytv/.home.HomeActivity' in resumed(), resumed()
print('PASS lifecycle release/recreate: saved and requested', position, 'ready', ready[-1], 'paused retained; returned production Home.', flush=True)
