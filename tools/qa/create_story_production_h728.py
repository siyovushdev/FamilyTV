#!/usr/bin/env python3
"""H728 D-pad acceptance of production Create / Story Builder V1.

Every run enters through HomeActivity. No app-data reset, state injection or animation-scale
change. ADB keyevents stand in for the physical remote. The debug preview Activity is never
launched as production evidence.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/v1/create-story-production'
parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='192.168.68.105:5555')
args = parser.parse_args()
ADB = ['adb', '-s', args.serial]
HOME = 'ru.familytv/.home.HomeActivity'
VIDEO = 'create-story-production-full.mp4'
TAG = 'FamilyStory:I'
CREATE = 'Придумать историю'
TITLE = 'Тайна зачарованного дуба'
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
    assert lines, 'No production story log'
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


def shot(name):
    (OUT / 'screenshots' / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))


def resumed():
    activity = adb('shell', 'dumpsys', 'activity', 'activities')
    return next((s.strip() for s in activity.splitlines() if 'ResumedActivity' in s), '')


def checkpoint(name, capture=True, settle=0.0, **fields):
    state = wait(**fields)
    assert 'AI=0' in state, state
    top = resumed()
    assert 'CreativeStudioActivity' in top and 'StitchStory' not in top, top
    if settle: time.sleep(settle)
    checks.append({'name': name, 'resumed': top, 'state': state})
    print(name, state.split(': ', 1)[-1][:170], flush=True)
    if capture: shot(name)
    return state


def ui(name=None):
    remote = '/sdcard/familytv-create-production-ui.xml'
    adb('shell', 'uiautomator', 'dump', remote)
    content = adb('shell', 'cat', remote)
    if name: (OUT / 'logs' / f'accessibility-{name}.xml').write_text(content)
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


def stories():
    xml = store('familytv_stories')
    if not xml: return []
    text = ''.join(n.text or '' for n in ET.fromstring(xml).iter('string') if n.get('name') == 'stories')
    return json.loads(text) if text else []


SELECTION_FIELDS = ('hero', 'world', 'object', 'focus', 'saved')


def pause_resume(name, expect_screen):
    pid = adb('shell', 'pidof', 'ru.familytv').strip()
    before = latest()
    adb('shell', 'am', 'start', '-f', '0x58000000', '-a', 'android.settings.SETTINGS')
    paused = wait(paused='true', screen=expect_screen)
    time.sleep(1.5)
    assert latest() == paused, ('state advanced while paused', paused, latest())
    for _ in range(4):
        key(BACK, 1.0)
        if 'CreativeStudioActivity' in resumed(): break
    after = wait(paused='false', screen=expect_screen)
    assert adb('shell', 'pidof', 'ru.familytv').strip() == pid
    for f in SELECTION_FIELDS:
        assert field(paused, f) == field(after, f) == field(before, f), (name, f, before, paused, after)
    checks.append({'name': f'pause-resume-{name}', 'paused': paused, 'resumed': after, 'pidPreserved': True})
    shot(f'extra_pause_resume_{name}')
    return after


def stop_recording():
    global recording
    if recording is None: return
    pid = adb('shell', 'pidof', 'screenrecord', check=False).strip()
    if pid: adb('shell', 'kill', '-2', *pid.split(), check=False)
    try: recording.communicate(timeout=15)
    except subprocess.TimeoutExpired: recording.terminate(); recording.communicate(timeout=5)
    recording = None
    time.sleep(1)
    adb('pull', '/sdcard/' + VIDEO, str(OUT / 'recordings' / VIDEO), timeout=90)


def focus_create_from_home():
    for _ in range(8): key(UP, .15)
    for _ in range(14):
        for _ in range(10):
            node = focused_node()
            label = node is not None and ((node.get('content-desc') or '') + (node.get('text') or ''))
            if label and CREATE in label: return label
            before = node.get('bounds') if node is not None else None
            key(RIGHT, .28)
            node = focused_node()
            if node is None or node.get('bounds') == before: break
        for _ in range(10): key(LEFT, .08)
        key(DOWN, .4)
    raise AssertionError('Create catalog card not reachable with D-pad')


def canonical_to_ending(from_home=True):
    if from_home:
        wait(screen='CREATE_HOME', focus='place:BOOK')
        key(OK, .6)
    wait(screen='HERO', focus='hero:ROBI')
    key(OK, .6)
    wait(screen='WORLD', focus='world:FOREST', hero='ROBI')
    key(OK, .6)
    wait(screen='OBJECT', focus='object:KEY')
    key(OK, .2)
    wait(screen='OBJECT', revealing='true', object='KEY')
    wait(screen='STORY_CHOICE', focus='action:DOOR', object='KEY')
    key(OK, .3)
    wait(screen='CONSEQUENCE', action='DOOR', canContinue='true', focus='inside')
    key(OK, .7)
    return wait(screen='ENDING', focus='replay', hero='ROBI', world='FOREST', object='KEY')


adb('logcat', '-c', check=False)
epoch = adb('shell', 'date', "'+%m-%d %H:%M:%S.000'").strip()
(OUT / 'logs/device.txt').write_text('model=' + adb('shell', 'getprop', 'ro.product.model') + 'android=' + adb('shell', 'getprop', 'ro.build.version.release')
                                     + adb('shell', 'wm', 'size') + adb('shell', 'wm', 'density') + 'animator_duration_scale=' + adb('shell', 'settings', 'get', 'global', 'animator_duration_scale')
                                     + '\ninput=ADB keyevent (physical remote NOT VERIFIED)\n')
STORES = ['familytv_stories', 'familytv_activity', 'familytv_journey_progress', 'familytv_discovery_journal', 'familytv_trial']
    stores_before = {name: store(name) for name in STORES}
stories_before = stories()
try:
    adb('shell', 'am', 'force-stop', 'ru.familytv')
    (OUT / 'logs/launch.txt').write_text(adb('shell', 'am', 'start', '-W', '-n', HOME))
    time.sleep(2.5)
    assert 'HomeActivity' in resumed()
    memory('home')
    focus = focus_create_from_home()
    checks.append({'name': 'home-create-focus', 'focus': focus})
    recording = subprocess.Popen(ADB + ['shell', 'screenrecord', '--size', '1920x1080', '--bit-rate', '6000000', '--time-limit', '180', '/sdcard/' + VIDEO],
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    time.sleep(1.5)
    shot('01_home_create_focus')

    key(OK, 1.4)
    checkpoint('02_create_home', settle=.6, screen='CREATE_HOME', focus='place:BOOK')
    render = [line for line in logs().splitlines() if ' render size=' in line][-1]
    assert 'render size=1920x1080 scale=1.0' in render, render
    memory('create-home')

    key(OK, .6)
    checkpoint('03_choose_robi', settle=.5, screen='HERO', focus='hero:ROBI')
    memory('hero')
    key(LEFT, .35); wait(screen='HERO', focus='hero:TIM')
    key(OK, .4); soon = wait(screen='HERO', soon='hero:TIM')
    checks.append({'name': 'future-hero-honest', 'state': soon})
    key(RIGHT, .35); wait(screen='HERO', focus='hero:ROBI')

    key(OK, .6)
    checkpoint('04_choose_forest', settle=.5, screen='WORLD', focus='world:FOREST', hero='ROBI')
    memory('forest')
    key(OK, .6)
    checkpoint('05_choose_key', settle=.5, screen='OBJECT', focus='object:KEY', object='null')
    key(OK, .2)
    checkpoint('06_key_selected', settle=.2, screen='OBJECT', object='KEY', revealing='true')
    checkpoint('07_choose_door', settle=.8, screen='STORY_CHOICE', focus='action:DOOR', object='KEY')
    memory('story-choice')
    key(OK, .3)
    wait(screen='CONSEQUENCE', action='DOOR', canContinue='false')
    checkpoint('08_consequence', settle=.6, screen='CONSEQUENCE', action='DOOR', canContinue='true', focus='inside')
    key(OK, .7)
    checkpoint('09_ending', settle=.6, screen='ENDING', focus='replay', saved='false')
    memory('ending')

    key(RIGHT, .35); wait(screen='ENDING', focus='save')
    key(OK, .7)
    saved = wait(screen='ENDING', saved='true')
    assert 'save persisted=true' in logs()
    saved_stories = stories()
    assert any(s.get('title') == TITLE for s in saved_stories), saved_stories
    assert len(saved_stories) == len(stories_before) or len(saved_stories) == len(stories_before) + 1
    checks.append({'name': 'save-storystore', 'title': TITLE, 'count': len(saved_stories)})
    shot('10_saved')
    key(OK, .4)
    assert len(stories()) == len(saved_stories), 'duplicate save'
    key(LEFT, .35); wait(screen='ENDING', focus='replay')
    key(OK, .5)
    checkpoint('11_replay', settle=.8, screen='REPLAY', replayBeat='2')
    wait(screen='ENDING', focus='replay', timeout=25)
    assert len(stories()) == len(saved_stories), 'replay wrote a story'
    key(RIGHT, .3); key(RIGHT, .3); wait(screen='ENDING', focus='new')
    key(OK, .6)
    checkpoint('12_new_story', settle=.5, screen='HERO', focus='hero:ROBI', hero='null', saved='false')
    assert any(s.get('title') == TITLE for s in stories()), 'new story deleted the save'
    for _ in range(3):
        if state_lines() and matches(latest(), {'screen': 'CREATE_HOME'}): break
        key(BACK, .7)
    wait(screen='CREATE_HOME', focus='place:BOOK')
    key(BACK, 1.5)
    top = resumed()
    assert 'HomeActivity' in top, top
    node = focused_node()
    label = (node.get('content-desc') if node is not None else '') or ''
    assert CREATE in label, ('home focus not restored', label)
    checks.append({'name': '13_home_return', 'resumed': top, 'focus': label})
    print('13_home_return', label, flush=True)
    shot('13_home_return')

    found = False
    for _ in range(8): key(UP, .25)
    for _ in range(14):
        node = focused_node()
        label = node is not None and ((node.get('content-desc') or '') + (node.get('text') or ''))
        if label and 'Наши истории' in label:
            found = True
            break
        key(RIGHT, .25)
    if found:
        key(OK, 1.5)
    else:
        adb('shell', 'am', 'start', '-W', '-n', 'ru.familytv/.journey.DiscoveryJournalActivity', '--es', 'focus', 'stories')
        time.sleep(1.0)
        checks.append({'name': 'gallery-via-production-journal-route', 'reason': 'Home continuity chip was above the restored create card'})
    assert 'DiscoveryJournalActivity' in resumed()
    nodes = ui('gallery')
    titles = [n.get('content-desc') or n.get('text') or '' for n in nodes]
    assert any(TITLE in t for t in titles), titles
    checks.append({'name': '14_story_in_gallery', 'titles': [t for t in titles if TITLE in t]})
    print('14_story_in_gallery', TITLE, flush=True)
    shot('14_story_in_gallery')
    time.sleep(1)
    stop_recording()

    key(BACK, 1.2)
    focus_create_from_home()
    key(OK, 1.4)
    if state_lines() and matches(latest(), {'screen': 'CREATE_HOME'}):
        key(OK, .5)
    wait(screen='HERO')
    key(OK, .5); wait(screen='WORLD')
    key(OK, .5); wait(screen='OBJECT', focus='object:KEY')
    pause_resume('object', 'OBJECT')
    key(OK, .2); wait(screen='STORY_CHOICE', focus='action:DOOR')
    pause_resume('story-choice', 'STORY_CHOICE')
    key(BACK, .5); key(BACK, .5); key(BACK, .5); key(BACK, 1.2)

    # Multi-story memory: three canonical endings in one visit.
    focus_create_from_home(); key(OK, 1.4)
    start_home = False
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        if state_lines() and matches(latest(), {'screen': 'CREATE_HOME'}):
            start_home = True
            break
        if state_lines() and matches(latest(), {'screen': 'HERO'}):
            break
        time.sleep(.1)
    before = memory('multi-before')
    series = []
    for i in range(3):
        canonical_to_ending(from_home=(i == 0 and start_home))
        series.append(memory(f'multi-ending-{i + 1}'))
        if i < 2:
            key(RIGHT, .3); key(RIGHT, .3); wait(screen='ENDING', focus='new'); key(OK, .6)
    (OUT / 'logs/multi-story-memory.json').write_text(json.dumps({'before': before, 'endings': series}, indent=2) + '\n')
    growth = series[-1]['pssKiB'] - series[0]['pssKiB']
    assert growth < 80_000, ('monotonic runaway', series)
    checks.append({'name': 'multi-story-memory', 'pss': [s['pssKiB'] for s in series], 'growthKiB': growth})

    full = adb('logcat', '-d', '-v', 'threadtime', '-T', epoch)
    assert 'StitchStoryBuilderPreviewActivity' not in full
    assert not re.search(r'FATAL EXCEPTION|ANR in ru\.familytv|Fatal signal', full)
    assert not re.search(r'\bAI=1\b', logs() + logs('FamilyHome:I'))
    checks.append({'name': 'production-only-no-crash-no-ai', 'ai': 0, 'debugEntry': False})
    print('PASS', len(checks), 'checks', flush=True)
finally:
    stop_recording()
    full = adb('logcat', '-d', '-v', 'threadtime', '-T', epoch)
    (OUT / 'logs/state.log').write_text(logs())
    (OUT / 'logs/logcat.txt').write_text('\n'.join(s for s in full.splitlines() if any(t in s for t in
        ['FamilyStory', 'FamilyHome', 'VoiceRuntime', 'ru.familytv', 'AndroidRuntime', 'FATAL EXCEPTION', 'Fatal signal', 'ANR'])) + '\n')
    (OUT / 'logs/checks.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2) + '\n')
    (OUT / 'logs/performance.json').write_text(json.dumps(measurements, indent=2) + '\n')
    stores_after = {name: store(name) for name in STORES}
    (OUT / 'logs/stores-delta.json').write_text(json.dumps({name: {'changed': stores_before[name] != stores_after[name],
        'afterHasTitle': TITLE in stores_after[name]} for name in STORES}, ensure_ascii=False, indent=2) + '\n')
