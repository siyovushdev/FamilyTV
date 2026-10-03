#!/usr/bin/env python3
"""H728 native Space Expedition capture vs accepted WebView goldens.

Does not overwrite docs/v1/space-expedition-visual-layer/.
Starts only HomeActivity. Does not reset app data. No commit.
"""
import json
import re
import subprocess
import time
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/v1/space-expedition-native'
GOLDEN = ROOT / 'docs/v1/space-expedition-visual-layer/android'
ADB = ['adb', '-s', '192.168.68.105:5555']
PKG = 'ru.familytv'
HOME = PKG + '/.home.HomeActivity'
SPACE = 'SpaceExpeditionActivity'
VIDEO = 'space-expedition-native.mp4'
TAG = 'FamilySpace:I'
HOME_TAG = 'FamilyHome:I'
LEFT, RIGHT, UP, DOWN, OK, BACK = 21, 22, 19, 20, 23, 4
FORBIDDEN = (
    'StitchHomePreview', 'StitchWatchPreview', 'StitchGamesPreview',
    'StitchExplore', 'StitchStory', 'StitchMovePreview',
)
WEBVIEW_MARKERS = (
    'libwebviewchromium', 'webview.apk', '/app_webview',
    'webview_data', 'WebViewGoogle', 'trichromelibrary',
)
PAIRS = [
    ('07_entry', '07_entry.png', 'Entry'),
    ('05_journey', '05_journey.png', 'Journey'),
    ('00_challenge', '00_challenge_active_accepted.png', 'Challenge'),
    ('02_help', '02_help.png', 'Help'),
    ('03_wrong', '03_wrong.png', 'Wrong'),
    ('01_success', '01_success_install.png', 'Success'),
    ('06_updated', '06_updated.png', 'Updated Journey'),
    ('04_intro', '04_intro.png', 'Intro'),
]
checks, measurements = [], []
recording = None
for directory in ['logs', 'android', 'recordings', 'comparisons']:
    (OUT / directory).mkdir(parents=True, exist_ok=True)


def adb(*parts, binary=False, check=True, timeout=40):
    result = subprocess.run(ADB + list(parts), capture_output=True, check=check, timeout=timeout)
    return result.stdout if binary else result.stdout.decode(errors='replace')


def key(code, delay=.5):
    adb('shell', 'input', 'keyevent', str(code))
    time.sleep(delay)


def shot(name):
    path = OUT / 'android' / (name + '.png')
    path.write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    print('Captured', name, path.stat().st_size, flush=True)
    return path


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


def wait_space(screen=None, timeout=14):
    deadline = time.monotonic() + timeout
    actual = None
    while time.monotonic() < deadline:
        if SPACE not in resumed():
            time.sleep(.12)
            continue
        actual = last_space()
        if actual and (screen is None or actual[0] == screen):
            return actual
        time.sleep(.12)
    raise AssertionError(('timeout', screen, actual, resumed(), logs()[-1500:]))


def set_scene(scene):
    adb('shell', 'am', 'force-stop', PKG)
    time.sleep(.3)
    prefs = adb('shell', 'run-as', PKG, 'cat', 'shared_prefs/familytv_adventure_progress.xml')
    for old in ('intro', 'launch_repair', 'launch_flight', 'nav_maze', 'repair_pattern', 'planet_sort', 'science_talk'):
        prefs = prefs.replace(f'>{old}<', f'>{scene}<')
    tmp = Path('/tmp/familytv_adventure_progress.xml')
    tmp.write_text(prefs)
    adb('push', str(tmp), '/data/local/tmp/familytv_adventure_progress.xml')
    adb('shell', 'cat /data/local/tmp/familytv_adventure_progress.xml | run-as ru.familytv tee shared_prefs/familytv_adventure_progress.xml >/dev/null')
    line = [ln for ln in adb('shell', 'run-as', PKG, 'cat', 'shared_prefs/familytv_adventure_progress.xml').splitlines() if 'space-expedition:scene' in ln]
    print('scene', scene, line, flush=True)


def go_watch_mission():
    adb('shell', 'am', 'start', '-W', '-n', HOME)
    wait_resumed('home.HomeActivity')
    time.sleep(1.6)
    key(DOWN, .2)
    key(DOWN, .2)
    key(UP, .25)
    for _ in range(6):
        key(LEFT, .1)
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        if last_home_focus() == 'watch':
            break
        time.sleep(.08)
    key(DOWN, .7)
    key(OK, 1.3)


def focus_launch():
    for _ in range(12):
        cur = last_space()
        if cur and cur[1] == 'node:launch':
            return cur
        key(LEFT, .2)
    return last_space()


def ki(content, pattern):
    match = re.search(pattern, content, re.M | re.I)
    return int(match.group(1)) if match else None


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
    hits = [marker for marker in WEBVIEW_MARKERS if marker.lower() in mappings.lower()]
    html_hits = re.findall(r'space_expedition_v1/\S+\.html', mappings)
    space = last_space()
    sample = {
        'surface': label,
        'pid': int(pid) if pid else 0,
        'pssKiB': int(match[1]),
        'graphicsKiB': ki(content, r'Graphics:\s+(\d+)'),
        'glKiB': ki(content, r'GL mtrack:\s+(\d+)'),
        'eglKiB': ki(content, r'EGL mtrack:\s+(\d+)'),
        'views': ki(content, r'Views:\s+(\d+)'),
        'webviewMaps': hits,
        'htmlMaps': html_hits,
        'modelMappings': models,
        'ai': int(space[-1]) if space else 0,
        'space': space,
        'resumed': resumed(),
    }
    measurements.append(sample)
    print('memory', sample, flush=True)
    return sample


