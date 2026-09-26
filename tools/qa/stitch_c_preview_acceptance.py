#!/usr/bin/env python3
"""H728 evidence for the debug-only Stitch C preview. Does not clear app data.
Usage: python3 tools/qa/stitch_c_preview_acceptance.py --device SERIAL [--phase measure|capture|verify]
Screenshots are raw device captures; the recording is one uninterrupted screenrecord.
"""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/design/stitch/home-c/android-preview'
PKG = 'ru.familytv'
ACTIVITY = PKG + '/.home.stitch.StitchHomePreviewActivity'
parser = argparse.ArgumentParser()
parser.add_argument('--device', required=True)
parser.add_argument('--phase', choices=['measure', 'capture', 'verify'], required=True)
args = parser.parse_args()
ADB = ['adb', '-s', args.device]
for folder in ['screenshots', 'recordings', 'logs']:
    (OUT / folder).mkdir(parents=True, exist_ok=True)

def adb(*cmd, binary=False):
    r = subprocess.run(ADB + list(cmd), stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True, timeout=90)
    return r.stdout if binary else r.stdout.decode('utf-8', errors='replace')

def save_log(name, value):
    (OUT / 'logs' / name).write_text(value)

def key(name, pause=0.45):
    adb('shell', 'input', 'keyevent', 'KEYCODE_' + name)
    time.sleep(pause)

def shot(name):
    (OUT / 'screenshots' / name).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    print('Captured', name, flush=True)

def launch(cold=False):
    if cold:
        adb('shell', 'am', 'force-stop', PKG)
    output = adb('shell', 'am', 'start', '-W', '-n', ACTIVITY)
    time.sleep(1.5)
    return output

def process_log():
    pid = adb('shell', 'pidof', PKG).strip()
    return adb('logcat', '-d', '--pid=' + pid, '-v', 'threadtime')

def hierarchy(name):
    result = adb('shell', 'uiautomator', 'dump', '/sdcard/stitch_c_window.xml')
    xml = adb('exec-out', 'cat', '/sdcard/stitch_c_window.xml')
    try:
        ET.fromstring(xml)
    except ET.ParseError:
        # Some H728 builds cannot reach UiAutomator's idle condition during floating motion.
        # Keep the tool's failure as text; never label an error message as a hierarchy dump.
        save_log(name.replace('.xml', '_unavailable.txt'), result + '\n' + xml)
    else:
        save_log(name, xml)

def assert_focus(expected):
    log = process_log()
    matches = re.findall(r'focus=(\S+) lastWorld=', log)
    actual = matches[-1] if matches else None
    assert actual == expected, (expected, actual)
    print('PASS focus', expected, flush=True)

if args.phase == 'measure':
    info = adb('shell', 'getprop ro.product.model; getprop ro.soc.model; getprop ro.hardware; getprop ro.build.version.release; wm size; wm density')
    save_log('device.txt', info)
    results = []
    for i in range(3):
        output = launch(cold=True)
        save_log(f'cold_start_{i + 1}.txt', output)
        time.sleep(2)
        mem = adb('shell', 'dumpsys', 'meminfo', PKG)
        save_log(f'meminfo_{i + 1}.txt', mem)
        log = process_log()
        save_log(f'cold_logcat_{i + 1}.txt', log)
        assert 'AI=0 runtimePresent=false' in log
        assert not re.search(r'runtimeInitialization count=|llmInitMs|NativeQwen.create', log)
        total = re.search(r'TotalTime:\s*(\d+)', output)
        pss = re.search(r'TOTAL PSS:\s*(\d+)', mem)
        results.append({'total_time_ms': int(total[1]) if total else None, 'pss_kb': int(pss[1]) if pss else None})
        print('Measurement', i + 1, results[-1], flush=True)
    pid = adb('shell', 'pidof', PKG).strip()
    maps = adb('shell', 'run-as', PKG, 'cat', '/proc/' + pid + '/maps')
    save_log('preview_process_maps.txt', maps)
    assert not re.search(r'\.(gguf|onnx)(?:\s|$)', maps, re.I), 'AI model is mapped'
    save_log('measurements.json', json.dumps(results, indent=2))

