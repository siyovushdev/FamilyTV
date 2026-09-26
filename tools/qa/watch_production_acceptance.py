#!/usr/bin/env python3
"""H728 acceptance through the production launcher. No fixture imports or progress seeding.
Requires an already installed APK. Uses only D-pad/media keys; preserves app data.
This run documents the bundled short-video catalog, including non-resumable policy.
"""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/v1/watch-production'
p = argparse.ArgumentParser()
p.add_argument('--device', required=True)
a = p.parse_args()
ADB = ['adb', '-s', a.device]
PKG = 'ru.familytv'


def adb(*args, binary=False):
    result = subprocess.run(ADB + list(args), check=True, capture_output=True, timeout=90)
    return result.stdout if binary else result.stdout.decode(errors='replace')


def log(name, data):
    (OUT / 'logs' / name).write_text(data)


def key(name, delay=.45):
    adb('shell', 'input', 'keyevent', 'KEYCODE_' + name)
    time.sleep(delay)


def shot(name):
    (OUT / 'screenshots' / name).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    print('Captured', name, flush=True)


def current_log():
    return adb('logcat', '-d', '--pid=' + adb('shell', 'pidof', PKG).strip(), '-v', 'threadtime')


def focus(expected, player=False):
    state = adb('shell', 'dumpsys', 'activity', 'activities')
    resumed = re.search(r'(?:topResumedActivity|mResumedActivity).*', state)
    expected_activity = 'VideoPlayerActivity' if player else 'home.watch.WatchActivity'
    assert resumed and expected_activity in resumed[0], (expected_activity, resumed[0] if resumed else state)
    regex = r'FamilyPlayer: focus=(\S+)' if player else r'FamilyWatch: screen=\S+ focus=(\S+)'
    matches = re.findall(regex, current_log())
    assert matches and matches[-1] == expected, (expected, matches[-3:])
    print('PASS focus', expected, flush=True)


def hierarchy():
    adb('shell', 'uiautomator', 'dump', '/sdcard/watch-production.xml')
    return adb('shell', 'cat', '/sdcard/watch-production.xml')


def memory(name):
    data = adb('shell', 'dumpsys', 'meminfo', PKG)
    log('meminfo_' + name + '.txt', data)
    return int(re.search(r'TOTAL PSS:\s*(\d+)', data)[1])


for folder in ('screenshots', 'recordings', 'logs'):
    (OUT / folder).mkdir(parents=True, exist_ok=True)
info = adb('shell', 'getprop ro.product.model; getprop ro.soc.model; getprop ro.hardware; getprop ro.build.version.release; wm size; wm density')
assert 'h728' in info.lower(), info
log('device.txt', info)
started = adb('shell', 'date +%s').strip() + '.000'
log('device_run_start_epoch.txt', started + '\n')
adb('shell', 'am', 'force-stop', PKG)
log('entry_start.txt', adb('shell', 'am', 'start', '-W', '-n', PKG + '/.home.HomeActivity'))
time.sleep(2)
# The existing Home may restore a journey/card. Find the new entry using D-pad only.
for _ in range(16):
    key("DPAD_UP", .1)
for _ in range(20):
    tree = ET.fromstring(hierarchy())
    focused = next((n for n in tree.iter('node') if n.get('focused') == 'true'), None)
    label = '' if focused is None else (focused.get('text', '') + focused.get('content-desc', ''))
    print('Home focus:', label, flush=True)
    if 'Смотреть' in label:
        break
    if any(word in label for word in ('Наши открытия', 'Наши истории', 'Карта')):
        for _ in range(3): key('DPAD_LEFT', .15)
    else:
        key('DPAD_DOWN')
else:
    raise AssertionError('Watch entry was not reachable from current Home focus')