def side_by_side(native_name, golden_name, title):
    native = Image.open(OUT / 'android' / (native_name + '.png')).convert('RGB')
    golden = Image.open(GOLDEN / golden_name).convert('RGB')
    h = 540
    nw = int(native.width * h / native.height)
    gw = int(golden.width * h / golden.height)
    sheet = Image.new('RGB', (gw + nw + 48, h + 64), (12, 16, 32))
    sheet.paste(golden.resize((gw, h)), (16, 48))
    sheet.paste(native.resize((nw, h)), (32 + gw, 48))
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.load_default()
    draw.text((16, 12), f'{title}  ·  GOLDEN WebView', fill=(187, 202, 200), font=font)
    draw.text((32 + gw, 12), f'{title}  ·  NATIVE H728', fill=(95, 246, 238), font=font)
    path = OUT / 'comparisons' / (native_name + '.png')
    sheet.save(path, quality=90)
    print('comparison', path, flush=True)


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
set_scene('repair_pattern')
time.sleep(.3)
recording = subprocess.Popen(
    ADB + ['shell', 'screenrecord', '--size', '1920x1080', '--bit-rate', '8000000', '--time-limit', '180', '/sdcard/' + VIDEO],
    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
)
time.sleep(1)
go_watch_mission()
entry = wait_space('ENTRY')
assert entry[-1] == '0', entry
time.sleep(0.4)
shot('07_entry')
memory('entry')
checks.append({'name': 'entry', 'space': entry})

key(OK, 0.9)
journey = wait_space('JOURNEY')
assert journey[-1] == '0', journey
time.sleep(0.3)
shot('05_journey')
memory('journey')
checks.append({'name': 'journey', 'space': journey})

print('journey-focus', focus_launch(), flush=True)
key(OK, 1.1)
challenge = wait_space('CHALLENGE')
assert challenge[4] == 'PATTERN_SEQUENCE', challenge
assert challenge[-1] == '0', challenge
time.sleep(0.35)
shot('00_challenge')
memory('challenge')
checks.append({'name': 'challenge', 'space': challenge})

key(UP, .35)
key(OK, .45)
help_state = wait_space('CHALLENGE')
assert help_state[5] == 'true', help_state
time.sleep(0.2)
shot('02_help')
memory('help')
checks.append({'name': 'help', 'space': help_state})
key(OK, .45)
time.sleep(.35)

for _ in range(6):
    cur = last_space()
    if cur and cur[1] == 'opt:5':
        break
    if cur and cur[1] == 'help':
        key(DOWN, .2)
        continue
    key(LEFT, .18)
print('wrong-focus', last_space(), flush=True)
key(OK, .4)
wrong = wait_space('CHALLENGE')
assert wrong[0] == 'CHALLENGE', wrong
shot('03_wrong')
checks.append({'name': 'wrong', 'space': wrong})
time.sleep(1.2)

for _ in range(6):
    cur = last_space()
    if cur and cur[1] == 'opt:6':
        break
    key(RIGHT, .18)
print('correct-focus', last_space(), flush=True)
key(OK, .25)
success = wait_space('SUCCESS', timeout=6)
time.sleep(0.2)
shot('01_success_install')
time.sleep(0.7)
shot('01_success_ignite')
time.sleep(0.9)
shot('01_success')
memory('success')
checks.append({'name': 'success', 'space': last_space()})

updated = wait_space('JOURNEY', timeout=8)
shot('06_updated')
memory('updated-journey')
checks.append({'name': 'updated-journey', 'space': updated, 'reacted': updated[7]})
assert updated[7] == 'true', updated
assert updated[-1] == '0', updated

key(BACK, .7)
wait_space('ENTRY')
adb('shell', 'am', 'force-stop', PKG)
time.sleep(.3)
set_scene('intro')
time.sleep(.3)
go_watch_mission()
wait_space('ENTRY')
key(OK, 0.9)
wait_space('JOURNEY')
focus_launch()
key(OK, 1.1)
intro = wait_space()
if intro[0] != 'INTRO':
    print('intro-landed', intro, flush=True)
time.sleep(0.3)
shot('04_intro')
memory('intro')
checks.append({'name': 'intro', 'space': last_space()})

fatal = adb('logcat', '-d', '-v', 'threadtime', '-T', epoch, '-s', 'AndroidRuntime:E', 'ActivityManager:I')
anr = 'ANR in ru.familytv' in fatal or 'FATAL EXCEPTION' in fatal
checks.append({'name': 'stability', 'anr_or_fatal': anr})
assert not anr, fatal[-2000:]

webview_html = [m for m in measurements if m.get('htmlMaps')]
webview_app = [m for m in measurements if any('app_webview' in x for x in m.get('webviewMaps') or [])]
checks.append({'name': 'webview-zero', 'htmlMaps': webview_html, 'appWebView': webview_app, 'markers': [m.get('webviewMaps') for m in measurements]})
assert not webview_html, webview_html
assert not webview_app, webview_app

for native_name, golden_name, title in PAIRS:
    native = OUT / 'android' / (native_name + '.png')
    if native.is_file() and (GOLDEN / golden_name).is_file():
        side_by_side(native_name, golden_name, title)

(OUT / 'logs' / 'logcat.txt').write_text(logs() + '\n' + logs(HOME_TAG))
(OUT / 'logs' / 'state.log').write_text(logs())
(OUT / 'logs' / 'checks.json').write_text(json.dumps(checks, indent=2, ensure_ascii=False))
(OUT / 'logs' / 'performance.json').write_text(json.dumps(measurements, indent=2))
stop_recording()
print('PASS', json.dumps({
    'checks': len(checks),
    'pss': [(m['surface'], m['pssKiB'], m.get('graphicsKiB'), m['ai'], m.get('webviewMaps')) for m in measurements],
}, ensure_ascii=False), flush=True)