elif args.phase == 'capture':
    # Open from the existing FamilyTV Home so the uncut video also shows the preserved launcher.
    adb('shell', 'am', 'force-stop', PKG)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.home.HomeActivity')
    time.sleep(1)
    remote = '/sdcard/stitch_c_home_navigation.mp4'
    recorder = subprocess.Popen(ADB + ['shell', 'screenrecord', '--size', '1920x1080', '--bit-rate', '6000000', '--time-limit', '45', remote], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    time.sleep(1)
    launch()
    shot('01_stitch_c_default.png')
    key('DPAD_LEFT', 0.8); shot('02_stitch_c_watch_focus.png')
    key('DPAD_RIGHT', 0.8); shot('03_stitch_c_play_focus.png')
    key('DPAD_RIGHT', 0.8); shot('04_stitch_c_explore_focus.png')
    key('DPAD_RIGHT', 0.8); shot('05_stitch_c_create_focus.png')
    key('DPAD_RIGHT', 0.8); shot('06_stitch_c_move_focus.png')
    key('DPAD_UP', 0.8); shot('07_stitch_c_parent_top.png')
    key('DPAD_DOWN'); key('DPAD_LEFT'); key('DPAD_LEFT'); key('DPAD_LEFT', 0.8)
    shot('08_stitch_c_lumi.png')
    key('DPAD_DOWN', 0.8); shot('09_stitch_c_mission_focus.png')
    key('DPAD_UP'); key('DPAD_UP', 0.8); shot('10_stitch_c_voice_top.png')
    key('DPAD_CENTER', 0.5); shot('11_stitch_c_voice_hint.png')
    key('DPAD_DOWN')
    stdout, stderr = recorder.communicate(timeout=50)
    if recorder.returncode != 0:
        raise RuntimeError(stderr.decode(errors='replace'))
    adb('pull', remote, str(OUT / 'recordings/stitch_c_home_navigation.mp4'))
    save_log('recording_log.txt', stdout.decode(errors='replace') + stderr.decode(errors='replace'))
    save_log('navigation_logcat.txt', process_log())
    print('Continuous recording saved', flush=True)

elif args.phase == 'verify':
    launch(cold=True)
    steps = [
        (None, 'world:1'), ('DPAD_LEFT', 'world:0'), ('DPAD_LEFT', 'world:0'),
        ('DPAD_RIGHT', 'world:1'), ('DPAD_RIGHT', 'world:2'), ('DPAD_RIGHT', 'world:3'),
        ('DPAD_RIGHT', 'world:4'), ('DPAD_RIGHT', 'world:4'),
        ('DPAD_UP', 'parent'), ('DPAD_LEFT', 'voice'), ('DPAD_LEFT', 'voice'),
        ('DPAD_RIGHT', 'parent'), ('DPAD_RIGHT', 'parent'), ('DPAD_DOWN', 'world:4'),
        ('DPAD_DOWN', 'mission'), ('DPAD_DOWN', 'mission'), ('DPAD_UP', 'world:4'),
        ('DPAD_LEFT', 'world:3'), ('DPAD_LEFT', 'world:2'), ('DPAD_LEFT', 'world:1'),
        ('DPAD_UP', 'voice'), ('DPAD_DOWN', 'world:1'),
    ]
    for button, expected in steps:
        if button: key(button)
        assert_focus(expected)
    hierarchy('world_focus.xml')
    key('DPAD_UP'); key('DPAD_CENTER')
    assert 'voice_control_only AI=0 runtimePresent=false' in process_log()
    key('DPAD_RIGHT'); hierarchy('parent_focus.xml')
    key('DPAD_CENTER', 1)
    parent_state = adb('shell', 'dumpsys', 'activity', 'activities')
    assert re.search(r'(?:topResumedActivity|mResumedActivity).*ParentModeActivity', parent_state)
    shot('12_stitch_c_parent_entry.png')
    key('BACK', 1)
    parent_back = adb('shell', 'dumpsys', 'activity', 'activities')
    assert re.search(r'(?:topResumedActivity|mResumedActivity).*HomeActivity', parent_back)
    # Parent Mode deliberately exits to production Home; retain that existing contract.
    launch(cold=True)
    assert_focus('world:1')
    # All five representatives enter existing Details, then return to the same focused station.
    key('DPAD_LEFT')
    for i in range(5):
        assert_focus(f'world:{i}')
        key('DPAD_CENTER', 0.8)
        state = adb('shell', 'dumpsys', 'activity', 'activities')
        target = 'CreativeStudioActivity' if i == 3 and 'creativeShortcut=true' in process_log() else 'ContentDetailsActivity'
        assert re.search(r'(?:topResumedActivity|mResumedActivity).*' + target, state)
        key('BACK', 0.8); assert_focus(f'world:{i}')
        if i < 4: key('DPAD_RIGHT')
    key('DPAD_DOWN'); key('DPAD_CENTER', 1)
    state = adb('shell', 'dumpsys', 'activity', 'activities')
    assert re.search(r'(?:topResumedActivity|mResumedActivity).*JourneyMapActivity', state)
    shot('13_stitch_c_journey_entry.png')
    key('BACK', 1); assert_focus('mission')
    save_log('acceptance_logcat.txt', process_log())
    key('BACK', 1)
    state = adb('shell', 'dumpsys', 'activity', 'activities')
    assert not re.search(r'(?:topResumedActivity|mResumedActivity).*StitchHomePreviewActivity', state)
    # Production launcher remains available.
    adb('shell', 'am', 'force-stop', PKG)
    original_home = adb('shell', 'am', 'start', '-W', '-n', PKG + '/.home.HomeActivity')
    save_log('production_home_start.txt', original_home)
    time.sleep(2)
    shot('14_production_home_unchanged.png')
    save_log('production_home_meminfo.txt', adb('shell', 'dumpsys', 'meminfo', PKG))
    home_log = process_log()
    save_log('production_home_logcat.txt', home_log)
    assert not re.search(r'runtimeInitialization count=|llmInitMs|NativeQwen.create', home_log)
    save_log('acceptance_result.txt', 'PASS: 22 directional states, bounded edges, last-world return, voice AI=0, Parent, five content/studio routes, Journey, BACK exit, production Home launch.\n')
    launch(cold=True)
    print('PASS all device navigation checks', flush=True)
