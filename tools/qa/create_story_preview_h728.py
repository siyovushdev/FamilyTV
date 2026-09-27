#!/usr/bin/env python3
"""H728 D-pad acceptance of the debug-only Create / Story Builder V1 native preview.

Only StitchStoryBuilderPreviewActivity is launched. No app-data reset, state injection or
animation-scale change. ADB keyevents stand in for the physical remote. A continuous screenrecord
covers Create Home -> Robi -> Forest -> Golden Key -> Door -> Consequence -> Ending -> Replay ->
Ending -> New Story -> Back chain -> exit. Pause/resume and process restore run afterwards.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/design/stitch/create-story-v1/android-preview'
parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='192.168.68.105:5555')
args = parser.parse_args()
ADB = ['adb', '-s', args.serial]
PREVIEW = 'ru.familytv/.creative.stitch.StitchStoryBuilderPreviewActivity'
VIDEO = 'create-story-v1-flow.mp4'
TAG = 'StitchStory:I'
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
    assert lines, 'No preview log'
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
    assert 'StitchStoryBuilderPreviewActivity' in top, top
    if settle: time.sleep(settle)
    checks.append({'name': name, 'resumed': top, 'state': state})
    print(name, state.split(': ', 1)[-1][:170], flush=True)
    if capture: shot(name)
    return state


def ui(name=None):
    remote = '/sdcard/familytv-create-story-ui.xml'
    adb('shell', 'uiautomator', 'dump', remote)
    content = adb('shell', 'cat', remote)
    if name: (OUT / 'logs' / f'accessibility-{name}.xml').write_text(content)
    return list(ET.fromstring(content).iter('node'))


def accessibility(name, focus, present=()):
    nodes = ui(name)
    focused = [n.get('content-desc') for n in nodes if n.get('focused') == 'true']
    focusable = [n.get('content-desc') for n in nodes if n.get('focusable') == 'true']
    assert focus in focused, (name, focus, focused)
    for text in present: assert text in focusable, (name, text, focusable)
    assert not any(n.get('scrollable') == 'true' for n in nodes), (name, 'scrollable node')
    for node in nodes:
        if node.get('focusable') == 'true':
            l, t, r, b = map(int, re.findall(r'\d+', node.get('bounds')))
            assert 88 <= l < r <= 1832 and 72 <= t < b <= 1008, (name, node.attrib)
    checks.append({'name': f'accessibility-{name}', 'focus': focused, 'focusable': focusable, 'scrollable': False})


def walk(name, moves, expected):
    """Every D-pad move lands on the expected focus (logged by the state, not guessed from pixels)."""
    seen = []
    for move, focus in zip(moves, expected):
        key(move, .3)
        seen.append(field(wait(focus=focus), 'focus'))
    checks.append({'name': f'focus-{name}', 'moves': moves, 'focus': seen})


def rejected_select(name, **fields):
    """OK is consumed without changing the story (reveal / consequence cannot be skipped)."""
    before = len(state_lines())
    key(OK, .35)
    after = state_lines()[before:]
    selects = [line for line in after if 'action=Select' in line]
    assert selects and all('handled=false' in line and matches(line, fields) for line in selects), (name, after)
    checks.append({'name': f'select-ignored-{name}', 'select': selects[-1]})


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
    sample = {'surface': label, 'pid': int(pid), 'pssKiB': int(match[1]), 'modelMappings': models, 'ai': field(latest(), 'AI')}
    assert sample['ai'] == '0', sample
    measurements.append(sample)
    print('memory', sample, flush=True)


def stores():
    names = adb('shell', 'run-as', 'ru.familytv', 'ls', 'shared_prefs', check=False).split()
    return {n: adb('shell', 'run-as', 'ru.familytv', 'cat', f'shared_prefs/{n}', check=False) for n in sorted(names) if n.endswith('.xml')}


SELECTION_FIELDS = ('hero', 'world', 'object', 'focus', 'saved')


def pause_resume(name, expect_screen, kill=False):
    """Pause under the TV Settings panel and return with BACK.

    kill=True leaves with HOME instead (the Settings panel keeps the preview visible, so it is never
    stopped and has no saved state), kills the stopped process and reopens the same task.
    """
    pid = adb('shell', 'pidof', 'ru.familytv').strip()
    before = latest()
    if kill: key(3, .2)
    else: adb('shell', 'am', 'start', '-f', '0x58000000', '-a', 'android.settings.SETTINGS')
    paused = wait(paused='true', screen=expect_screen)
    time.sleep(2.0)
    assert latest() == paused, ('state advanced while paused', paused, latest())
    if kill:
        adb('shell', 'run-as', 'ru.familytv', 'kill', '-9', pid, check=False)
        time.sleep(1.5)
        assert adb('shell', 'pidof', 'ru.familytv', check=False).strip() != pid, 'process survived kill'
        adb('shell', 'am', 'start', '-W', '-n', PREVIEW)
        time.sleep(1.0)
    for _ in range(4):
        if 'StitchStoryBuilderPreviewActivity' in resumed(): break
        key(BACK, 1.0)
    after = wait(paused='false', screen=expect_screen)
    new_pid = adb('shell', 'pidof', 'ru.familytv').strip()
    assert (new_pid != pid) if kill else (new_pid == pid), (name, pid, new_pid)
    for f in SELECTION_FIELDS:
        assert field(paused, f) == field(after, f) == field(before, f), (name, f, before, paused, after)
    assert re.search(r' object=\S+ action=(\S+)', paused)[1] == re.search(r' object=\S+ action=(\S+)', after)[1]
    if expect_screen == 'OBJECT' and 'revealing=true' in paused:
        assert int(field(after, 'elapsed')) - int(field(paused, 'elapsed')) <= 300, (name, paused, after)
    checks.append({'name': f'pause-resume-{name}', 'paused': paused, 'resumed': after, 'processKilled': kill,
                   'pidPreserved': not kill, 'selectionsPreserved': list(SELECTION_FIELDS) + ['action']})
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


def start_preview():
    adb('shell', 'am', 'force-stop', 'ru.familytv')
    launch = adb('shell', 'am', 'start', '-W', '-n', PREVIEW)
    assert 'Status: ok' in launch, launch
    return launch


adb('logcat', '-c', check=False)
epoch = adb('shell', 'date', "'+%m-%d %H:%M:%S.000'").strip()
(OUT / 'logs/device.txt').write_text('model=' + adb('shell', 'getprop', 'ro.product.model') + 'android=' + adb('shell', 'getprop', 'ro.build.version.release')
                                     + adb('shell', 'wm', 'size') + adb('shell', 'wm', 'density') + 'animator_duration_scale=' + adb('shell', 'settings', 'get', 'global', 'animator_duration_scale')
                                     + '\ninput=ADB keyevent (physical remote NOT VERIFIED)\n')
stores_before = stores()
try:
    (OUT / 'logs/launch.txt').write_text(start_preview())
    wait(screen='CREATE_HOME', focus='place:BOOK')
    render = [line for line in logs().splitlines() if ' render size=' in line][-1]
    assert 'render size=1920x1080 scale=1.0' in render, render
    checks.append({'name': 'stage-1920x1080', 'render': render})
    time.sleep(1.0)
    memory('create-home')
    recording = subprocess.Popen(ADB + ['shell', 'screenrecord', '--size', '1920x1080', '--bit-rate', '6000000', '--time-limit', '175', '/sdcard/' + VIDEO],
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    time.sleep(1.5)

    # Create Home: the book owns focus; theatre and drawings are honest "soon" places.
    checkpoint('01_create_home', settle=.6, screen='CREATE_HOME', focus='place:BOOK', hero='null')
    accessibility('create-home', 'Придумать историю', present=['Театр. Скоро', 'Рисунки. Скоро'])
    walk('create-home', [LEFT], ['place:THEATER'])
    key(OK, .5); soon = wait(screen='CREATE_HOME', soon='place:THEATER')
    checks.append({'name': 'future-place-honest', 'state': soon, 'screen': 'CREATE_HOME'})
    walk('create-home-back-to-book', [RIGHT], ['place:BOOK'])

    key(OK, .6)
    wait(screen='HERO', focus='hero:ROBI', hero='null')
    walk('hero', [LEFT, RIGHT, RIGHT, LEFT, UP, DOWN], ['hero:TIM', 'hero:ROBI', 'hero:PUSHISTIK', 'hero:ROBI', 'back', 'hero:ROBI'])
    checkpoint('02_choose_hero_robi', settle=.6, screen='HERO', focus='hero:ROBI')
    accessibility('choose-hero', 'Роби', present=['Тим. Скоро', 'Пушистик. Скоро'])
    memory('choose-hero')

    key(OK, .6)
    wait(screen='WORLD', focus='world:FOREST', hero='ROBI')
    walk('world', [LEFT, RIGHT, RIGHT, LEFT], ['world:SPACE', 'world:FOREST', 'world:ISLAND', 'world:FOREST'])
    checkpoint('03_choose_world_forest', settle=.6, screen='WORLD', focus='world:FOREST', hero='ROBI')
    accessibility('choose-world', 'Волшебный лес', present=['Звёздная станция. Скоро', 'Таинственный остров. Скоро'])
    memory('forest')

    key(OK, .6)
    wait(screen='OBJECT', focus='object:KEY', hero='ROBI', world='FOREST', object='null')
    walk('object', [RIGHT, RIGHT, LEFT, LEFT], ['object:MAP', 'object:LANTERN', 'object:MAP', 'object:KEY'])
    checkpoint('04_choose_object_key', settle=.6, screen='OBJECT', focus='object:KEY', object='null')
    accessibility('choose-object', 'Золотой ключ', present=['Светящаяся карта. Скоро', 'Звёздный фонарик. Скоро'])

    key(OK, .2)
    wait(screen='OBJECT', object='KEY', revealing='true')
    rejected_select('key-reveal', screen='OBJECT', revealing='true')
    checkpoint('05_key_selected', settle=.2, screen='OBJECT', object='KEY', revealing='true')

    checkpoint('06_story_choice_door', settle=1.0, screen='STORY_CHOICE', focus='action:DOOR', hero='ROBI', world='FOREST', object='KEY')
    accessibility('story-choice', 'Открыть дверь', present=['Пойти по тропинке. Скоро'])
    memory('story-choice')
    walk('story-choice', [RIGHT], ['action:PATH'])
    checkpoint('07_story_choice_path_focus', settle=.6, screen='STORY_CHOICE', focus='action:PATH')
    key(OK, .5); path = wait(screen='STORY_CHOICE', soon='action:PATH', object='KEY')
    checks.append({'name': 'path-soon-no-wrong', 'state': path, 'screen': 'STORY_CHOICE'})
    walk('story-choice-door', [LEFT], ['action:DOOR'])

    key(OK, .3)
    wait(screen='CONSEQUENCE', action='DOOR', canContinue='false')
    rejected_select('consequence-running', screen='CONSEQUENCE', canContinue='false')
    narration = [line for line in logs().splitlines() if 'consequence_narration' in line]
    checkpoint('08_consequence_door_open', settle=.8, screen='CONSEQUENCE', action='DOOR', canContinue='true', focus='inside')
    lines = logs().splitlines()
    enter = next(i for i, line in enumerate(lines) if 'enter screen=CONSEQUENCE' in line)
    spoken = next(i for i, line in enumerate(lines) if 'consequence_narration' in line)
    ready = next(i for i, line in enumerate(lines) if 'consequence_ready' in line)
    assert enter < spoken < ready and not narration, (enter, spoken, ready)
    checks.append({'name': 'consequence-visual-before-narration', 'order': ['enter', 'consequence_narration', 'consequence_ready']})

    key(OK, .8)
    checkpoint('09_ending', settle=.8, screen='ENDING', focus='replay', hero='ROBI', world='FOREST', object='KEY', saved='false')
    accessibility('ending', 'Посмотреть ещё раз', present=['В наши истории', 'Новая история', 'В мастерскую'])
    memory('ending')

    key(OK, .5)
    wait(screen='REPLAY', replayBeat='0')
    checkpoint('10_replay', settle=.8, screen='REPLAY', replayBeat='2')
    wait(screen='ENDING', focus='replay', timeout=25)
    beats = [field(line, 'replayBeat') for line in state_lines() if 'replay_beat' in line]
    assert beats[-4:] == ['1', '2', '3', '4'], beats
    checks.append({'name': 'replay-beats-return-to-ending', 'beats': ['0'] + beats[-4:]})

    walk('ending', [RIGHT], ['save'])
    key(OK, .6)
    saved = wait(screen='ENDING', saved='true')
    note = [line for line in logs().splitlines() if 'save_preview_only persisted=false' in line]
    assert note, 'save note'
    checks.append({'name': 'save-preview-only', 'state': saved, 'log': note[-1].split(': ', 1)[-1]})
    walk('ending-new', [RIGHT], ['new'])
    key(OK, .6)
    checkpoint('11_new_story', settle=.6, screen='HERO', focus='hero:ROBI', hero='null', world='null', object='null', saved='false')

    # Back walks the story backwards and restores the chosen selection each time.
    key(OK, .5); wait(screen='WORLD', hero='ROBI')
    key(OK, .5); wait(screen='OBJECT', world='FOREST')
    key(OK, .3); wait(screen='STORY_CHOICE', object='KEY', timeout=10)
    time.sleep(.6)
    key(BACK, .5)
    checkpoint('12_back_focus_restored', settle=.6, screen='OBJECT', focus='object:KEY', object='null', world='FOREST')
    accessibility('back-object', 'Золотой ключ')
    key(BACK, .5); checkpoint('back_object_to_world', capture=False, screen='WORLD', focus='world:FOREST', world='null', hero='ROBI')
    key(BACK, .5); checkpoint('back_world_to_hero', capture=False, screen='HERO', focus='hero:ROBI', hero='null')
    key(BACK, .5); checkpoint('back_hero_to_create_home', capture=False, screen='CREATE_HOME', focus='place:BOOK')
    key(BACK, 1.5)
    assert [line for line in logs().splitlines() if ' exit screen=CREATE_HOME' in line], 'exit'
    top = resumed()
    assert 'StitchStoryBuilderPreviewActivity' not in top, top
    checks.append({'name': 'back-create-home-exits', 'resumed': top})
    time.sleep(1)
    stop_recording()

    # ---- Supplemental checks (outside the continuous recording) ----
    start_preview()
    wait(screen='CREATE_HOME', focus='place:BOOK')
    time.sleep(.8)
    key(OK, .5); wait(screen='HERO')
    key(OK, .5); wait(screen='WORLD')
    key(OK, .5); wait(screen='OBJECT', focus='object:KEY')
    key(RIGHT, .4); wait(screen='OBJECT', focus='object:MAP')
    pause_resume('object', 'OBJECT')
    key(LEFT, .4); wait(screen='OBJECT', focus='object:KEY')
    key(OK, .1); wait(screen='OBJECT', revealing='true')
    pause_resume('object-revealing', 'OBJECT')
    wait(screen='STORY_CHOICE', focus='action:DOOR')
    key(RIGHT, .4); wait(screen='STORY_CHOICE', focus='action:PATH')
    pause_resume('story-choice', 'STORY_CHOICE')
    pause_resume('story-choice-process-restore', 'STORY_CHOICE', kill=True)
    key(LEFT, .4); wait(screen='STORY_CHOICE', focus='action:DOOR')
    key(OK, .3); wait(screen='CONSEQUENCE', action='DOOR')
    key(BACK, .5); checkpoint('back_consequence_to_choice', capture=False, screen='STORY_CHOICE', focus='action:DOOR', action='null', object='KEY')
    key(OK, .3); wait(screen='CONSEQUENCE', action='DOOR', canContinue='true', focus='inside')
    key(OK, .6); wait(screen='ENDING', focus='replay')
    walk('ending-trap', [UP, DOWN, RIGHT, RIGHT, RIGHT, LEFT, LEFT, LEFT], ['back', 'replay', 'save', 'new', 'new', 'save', 'replay', 'replay'])
    key(BACK, .6)
    checkpoint('back_ending_to_create_home', capture=False, screen='CREATE_HOME', focus='place:BOOK', hero='null', world='null', object='null')
    key(BACK, 1.2)

    stores_after = stores()
    changed = sorted(n for n in set(stores_before) | set(stores_after) if stores_before.get(n) != stores_after.get(n))
    (OUT / 'logs/stores-delta.json').write_text(json.dumps({'files': sorted(stores_after), 'changed': changed,
        'delta': {n: {'before': stores_before.get(n, ''), 'after': stores_after.get(n, '')} for n in changed}}, ensure_ascii=False, indent=2) + '\n')
    assert not changed, ('production stores changed', changed)
    checks.append({'name': 'production-stores-unchanged', 'files': sorted(stores_after)})

    full = adb('logcat', '-d', '-v', 'threadtime', '-T', epoch)
    assert not re.search(r'FATAL EXCEPTION|ANR in ru\.familytv|Fatal signal', full)
    assert not re.search(r'\bAI=1\b', logs()) and all('AI=0' in line for line in state_lines())
    assert not re.search(r'(HomeActivity|CreativeStudioActivity|StoryGalleryActivity).*(Displayed|START)', full), 'production screen opened'
    checks.append({'name': 'preview-only-no-crash-no-ai', 'ai': 0, 'stateLines': len(state_lines())})
    print('PASS', len(checks), 'checks', flush=True)
finally:
    stop_recording()
    full = adb('logcat', '-d', '-v', 'threadtime', '-T', epoch)
    (OUT / 'logs/state.log').write_text(logs())
    (OUT / 'logs/logcat.txt').write_text('\n'.join(s for s in full.splitlines() if any(t in s for t in
        ['StitchStory', 'VoiceRuntime', 'Narration', 'ru.familytv', 'AndroidRuntime', 'FATAL EXCEPTION', 'Fatal signal', 'ANR'])) + '\n')
    (OUT / 'logs/checks.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2) + '\n')
    (OUT / 'logs/performance.json').write_text(json.dumps(measurements, indent=2) + '\n')
