#!/usr/bin/env python3
"""D-pad acceptance of the production Games V2; all acceptance enters through production Home.

--normal-animation temporarily sets 1x animator duration and restores it in finally.
No app-data reset. A continuous native screenrecord covers the actual interaction.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/v1/games-production'
p = argparse.ArgumentParser()
p.add_argument('--serial', default='192.168.68.105:5555')
p.add_argument('--normal-animation', action='store_true')
args = p.parse_args()
ADB = ['adb', '-s', args.serial]
COMPONENT = 'ru.familytv/.home.HomeActivity'
for folder in ['screenshots', 'recordings', 'logs']:
    (OUT / folder).mkdir(parents=True, exist_ok=True)
checks = []

def adb(*parts, binary=False, check=True, timeout=30):
    r = subprocess.run(ADB + list(parts), check=check, capture_output=True, timeout=timeout)
    return r.stdout if binary else r.stdout.decode(errors='replace')

def logs():
    return adb('logcat', '-d', '-v', 'threadtime', '-T', epoch, '-s', 'FamilyGames:I')

def latest():
    lines = [s for s in logs().splitlines() if 'world=' in s]
    assert lines, 'No production state log'
    return lines[-1]

def key(code, delay=.35):
    adb('shell', 'input', 'keyevent', str(code))
    time.sleep(delay)

def screenshot(name):
    (OUT / 'screenshots' / f'{name}.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))

def checkpoint(name, world, capture=True, **fields):
    activity = adb('shell', 'dumpsys', 'activity', 'activities')
    resumed = next((s.strip() for s in activity.splitlines() if 'ResumedActivity' in s), '')
    assert '.games.GameHubActivity' in resumed, resumed
    state = latest()
    for field, value in dict(world=world, **fields).items():
        assert re.search(r'\b' + field + '=' + re.escape(str(value)) + r'(?:\s|$)', state), (name, fields, state)
    checks.append(dict(name=name, resumed=resumed, state=state))
    print(name, state, flush=True)
    if capture: screenshot(name)

def wait_for(predicate, timeout=10):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        state = latest()
        if predicate(state):
            return state
        time.sleep(.08)
    raise AssertionError(('Timed out', state))

def accessibility(name, expected_answers, focus_description):
    remote = '/sdcard/familytv-games-polish-ui.xml'
    adb('shell', 'uiautomator', 'dump', remote)
    xml = adb('shell', 'cat', remote)
    (OUT / 'logs' / f'{name}.xml').write_text(xml)
    nodes = list(ET.fromstring(xml).iter('node'))
    answers = [n for n in nodes if n.get('content-desc') in ['4 яблок', '5 яблок', '6 яблок']]
    assert len(answers) == expected_answers, (name, len(answers))
    if expected_answers:
        assert all(n.get('focusable') == 'true' and n.get('enabled') == 'true' for n in answers)
    focus = [n.get('content-desc') for n in nodes if n.get('focused') == 'true']
    assert focus_description in focus, (name, focus)
    checks.append(dict(name=name, answer_nodes=len(answers), focus=focus))

def help_count(prefix, capture=True):
    # Readback/compression of a 1080p PNG can outlast an 850ms beat on H728.
    # Count frames are decoded from the continuous video after the run, without pausing it.
    start = len(logs().splitlines())
    key(23, 0)
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        segment = '\n'.join(logs().splitlines()[start:])
        if 'count_end ' in segment:
            break
        time.sleep(.12)
    else:
        raise AssertionError(('Count did not finish', segment))
    checkpoint(f'{prefix}_finished', 'MATH', feedback='HELP', equation='?', focusKey='help', mathChoice=4, choices=3, counting='false')
    counted = re.findall(r'\bcount=(\d+)\b', segment)
    assert counted == ['1', '2', '3', '4', '5'], counted
    assert 'count_narration=Посчитаем вместе. Один, два, три, четыре, пять.' in segment
    checks.append(dict(name=prefix, numbers=counted, submitted_answer=4, equation='?', focus='help'))


def extract_count_frames(video):
    # Detect the actual amber number circles in the existing recording, never synthesize them.
    # These are reference-canvas coordinates of the five labels above the combined apples.
    rate, width, height = 20, 400, 70
    proc = subprocess.Popen(['ffmpeg', '-v', 'error', '-i', str(video), '-map', '0:v:0', '-vf',
                             'fps=20,crop=400:70:720:330', '-pix_fmt', 'rgb24', '-f', 'rawvideo', '-'],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    samples = {n: [] for n in range(1, 6)}
    frame_index, reached, complete = 0, 0, False
    while True:
        data = proc.stdout.read(width * height * 3)
        if not data:
            break
        assert len(data) == width * height * 3
        if not complete:
            present = []
            for x in [66, 128, 190, 252, 314]:
                amber = 0
                for yy in range(23, 48):
                    for xx in range(x - 12, x + 13):
                        r, g, b = data[(yy * width + xx) * 3:(yy * width + xx) * 3 + 3]
                        amber += r > 225 and 150 < g < 220 and b < 80
                present.append(amber / 625 > .55)
            n = sum(present)
            prefix = present == [i < n for i in range(5)]
            if prefix and n > 0 and n in [reached, reached + 1]:
                reached = n
                samples[n].append(frame_index / rate)
            elif reached == 5:
                complete = True
        frame_index += 1
    error = proc.stderr.read().decode()
    assert proc.wait() == 0, error
    chosen = {}
    for n, times in samples.items():
        assert len(times) >= 3, (n, times)
        t = times[len(times) // 2]
        chosen[n] = t
        subprocess.run(['ffmpeg', '-v', 'error', '-ss', str(t), '-i', str(video), '-frames:v', '1', '-y',
                        str(OUT / 'screenshots' / f'05_help_count_{n}.png')], check=True)
    (OUT / 'logs/count-frame-times.json').write_text(json.dumps(dict(source=str(video), method='Full frames decoded from first 1..5 sequence; selected using existing amber label pixels; no compositing', seconds=chosen), indent=2))


def ui(name=None):
    remote = '/sdcard/familytv-games-production-ui.xml'
    adb('shell', 'uiautomator', 'dump', remote)
    xml = adb('shell', 'cat', remote)
    if name: (OUT / 'logs' / f'{name}.xml').write_text(xml)
    return list(ET.fromstring(xml).iter('node'))

def home_check(name, capture=True):
    activity = adb('shell', 'dumpsys', 'activity', 'activities')
    resumed = next(s.strip() for s in activity.splitlines() if 'ResumedActivity' in s)
    assert '.home.HomeActivity' in resumed, resumed
    nodes = ui(name)
    focus = [n.get('content-desc') for n in nodes if n.get('focused') == 'true']
    assert 'Играть' in focus, focus
    assert len([n for n in nodes if n.get('content-desc') == 'Играть']) == 1
    checks.append(dict(name=name, resumed=resumed, focus=focus))
    if capture: screenshot(name)
    print(name, focus, flush=True)

def focus_games_from_home():
    # Move with actual D-pad; never inject a route, focus preference, answer or saved state.
    for _ in range(18):
        nodes = ui()
        focus = next((n for n in nodes if n.get('focused') == 'true'), None)
        if focus is not None and focus.get('content-desc') == 'Играть': return
        if focus is not None and (focus.get('content-desc') == 'Смотреть видео' or focus.get('text') == 'Смотреть'):
            key(22)
        elif focus is not None and focus.get('text') in ['Наши открытия', 'Наши истории', 'Карта']:
            key(21)
        else:
            key(19)
    raise AssertionError('Could not focus Games using D-pad')

def memory(label):
    info = adb('shell', 'dumpsys', 'meminfo', 'ru.familytv')
    (OUT / 'logs' / f'pss-{label}.txt').write_text(info)
    pid = adb('shell', 'pidof', 'ru.familytv').strip()
    maps = adb('shell', 'run-as', 'ru.familytv', 'cat', f'/proc/{pid}/maps')
    (OUT / 'logs' / f'maps-{label}.txt').write_text(maps)
    assert '.gguf' not in maps.lower() and '.onnx' not in maps.lower()
    pss = re.search(r'TOTAL PSS:\s*(\d+)', info)
    if not pss: pss = re.search(r'^\s*TOTAL\s+(\d+)', info, re.M)
    assert pss, info
    sample = dict(surface=label, pid=int(pid), pss_kb=int(pss[1]), mapped_models=[])
    measurements.append(sample)
    print('memory', sample, flush=True)

def game_history():
    xml = adb('shell', 'run-as', 'ru.familytv', 'cat', 'shared_prefs/familytv_activity.xml', check=False)
    if not xml.startswith('<?xml'): return []
    return [e for n in ET.fromstring(xml).iter('string') if n.get('name', '').startswith('day:')
            for e in json.loads(n.text) if e['kind'] == 'GAME']

old_scale = adb('shell', 'settings', 'get', 'global', 'animator_duration_scale').strip()
record = None
record_log = None
measurements = []
epoch = adb('shell', 'date', '+%s').strip() + '.000'
try:
    if args.normal_animation:
        adb('shell', 'settings', 'put', 'global', 'animator_duration_scale', '1')
    info = {k: adb('shell', 'getprop', v).strip() for k, v in {
        'model': 'ro.product.model', 'hardware': 'ro.hardware', 'android': 'ro.build.version.release', 'abi': 'ro.product.cpu.abi'
    }.items()}
    info.update(size=adb('shell', 'wm', 'size').strip(), animator_duration_scale=adb('shell', 'settings', 'get', 'global', 'animator_duration_scale').strip())
    (OUT / 'logs/device.json').write_text(json.dumps(info, indent=2))
    before_history = game_history()
    adb('shell', 'am', 'force-stop', 'ru.familytv')
    (OUT / 'logs/launch.txt').write_text(adb('shell', 'am', 'start', '-W', '-n', COMPONENT))
    time.sleep(2)
    focus_games_from_home()
    memory('home')
    remote_video = '/sdcard/familytv-games-production-full.mp4'
    record_log = (OUT / 'logs/screenrecord.txt').open('wb')
    record = subprocess.Popen(ADB + ['shell', 'screenrecord', '--size', '1920x1080', '--bit-rate', '6000000', '--time-limit', '170', remote_video], stdout=record_log, stderr=subprocess.STDOUT)
    time.sleep(.6)
    home_check('01_home_games_focus')
    key(23, 1.5)
    checkpoint('02_games_home', 'HOME', focusKey='game:0', AI=0)
    memory('games-home')
    key(23)
    checkpoint('03_math_world', 'MATH', combined='false', choices=0, equation='hidden', focusKey='world', mathChoice='null', AI=0)
    memory('math')
    key(20); key(22)
    checkpoint('03a_no_answers_before_combine', 'MATH', choices=0, focusKey='world', mathChoice='null')
    accessibility('stage_a', 0, 'Яблоки: 2 и 3. OK: соединить в одну корзину')
    key(23, .10)
    # Log proves the animation interval; screenshot follows native display timing.
    checkpoint('03b_combine', 'MATH', combining='true', combined='false', choices=0, equation='hidden')
    wait_for(lambda s: 'combined=true' in s and 'focusKey=choice:0' in s)
    checkpoint('04_math_combined', 'MATH', feedback='READY', choices=3, equation='?', focusKey='choice:0', mathChoice='null')
    accessibility('stage_b', 3, '4 яблок')
    key(23, .25)
    checkpoint('06_math_wrong', 'MATH', feedback='WRONG', equation='?', mathChoice=4)
    key(19)
    help_count('05_help')
    screenshot('05_math_help')
    accessibility('help_finished', 3, '?  Подсказка')
    help_count('05_repeat_help', capture=False)
    key(20); key(22)
    checkpoint('05a_child_choice', 'MATH', focusKey='choice:1', feedback='HELP', equation='?', mathChoice=4)
    key(23, .35)
    checkpoint('07_math_correct', 'MATH', feedback='CORRECT', equation=5, mathChoice=5, round=0)
    for i in [1, 2]:
        key(23)
        checkpoint(f'07_round_{i+1}_world', 'MATH', combined='false', choices=0, equation='hidden', focusKey='world', round=i)
        key(23, 1.5); key(22); key(23, 1.8)
        checkpoint(f'07_round_{i+1}_correct', 'MATH', feedback='CORRECT', round=i)
    key(23)
    checkpoint('07_finite_return', 'HOME', focusKey='game:0')
    key(22); key(23)
    checkpoint('08_color', 'COLOR', feedback='READY', painted='null', focusKey='choice:1', AI=0)
    memory('color')
    key(21); key(23, 1.3)
    checkpoint('09_color_wrong', 'COLOR', feedback='WRONG', painted='RED')
    time.sleep(1)
    checkpoint('09a_red_persists', 'COLOR', feedback='WRONG', painted='RED')
    key(22); key(23, 1.3)
    checkpoint('10_color_correct', 'COLOR', feedback='CORRECT', painted='BLUE', launched='false')
    time.sleep(1.5)
    checkpoint('10a_explicit_launch_wait', 'COLOR', painted='BLUE', launched='false')
    key(23, .2)
    screenshot('11_rocket_launch')
    time.sleep(2)
    checkpoint('11a_launch_complete', 'COLOR', launched='true')
    key(23)
    checkpoint('11b_games_return', 'HOME', focusKey='game:1')
    key(4, 1)
    home_check('12_home_return')
    time.sleep(1)
    pid = adb('shell', 'pidof', 'screenrecord', check=False).strip()
    if pid: adb('shell', 'kill', '-2', *pid.split())
    record.wait(timeout=20); record = None
    record_log.close(); record_log = None
    adb('pull', remote_video, str(OUT / 'recordings/games-production-full.mp4'), timeout=60)
    after_history = game_history()
    added = [e for e in after_history if e not in before_history]
    assert [e['contentId'] for e in added] == ['game-math', 'game-color'], added
    assert all(e['durationMs'] > 0 for e in added)
    (OUT / 'logs/history-main-flow.json').write_text(json.dumps(dict(previous_game_events=len(before_history), added=added), indent=2, ensure_ascii=False))
    checks.append(dict(name='production_history_exactly_once', content_ids=[e['contentId'] for e in added]))
    extract_count_frames(OUT / 'recordings/games-production-full.mp4')

    # Supplemental real production flow: unavailable cards, Back, Repeat and lifecycle.
    key(23, 1)
    key(22); key(22); key(23)
    checkpoint('13_weather_unavailable', 'HOME', focusKey='game:2')
    key(22); key(23)
    checkpoint('14_maze_unavailable', 'HOME', focusKey='game:3')
    key(21); key(21); key(21); key(23)
    key(21); key(23)  # Repeat from world-only stage
    assert 'repeat narration_requested' in logs()
    key(22); key(23, 1.5); key(23, 1.3); key(19); key(23, 1.1)
    original_pid = adb('shell', 'pidof', 'ru.familytv').strip()
    history_count = len(game_history())
    adb('shell', 'am', 'start', '-f', '0x58000000', '-a', 'android.settings.SETTINGS')
    time.sleep(.5)
    for _ in range(4):
        key(4, .8)
        activity = adb('shell', 'dumpsys', 'activity', 'activities')
        resumed = next(s for s in activity.splitlines() if 'ResumedActivity' in s)
        if '.games.GameHubActivity' in resumed: break
    assert '.games.GameHubActivity' in resumed, resumed
    assert adb('shell', 'pidof', 'ru.familytv').strip() == original_pid
    assert len(game_history()) == history_count
    checkpoint('15_help_pause_resume', 'MATH', feedback='HELP', equation='?', counting='false', focusKey='help', mathChoice=4)
    help_count('16_help_after_resume', capture=False)
    key(4)
    checkpoint('17_back_restores_math', 'HOME', focusKey='game:0')
    key(22); key(23); key(21); key(23, 1.4)
    adb('shell', 'am', 'start', '-f', '0x58000000', '-a', 'android.settings.SETTINGS')
    time.sleep(.5)
    key(4, .8)
    checkpoint('18_color_pause_keeps_red', 'COLOR', feedback='WRONG', painted='RED', focusKey='choice:0')
    key(4)
    checkpoint('19_back_restores_color', 'HOME', focusKey='game:1')
    key(4, .8)
    home_check('20_final_home')
    state_log = logs()
    assert not re.search(r'\bAI=1\b', state_log)
    for reaction in ['HELP', 'WRONG', 'SUCCESS', 'LAUNCH']:
        assert f'lumi={reaction} start' in state_log, reaction
    full = adb('logcat', '-d', '-v', 'threadtime', '-T', epoch)
    assert 'StitchGamesPreviewActivity' not in full
    assert not re.search(r'FATAL EXCEPTION|ANR in ru.familytv|Fatal signal', full)
    checks.append(dict(name='production_only_no_crash_no_ai', ai=0, debug_entry=False))
    print('PASS', len(checks), 'checks', flush=True)
finally:
    if record is not None:
        pid = adb('shell', 'pidof', 'screenrecord', check=False).strip()
        if pid: adb('shell', 'kill', '-2', *pid.split(), check=False)
        record.wait(timeout=20)
        if record_log: record_log.close()
        adb('pull', remote_video, str(OUT / 'recordings/games-production-partial.mp4'), check=False, timeout=60)
    if args.normal_animation:
        if old_scale == 'null': adb('shell', 'settings', 'delete', 'global', 'animator_duration_scale')
        else: adb('shell', 'settings', 'put', 'global', 'animator_duration_scale', old_scale)
    after = adb('shell', 'settings', 'get', 'global', 'animator_duration_scale').strip()
    (OUT / 'logs/animation-setting.json').write_text(json.dumps(dict(before=old_scale, during='1' if args.normal_animation else old_scale, after=after, restored=old_scale==after), indent=2))
    text = adb('logcat', '-d', '-v', 'threadtime', '-T', epoch)
    (OUT / 'logs/logcat.txt').write_text('\n'.join(s for s in text.splitlines() if any(token in s for token in ['FamilyGames', 'FamilyHome', 'VoiceRuntime', 'ru.familytv', 'AndroidRuntime', 'FATAL EXCEPTION', 'Fatal signal'])) + '\n')
    (OUT / 'logs/checks.json').write_text(json.dumps(checks, indent=2, ensure_ascii=False))
    (OUT / 'logs/performance.json').write_text(json.dumps(measurements, indent=2))
