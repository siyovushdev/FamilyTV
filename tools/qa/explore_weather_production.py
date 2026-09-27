#!/usr/bin/env python3
"""H728 D-pad acceptance of production Explore / Weather V1. Every run enters through HomeActivity.

No app-data reset, state injection, route intent or animation-scale change. ADB keyevents stand in
for the physical remote. A continuous screenrecord covers Home -> Explore -> Weather -> Home.
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
OUT = ROOT / 'docs/v1/explore-weather-production'
parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='192.168.68.105:5555')
args = parser.parse_args()
ADB = ['adb', '-s', args.serial]
HOME = 'ru.familytv/.home.HomeActivity'
VIDEO = 'explore-weather-production-full.mp4'
NEXT = 'Спрятать лёд от солнца'
EXPLANATION = 'Луми объясняет: солнце, тепло, лёд, вода'
STORES = ['familytv_activity', 'familytv_journey_progress', 'familytv_discovery_journal', 'familytv_adventure_progress', 'familytv_trial', 'familytv_adventure_events']
checks, measurements = [], []
recording = None
for directory in ['logs', 'screenshots', 'recordings']:
    (OUT / directory).mkdir(parents=True, exist_ok=True)


def adb(*parts, binary=False, check=True, timeout=30):
    result = subprocess.run(ADB + list(parts), capture_output=True, check=check, timeout=timeout)
    return result.stdout if binary else result.stdout.decode(errors='replace')


def logs(tag='FamilyExplore:I'):
    return adb('logcat', '-d', '-v', 'threadtime', '-T', epoch, '-s', tag)


def state_lines():
    return [line for line in logs().splitlines() if 'screen=' in line]


def latest():
    lines = state_lines()
    assert lines, 'No production Explore log'
    return lines[-1]


def matches(line, fields):
    return all(re.search(r'\b' + k + '=' + re.escape(str(v)) + r'(?:\s|$)', line) for k, v in fields.items())


def wait(timeout=26, **fields):
    deadline = time.monotonic() + timeout
    line = ''
    while time.monotonic() < deadline:
        line = (state_lines() or [''])[-1]
        if line and matches(line, fields):
            return line
        time.sleep(.08)
    raise AssertionError(('state timeout', fields, line))


def key(code, delay=.3):
    adb('shell', 'input', 'keyevent', str(code))
    time.sleep(delay)


def shot(name):
    (OUT / 'screenshots' / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))


def resumed():
    activity = adb('shell', 'dumpsys', 'activity', 'activities')
    return next((s.strip() for s in activity.splitlines() if 'ResumedActivity' in s), '')


def checkpoint(name, capture=True, settle=0.0, **fields):
    state = wait(**fields)
    assert 'AI=0' in state, state
    top = resumed()
    assert '.explore.ExploreActivity' in top and 'Stitch' not in top, top
    if settle: time.sleep(settle)
    checks.append({'name': name, 'resumed': top, 'state': state})
    print(name, state, flush=True)
    if capture: shot(name)
    return state


def ui(name=None):
    remote = '/sdcard/familytv-explore-production-ui.xml'
    adb('shell', 'uiautomator', 'dump', remote)
    content = adb('shell', 'cat', remote)
    if name: (OUT / 'logs' / f'accessibility-{name}.xml').write_text(content)
    return list(ET.fromstring(content).iter('node'))


def focused_node():
    return next((n for n in ui() if n.get('focused') == 'true'), None)


def home_check(name, capture=True):
    top = resumed()
    assert '.home.HomeActivity' in top, top
    nodes = ui(name)
    focus = [n.get('content-desc') for n in nodes if n.get('focused') == 'true']
    assert 'Исследовать' in focus, (name, focus)
    assert len([n for n in nodes if n.get('content-desc') == 'Исследовать']) == 1, 'one Explore entry'
    checks.append({'name': name, 'resumed': top, 'focus': focus, 'exploreEntries': 1})
    print(name, focus, flush=True)
    if capture: shot(name)


def focus_explore_from_home():
    order = ['Смотреть видео', 'Играть', 'Исследовать', 'Наши открытия', 'Наши истории', 'Карта']
    for _ in range(20):
        node = focused_node()
        label = node is not None and (node.get('content-desc') or node.get('text'))
        if label == 'Исследовать': return
        if label in order: key(22 if order.index(label) < 2 else 21)
        else: key(19)
    raise AssertionError('Could not focus Исследовать with the D-pad')


def accessibility(name, focus=None, present=(), absent=()):
    nodes = ui(name)
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


def premature_select(name, since, then=None):
    """OK during the explanation is consumed and never opens Build & Test (log checked afterwards)."""
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
    checks.append({'name': f'premature-select-{name}', 'select': selects[-1], 'buildOpened': False})


def assert_sequence(name, start, end):
    lines = state_lines()
    span = lines[lines.index(start):lines.index(end) + 1]
    assert all('screen=ASK' in line for line in span), (name, span)
    order = []
    for line in span:
        cause = re.search(r' cause=(\w+) beat=', line)[1]
        if not order or order[-1] != cause: order.append(cause)
        assert ('canBuild=true' in line) == (cause == 'DONE'), (name, line)
    assert order == ['SUN', 'HEAT', 'ICE', 'WATER', 'DONE'], (name, order)
    checks.append({'name': f'sequence-{name}', 'order': order, 'loggedStates': len(span), 'nextLockedUntilDone': True})


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
    measurements.append(sample)
    print('memory', sample, flush=True)


def store(name):
    content = adb('shell', 'run-as', 'ru.familytv', 'cat', f'shared_prefs/{name}.xml', check=False)
    return content if content.startswith('<?xml') else ''


def history():
    xml = store('familytv_activity')
    if not xml: return []
    return [e for n in ET.fromstring(xml).iter('string') if n.get('name', '').startswith('day:') for e in json.loads(n.text)]


def weather_history():
    return [e for e in history() if e.get('contentId') == 'adv-weather-lab']


def pause_resume(name, expect_screen, frozen_fields=('elapsed', 'cause', 'hypothesis', 'shelter', 'focus', 'canBuild', 'canCheck')):
    before_history = len(history())
    pid = adb('shell', 'pidof', 'ru.familytv').strip()
    adb('shell', 'am', 'start', '-f', '0x58000000', '-a', 'android.settings.SETTINGS')
    paused = wait(paused='true', screen=expect_screen)
    time.sleep(1.5)
    assert latest() == paused, ('state advanced while paused', paused, latest())
    for _ in range(4):
        key(4, .8)
        if '.explore.ExploreActivity' in resumed(): break
    after = wait(paused='false', screen=expect_screen)
    assert adb('shell', 'pidof', 'ru.familytv').strip() == pid
    grab = lambda line, f: re.search(r'\b' + f + r'=(\S+)', line)[1]
    for field in frozen_fields:
        if field == 'elapsed' and expect_screen in ('EXPERIMENT', 'ASK', 'BUILD_RUNNING'):
            assert int(grab(after, 'elapsed')) - int(grab(paused, 'elapsed')) <= 300, (name, paused, after)
        elif field != 'elapsed':
            assert grab(paused, field) == grab(after, field), (name, field, paused, after)
    assert len(history()) == before_history, 'pause/resume wrote history'
    since = logs()
    checks.append({'name': f'pause-resume-{name}', 'paused': paused, 'resumed': after, 'pidPreserved': True, 'historyUnchanged': True,
                   'noVisitRecordOnResume': not re.search(r'visit_(started|completed)', since.split(after)[-1])})
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


def find_catalog_weather_card():
    """Walk Home rows with the real D-pad until the existing catalog card is focused."""
    for _ in range(14):
        for _ in range(8):
            node = focused_node()
            label = node is not None and ((node.get('content-desc') or '') + (node.get('text') or ''))
            if label and 'Лаборатория погоды' in label: return label
            before = node.get('bounds') if node is not None else None
            key(22, .35)
            node = focused_node()
            if node is None or node.get('bounds') == before: break
        for _ in range(8): key(21, .12)
        key(20, .5)
    raise AssertionError('Catalog weather card not reachable with D-pad')


adb('logcat', '-c', check=False)
epoch = adb('shell', 'date', "'+%m-%d %H:%M:%S.000'").strip()
(OUT / 'logs/device.txt').write_text('model=' + adb('shell', 'getprop', 'ro.product.model') + 'android=' + adb('shell', 'getprop', 'ro.build.version.release')
                                     + adb('shell', 'wm', 'size') + adb('shell', 'wm', 'density') + 'animator_duration_scale=' + adb('shell', 'settings', 'get', 'global', 'animator_duration_scale')
                                     + '\ninput=ADB keyevent (physical remote NOT VERIFIED)\n')
stores_before = {name: store(name) for name in STORES}
history_before = history()
try:
    adb('shell', 'am', 'force-stop', 'ru.familytv')
    (OUT / 'logs/launch.txt').write_text(adb('shell', 'am', 'start', '-W', '-n', HOME))
    time.sleep(2.5)
    focus_explore_from_home()
    memory('home')
    recording = subprocess.Popen(ADB + ['shell', 'screenrecord', '--size', '1920x1080', '--bit-rate', '6000000', '--time-limit', '175', '/sdcard/' + VIDEO],
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    time.sleep(1.5)
    home_check('01_home_explore_focus')
    key(23, 1.2)
    checkpoint('02_explore_home', settle=.8, screen='HOME', focus='destination:WEATHER', visit='false')
    accessibility('explore-home', 'Погода. Исследовать', present=['Погода. Исследовать', 'Космос. Исследовать', 'Лес. Исследовать',
                                                               'Как устроено. Скоро, пока недоступно', 'Опыты. Скоро, пока недоступно'])
    memory('explore-home')
    key(23)
    checkpoint('03_weather_intro', settle=.8, screen='INTRO', focus='start', visit='true')
    assert 'visit_started content=adv-weather-lab journey=opened' in logs()
    memory('weather-intro')
    key(23)
    wait(screen='PREDICTION', focus='place:SUN')
    key(22)
    checkpoint('04_prediction', settle=.6, screen='PREDICTION', focus='place:SHADE')
    accessibility('prediction', 'Положить лёд в тень. Предположение', present=['Положить лёд на солнце. Предположение'])
    key(23, .2)
    wait(screen='PLACING_ICE', hypothesis='SHADE')
    wait(screen='EXPERIMENT', focus='speed')
    checkpoint('05_experiment', settle=.3, screen='EXPERIMENT', beat='MID', hypothesis='SHADE')
    memory('experiment')
    key(23, .3)  # "Ускорить время": same deterministic clock, time-lapse only
    checkpoint('06_observation', settle=.8, screen='RESULT', focus='why', hypothesis='SHADE')
    assert 'experiment_observed content=adv-weather-lab experiment=where-melts-faster variant=shade' in logs()
    key(23, .12)
    entry = checkpoint('ask_entry', capture=False, screen='ASK', cause='SUN', canBuild='false', focus='explanation:CHAIN')
    premature_select('sun', entry)
    heat = checkpoint('ask_heat', capture=False, screen='ASK', cause='HEAT', canBuild='false')
    premature_select('heat', heat, then=lambda: shot('07_ask_lumi_running'))
    for cause in ['ICE', 'WATER']:
        stage = checkpoint(f'ask_{cause.lower()}', capture=False, screen='ASK', cause=cause, canBuild='false')
        premature_select(cause.lower(), stage)
    done = checkpoint('ask_done_first', capture=False, screen='ASK', cause='DONE', canBuild='true', focus='build')
    assert_sequence('first', entry, done)
    # Repeat with the remote: RIGHT to Repeat, OK. The next experiment locks again until DONE.
    time.sleep(.8)
    key(22, .3); wait(screen='ASK', focus='repeat', canBuild='true')
    key(23, .3)
    repeat_start = checkpoint('ask_repeat_locks_next', capture=False, screen='ASK', cause='SUN', canBuild='false', focus='repeat')
    done = checkpoint('08_ask_lumi_done', settle=.6, screen='ASK', cause='DONE', canBuild='true', focus='build')
    assert_sequence('repeat', repeat_start, done)
    accessibility('ask-done', NEXT, present=[NEXT], absent=[EXPLANATION])
    key(23, .5)
    checkpoint('09_build_choose', settle=.6, screen='BUILD_CHOOSE', focus='shelter:CANOPY', canCheck='false')
    accessibility('build-choose', 'Поставить: Навес', present=['Поставить: Навес', 'Поставить: Зонтик из листьев'], absent=['Проверить'])
    key(23, .3)
    placed = checkpoint('10_build_placed', settle=.6, screen='BUILD_PLACED', shelter='CANOPY', canCheck='true', focus='check')
    memory('build-test')
    time.sleep(2.5)
    assert matches(latest(), {'screen': 'BUILD_PLACED', 'shelter': 'CANOPY'}), 'result appeared before Check'
    assert 'visit_completed' not in logs()
    key(23, .3)
    wait(screen='BUILD_RUNNING', shelter='CANOPY')
    checkpoint('11_build_result', settle=.8, screen='BUILD_RESULT', shelter='CANOPY', focus='change', completed='true')
    assert 'visit_completed content=adv-weather-lab' in logs()
    key(22, .3); wait(screen='BUILD_RESULT', focus='home')
    key(23, .8)
    checkpoint('12_explore_return', settle=.6, screen='HOME', focus='destination:WEATHER', visit='false')
    assert 'visit_finished content=adv-weather-lab reason=explore_home completed=true' in logs()
    key(4, 1.5)
    home_check('13_home_return')
    time.sleep(1.2)
    stop_recording()
    added = [e for e in history() if e not in history_before]
    assert len(added) == 1 and added[0]['contentId'] == 'adv-weather-lab' and added[0]['kind'] == 'INTERACTIVE', added
    assert added[0]['topics'] == ['adventure', 'explore'] and added[0]['durationMs'] > 0, added
    checks.append({'name': 'history-main-flow-exactly-once', 'added': added})
    (OUT / 'logs/history-main-flow.json').write_text(json.dumps({'previousWeatherEvents': len([e for e in history_before if e.get('contentId') == 'adv-weather-lab']),
                                                                 'added': added}, ensure_ascii=False, indent=2))

    # ---- Supplemental production checks (outside the continuous recording) ----
    key(23, 1.2)
    wait(screen='HOME', focus='destination:WEATHER')
    key(22, .3)
    wait(screen='HOME', focus='destination:HOW')
    history_count = len(history())
    key(23, .8)
    selected = [line for line in state_lines() if 'action=Select' in line][-1]
    assert matches(selected, {'screen': 'HOME', 'focus': 'destination:HOW', 'visit': 'false'}), selected
    shot('extra_future_how')
    assert len(history()) == history_count
    checks.append({'name': 'future-destination-honest', 'state': selected, 'visitStarted': False, 'historyUnchanged': True})
    key(21, .3); wait(screen='HOME', focus='destination:WEATHER')
    key(23); wait(screen='INTRO', visit='true')
    key(23); wait(screen='PREDICTION', focus='place:SUN')
    key(23, .2); wait(screen='EXPERIMENT', hypothesis='SUN')
    wait(screen='EXPERIMENT', beat='MID')
    pause_resume('experiment', 'EXPERIMENT', ('elapsed', 'hypothesis', 'focus', 'beat'))
    wait(screen='RESULT', hypothesis='SUN', timeout=30)
    key(23); ask_entry = wait(screen='ASK', cause='SUN', canBuild='false')
    wait(screen='ASK', cause='HEAT')
    pause_resume('ask-running', 'ASK')
    assert 'canBuild=false' in latest() and 'focus=build' not in latest()
    wait(screen='ASK', cause='DONE', canBuild='true', focus='build')
    key(23); wait(screen='BUILD_CHOOSE', focus='shelter:CANOPY')
    key(22, .3); wait(screen='BUILD_CHOOSE', focus='shelter:LEAVES')
    key(23); wait(screen='BUILD_PLACED', shelter='LEAVES', canCheck='true', focus='check')
    pause_resume('placed-shelter', 'BUILD_PLACED')
    assert matches(latest(), {'screen': 'BUILD_PLACED', 'shelter': 'LEAVES', 'canCheck': 'true'})
    count_before_check = len(weather_history())
    key(23); wait(screen='BUILD_RESULT', shelter='LEAVES')
    assert len(weather_history()) == count_before_check + 1, 'second visit completes once'
    # Back chain returns through the world to Home with restored focus.
    key(4); checkpoint('back_result_to_choose', capture=False, screen='BUILD_CHOOSE', focus='shelter:LEAVES')
    key(4); checkpoint('back_choose_to_ask_done', capture=False, screen='ASK', cause='DONE', canBuild='true', focus='build')
    key(4); checkpoint('back_ask_to_result', capture=False, screen='RESULT', focus='why')
    key(4); checkpoint('back_result_to_intro', capture=False, screen='INTRO', focus='start')
    key(4); checkpoint('back_intro_to_explore', capture=False, screen='HOME', focus='destination:WEATHER', visit='false')
    key(4, 1.5); home_check('back_explore_to_home', capture=False)
    assert len(weather_history()) == count_before_check + 1, 'Back never adds history'

    # Existing catalog card now opens the same production Weather loop and returns to Home.
    key(20, .5)
    card = find_catalog_weather_card()
    shot('extra_catalog_card')
    key(23, 1.5)
    details = ui('catalog-details')
    assert any(n.get('text') in ('Начать', 'Продолжить') and n.get('focused') == 'true' for n in details), 'details start focus'
    key(23, 1.5)
    checkpoint('extra_catalog_weather_intro', screen='INTRO', focus='start', visit='true')
    key(4, 1.5)
    top = resumed()
    assert '.home.HomeActivity' in top, top
    checks.append({'name': 'catalog-route', 'card': card, 'openedWeatherIntro': True, 'backReturned': top})
    assert len(weather_history()) == count_before_check + 1 + 1, 'ContentDetails keeps its existing start record'

    full = adb('logcat', '-d', '-v', 'threadtime', '-T', epoch)
    assert 'StitchExploreWeatherPreviewActivity' not in full
    assert 'AdventureActivity' not in full, 'old weather adventure opened'
    assert not re.search(r'FATAL EXCEPTION|ANR in ru\.familytv|Fatal signal', full)
    assert not re.search(r'\bAI=1\b', logs() + logs('FamilyHome:I'))
    checks.append({'name': 'production-only-no-crash-no-ai', 'ai': 0, 'debugEntry': False, 'legacyAdventure': False})
    print('PASS', len(checks), 'checks', flush=True)
finally:
    stop_recording()
    full = adb('logcat', '-d', '-v', 'threadtime', '-T', epoch)
    (OUT / 'logs/state.log').write_text(logs())
    (OUT / 'logs/home.log').write_text(logs('FamilyHome:I'))
    (OUT / 'logs/logcat.txt').write_text('\n'.join(s for s in full.splitlines() if any(t in s for t in
        ['FamilyExplore', 'FamilyHome', 'FamilyJourney', 'VoiceRuntime', 'ru.familytv', 'AndroidRuntime', 'FATAL EXCEPTION', 'Fatal signal', 'ANR'])) + '\n')
    (OUT / 'logs/checks.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2) + '\n')
    (OUT / 'logs/performance.json').write_text(json.dumps(measurements, indent=2) + '\n')
    stores_after = {name: store(name) for name in STORES}
    (OUT / 'logs/stores-delta.json').write_text(json.dumps({name: {'before': stores_before[name], 'after': stores_after[name],
        'changed': stores_before[name] != stores_after[name]} for name in STORES}, ensure_ascii=False, indent=2) + '\n')