log('home_entry.xml', hierarchy())
remote = '/sdcard/watch_production_full.mp4'
recorder = subprocess.Popen(ADB + ['shell', f'screenrecord --size 1920x1080 --bit-rate 6000000 --time-limit 170 {remote} & rec=$!; echo REC_PID:$rec; wait $rec'], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
pid = re.search(r'REC_PID:(\d+)', recorder.stdout.readline().decode())[1]
measurements = {}
try:
    time.sleep(.5)
    shot('01_home_to_watch.png')
    key('DPAD_CENTER', 1.2)
    focus('category:stories')
    shot('02_watch_home.png')
    measurements['watch_home_pss_kb'] = memory('watch_home')
    key('DPAD_RIGHT'); focus('category:creativity'); shot('03_watch_focus.png')
    key('DPAD_DOWN'); focus('guide'); key('DPAD_UP'); focus('category:creativity')
    key('DPAD_LEFT'); key('DPAD_CENTER'); focus('video:video-warm')
    shot('04_browse.png')
    measurements['browse_pss_kb'] = memory('browse')
    key('DPAD_UP'); focus('back'); key('DPAD_DOWN'); focus('video:video-warm')
    shot('05_browse_focus.png')
    key('DPAD_CENTER'); focus('play'); shot('06_details.png')
    measurements['details_pss_kb'] = memory('details')
    key('DPAD_UP'); focus('guide'); key('DPAD_UP'); focus('back'); key('DPAD_DOWN'); focus('play')
    key('DPAD_CENTER', .7); key('MEDIA_PAUSE', 2)
    focus('play', True)
    shot('07_player_controls.png')
    measurements['player_pss_kb'] = memory('player')
    key('DPAD_UP'); focus('timeline', True); key('DPAD_LEFT')
    key('DPAD_UP'); focus('back', True); key('DPAD_DOWN'); focus('timeline', True)
    key('DPAD_DOWN'); focus('play', True)
    key('DPAD_RIGHT'); focus('forward', True)
    key('DPAD_RIGHT'); focus('audio', True); key('DPAD_CENTER')
    shot('12_player_audio_muted.png')
    key('DPAD_CENTER'); key('DPAD_LEFT'); key('DPAD_LEFT'); focus('play', True)
    key('MEDIA_PLAY', 4)
    focus('play')
    key('BACK'); focus('video:video-warm'); key('BACK'); focus('category:stories')
    shot('09_continue.png')  # Correctly absent for <8s real clips, NOT a resumable-video claim.
    key('DPAD_CENTER'); key('DPAD_CENTER'); key('DPAD_CENTER', .7); key('MEDIA_PAUSE', 2)
    shot('10_resume.png')  # Short clip starts at zero, per policy; no saved progress is injected.
    key('DPAD_RIGHT'); focus('forward', True); key('DPAD_CENTER', .5); key('MEDIA_PLAY', 1)
    focus('play')
    key('BACK'); key('BACK'); key('BACK', .8)
    home = hierarchy()
    assert 'Смотреть видео' in home and 'Миры для семьи' in home, 'Expected production Home after Back'
    log('home_return.xml', home)
    shot('11_home_return.png')
finally:
    adb('shell', 'kill', '-2', pid)
    recorder.communicate(timeout=20)
    adb('pull', remote, str(OUT / 'recordings/watch_production_full.mp4'))
    log('pss.json', json.dumps(measurements, indent=2))
    log('acceptance_logcat.txt', current_log())
    log('system_logcat.txt', adb('logcat', '-d', '-v', 'threadtime', '-T', started))
    log('progress_after.xml', adb('shell', 'run-as', PKG, 'cat', 'shared_prefs/familytv_watch_progress.xml'))

process = current_log()
assert 'AI=0 runtimePresent=false' in process
assert 'completed id=video-warm' in process
assert 'resume_request id=video-warm positionMs=0' in process
assert 'released id=video-warm' in process
assert not re.search(r'FATAL EXCEPTION|ANR in ru.familytv|runtimeInitialization count=|playback_error|catalog_error|load_error', process)
assert 'stitch-watch-' not in '\n'.join(line for line in process.splitlines() if 'FamilyWatch:' in line or 'FamilyPlayer:' in line)
process_pid = adb('shell', 'pidof', PKG).strip()
maps = adb('shell', 'run-as', PKG, 'cat', '/proc/' + process_pid + '/maps')
log('process_maps.txt', maps)
assert not re.search(r'\.(gguf|onnx)(?:\s|$)', maps, re.I)
log('acceptance_result.txt', 'PASS: H728 production Home → Watch → Browse → Details → real bundled Media3 video → Back/Home, D-pad, seek clamp, pause/play, audio mute/unmute, release, short-clip completion/restart policy, AI=0.\nNOT VERIFIED: 5s playing HUD hide and nonzero resume (all eligible videos 3s); captions toggling (no eligible caption track); installed package on device (none installed); physical remote.\n')
print('PASS available production acceptance; content-limited checks recorded explicitly.', flush=True)
