#!/usr/bin/env python3
"""Short H728 routing check: Home -> Исследовать -> Космос -> Back -> Лес -> Back -> Погода.

Enters through HomeActivity only. ADB keyevents stand in for the physical remote (NOT VERIFIED).
No app-data reset, route intent, state injection or animation-scale change.
"""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/v1/explore-routing'
parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='192.168.68.105:5555')
args = parser.parse_args()
ADB = ['adb', '-s', args.serial]
HOME = 'ru.familytv/.home.HomeActivity'
checks = []
for directory in ['logs', 'screenshots']:
    (OUT / directory).mkdir(parents=True, exist_ok=True)


def adb(*parts, binary=False, check=True, timeout=30):
    result = subprocess.run(ADB + list(parts), capture_output=True, check=check, timeout=timeout)
    return result.stdout if binary else result.stdout.decode(errors='replace')


def logs(*tags):
    return adb('logcat', '-d', '-v', 'threadtime', '-T', epoch, '-s', *(tags or ['FamilyExplore:I']))


def key(code, delay=.4):
    adb('shell', 'input', 'keyevent', str(code))
    time.sleep(delay)


def shot(name):
    (OUT / 'screenshots' / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))


def resumed():
    activity = adb('shell', 'dumpsys', 'activity', 'activities')
    return next((s.strip() for s in activity.splitlines() if 'ResumedActivity' in s), '')


def top_is(name):
    top = resumed()
    return top if name in top else None


def focused():
    remote = '/sdcard/familytv-explore-routing-ui.xml'
    adb('shell', 'uiautomator', 'dump', remote)
    nodes = ET.fromstring(adb('shell', 'cat', remote)).iter('node')
    return next((n.get('content-desc') or n.get('text') for n in nodes if n.get('focused') == 'true'), None)


def wait_for(predicate, what, timeout=20):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = predicate()
        if value: return value
        time.sleep(.2)
    raise AssertionError(('timeout', what, resumed()))


def explore_state(**fields):
    def match():
        lines = [l for l in logs().splitlines() if 'screen=' in l]
        last = lines[-1] if lines else ''
        return last if all(re.search(r'\b' + k + '=' + re.escape(v) + r'(?:\s|$)', last) for k, v in fields.items()) else None
    return wait_for(match, fields)


def check_explore(name, focus_key, focus_label, **fields):
    state = explore_state(focus=focus_key, **fields)
    top = wait_for(lambda: top_is('.explore.ExploreActivity'), 'ExploreActivity')
    assert 'AI=0' in state and 'Stitch' not in top, (state, top)
    time.sleep(.8)
    label = focused()
    assert label == focus_label, (name, label)
    checks.append({'name': name, 'resumed': top, 'focus': label, 'state': state.split('FamilyExplore: ')[-1][:160]})
    print(name, top, label, flush=True)
    shot(name)


def check_adventure(name, destination, content, adventure):
    wait_for(lambda: f'open destination={destination} content={content}' in logs(), f'open {destination}')
    top = wait_for(lambda: top_is('.adventure.AdventureActivity'), 'AdventureActivity')
    ready = wait_for(lambda: next((l for l in logs('FamilyAskLumi:I').splitlines()[::-1] if f'adventure ready id={adventure} contentId={content}' in l), None), adventure)
    time.sleep(2.5)
    checks.append({'name': name, 'resumed': top, 'ready': ready.split(': ', 1)[-1]})
    print(name, top, flush=True)
    shot(name)


def focus_explore_from_home():
    order = ['Смотреть видео', 'Играть', 'Исследовать', 'Наши открытия', 'Наши истории', 'Карта']
    for _ in range(20):
        label = focused()
        if label == 'Исследовать': return
        if label in order: key(22 if order.index(label) < 2 else 21)
        else: key(19)
    raise AssertionError('Could not focus Исследовать with the D-pad')


adb('logcat', '-c', check=False)
epoch = adb('shell', 'date', "'+%m-%d %H:%M:%S.000'").strip()
device = ('model=' + adb('shell', 'getprop', 'ro.product.model') + 'android=' + adb('shell', 'getprop', 'ro.build.version.release')
          + adb('shell', 'wm', 'size') + 'animator_duration_scale=' + adb('shell', 'settings', 'get', 'global', 'animator_duration_scale')
          + '\ninput=ADB keyevent (physical remote NOT VERIFIED)\n')
(OUT / 'logs/device.txt').write_text(device)
result = 'FAIL'
try:
    adb('shell', 'am', 'force-stop', 'ru.familytv')
    adb('shell', 'am', 'start', '-W', '-n', HOME)
    time.sleep(2.5)
    focus_explore_from_home()
    assert '.home.HomeActivity' in resumed()
    shot('01_home_explore_focus')
    key(23, 1.2)
    check_explore('02_explore_home', 'destination:WEATHER', 'Погода. Исследовать', screen='HOME')
    key(21); key(21)
    check_explore('03_space_focus', 'destination:SPACE', 'Космос. Исследовать', screen='HOME')
    key(23, 1.5)
    check_adventure('04_space_adventure', 'SPACE', 'adv-space-expedition', 'space-expedition')
    key(4, 1.5)
    check_explore('05_back_space_focus', 'destination:SPACE', 'Космос. Исследовать', screen='HOME', visit='false')
    key(22)
    check_explore('06_forest_focus', 'destination:FOREST', 'Лес. Исследовать', screen='HOME')
    key(23, 1.5)
    check_adventure('07_forest_adventure', 'FOREST', 'adv-forest-trail', 'forest-trail')
    key(4, 1.5)
    check_explore('08_back_forest_focus', 'destination:FOREST', 'Лес. Исследовать', screen='HOME', visit='false')
    key(22)
    check_explore('09_weather_focus', 'destination:WEATHER', 'Погода. Исследовать', screen='HOME')
    key(23, 1.2)
    state = explore_state(screen='INTRO', focus='start', visit='true')
    assert 'visit_started content=adv-weather-lab journey=opened' in logs()
    time.sleep(1)
    checks.append({'name': '10_weather_intro', 'resumed': resumed(), 'state': state.split('FamilyExplore: ')[-1][:160]})
    print('10_weather_intro', flush=True)
    shot('10_weather_intro')
    all_logs = logs('FamilyExplore:I', 'FamilyHome:I', 'FamilyAskLumi:I')
    assert not re.search(r'\bAI=1\b', all_logs)
    crashes = adb('logcat', '-d', '-T', epoch, '-b', 'crash', check=False) + adb('logcat', '-d', '-T', epoch, '-s', 'ActivityManager:E', check=False)
    assert 'ru.familytv' not in crashes and 'ANR' not in crashes, crashes
    assert 'StitchExploreWeatherPreviewActivity' not in adb('shell', 'dumpsys', 'activity', 'activities')
    result = 'PASS'
finally:
    (OUT / 'logs/family.log').write_text(logs('FamilyExplore:I', 'FamilyHome:I', 'FamilyAskLumi:I', 'AndroidRuntime:E'))
    (OUT / 'logs/h728-routing.json').write_text(json.dumps({'result': result, 'device': device, 'checks': checks}, ensure_ascii=False, indent=2))
    print('H728 routing', result, len(checks), 'checks')
