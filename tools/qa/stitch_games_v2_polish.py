#!/usr/bin/env python3
"""D-pad acceptance of the debug Games V2 UX polish; preserves the earlier preview evidence.

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
OUT = ROOT / 'docs/design/stitch/games-v2/ux-polish'
p = argparse.ArgumentParser()
p.add_argument('--serial', default='192.168.68.105:5555')
p.add_argument('--normal-animation', action='store_true')
args = p.parse_args()
ADB = ['adb', '-s', args.serial]
COMPONENT = 'ru.familytv/.games.stitch.StitchGamesPreviewActivity'
for folder in ['screenshots', 'recordings', 'logs']:
    (OUT / folder).mkdir(parents=True, exist_ok=True)
checks = []

def adb(*parts, binary=False, check=True, timeout=30):
    r = subprocess.run(ADB + list(parts), check=check, capture_output=True, timeout=timeout)
    return r.stdout if binary else r.stdout.decode(errors='replace')

def logs():
    return adb('logcat', '-d', '-v', 'threadtime', '-T', epoch, '-s', 'StitchGames:I')

def latest():
    lines = [s for s in logs().splitlines() if 'world=' in s]
    assert lines, 'No preview state log'
    return lines[-1]

def key(code, delay=.35):
    adb('shell', 'input', 'keyevent', str(code))
    time.sleep(delay)

def screenshot(name):
    (OUT / 'screenshots' / f'{name}.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))

def checkpoint(name, world, **fields):
    activity = adb('shell', 'dumpsys', 'activity', 'activities')
    resumed = next((s.strip() for s in activity.splitlines() if 'ResumedActivity' in s), '')
    assert '.games.stitch.StitchGamesPreviewActivity' in resumed, resumed
    state = latest()
    for field, value in dict(world=world, **fields).items():
        assert re.search(r'\b' + field + '=' + re.escape(str(value)) + r'(?:\s|$)', state), (name, fields, state)
    checks.append(dict(name=name, resumed=resumed, state=state))
    print(name, state, flush=True)
    screenshot(name)

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
                        str(OUT / 'screenshots' / f'08_help_count_{n}.png')], check=True)
    (OUT / 'logs/count-frame-times.json').write_text(json.dumps(dict(source=str(video), method='Full frames decoded from first 1..5 sequence; selected using existing amber label pixels; no compositing', seconds=chosen), indent=2))

old_scale = adb('shell', 'settings', 'get', 'global', 'animator_duration_scale').strip()
record = None
record_log = None
epoch = adb('shell', 'date', '+%s').strip() + '.000'
try:
    if args.normal_animation:
        adb('shell', 'settings', 'put', 'global', 'animator_duration_scale', '1')
    info = {key: adb('shell', 'getprop', value).strip() for key, value in {
        'model': 'ro.product.model', 'hardware': 'ro.hardware', 'android': 'ro.build.version.release', 'abi': 'ro.product.cpu.abi'
    }.items()}
    info.update(size=adb('shell', 'wm', 'size').strip(), animator_duration_scale=adb('shell', 'settings', 'get', 'global', 'animator_duration_scale').strip())
    (OUT / 'logs/device.json').write_text(json.dumps(info, indent=2))
    adb('shell', 'am', 'force-stop', 'ru.familytv')
    (OUT / 'logs/launch.txt').write_text(adb('shell', 'am', 'start', '-W', '-n', COMPONENT))
    wait_for(lambda s: 'world=HOME' in s and 'focusKey=game:0' in s)
    time.sleep(1)
    remote_video = '/sdcard/familytv-games-v2-polish.mp4'
    record_log = (OUT / 'logs/screenrecord.txt').open('wb')
    record = subprocess.Popen(ADB + ['shell', 'screenrecord', '--size', '1920x1080', '--bit-rate', '6000000', '--time-limit', '170', remote_video], stdout=record_log, stderr=subprocess.STDOUT)
    time.sleep(.6)
    checkpoint('01_games_home', 'HOME')
    key(23)
    checkpoint('02_math_world_only', 'MATH', combined='false', choices=0, equation='hidden', focusKey='world', mathChoice='null')
    key(20); key(22)  # these used to navigate to the quiz choices
    checkpoint('03_before_combine_no_answer_focus', 'MATH', choices=0, focusKey='world', mathChoice='null')
    accessibility('stage_a', 0, 'Яблоки: 2 и 3. OK: соединить в одну корзину')
    key(23, .10)
    checkpoint('04_combine_in_progress', 'MATH', combining='true', combined='false', choices=0, equation='hidden')
    wait_for(lambda s: 'combined=true' in s and 'focusKey=choice:0' in s)
    checkpoint('05_answers_available', 'MATH', feedback='READY', choices=3, equation='?', focusKey='choice:0', mathChoice='null')
    accessibility('stage_b', 3, '4 яблок')
    key(23, .20)
    checkpoint('06_math_wrong_lumi', 'MATH', feedback='WRONG', equation='?', mathChoice=4)
    time.sleep(1.2)
    checkpoint('07_math_wrong_settled', 'MATH', feedback='WRONG', mathChoice=4)
    key(19)
    help_count('08_help')
    accessibility('help_finished', 3, '?  Подсказка')
    help_count('09_repeated_help', capture=False)
    key(20)
    checkpoint('10_child_returns_to_4', 'MATH', focusKey='choice:0', equation='?', mathChoice=4)
    key(22)
    checkpoint('11_child_selects_5', 'MATH', focusKey='choice:1', feedback='HELP', equation='?', mathChoice=4)
    key(23, .35)
    checkpoint('12_math_success_lumi', 'MATH', feedback='CORRECT', equation=5, mathChoice=5)
    time.sleep(1.4)
    checkpoint('13_math_success_settled', 'MATH', feedback='CORRECT')
    key(4); key(22); key(23)
    checkpoint('14_color_idle', 'COLOR', feedback='READY', painted='null', focusKey='choice:1')
    key(21); key(23, 1.3)
    checkpoint('15_color_wrong_lumi', 'COLOR', feedback='WRONG', painted='RED')
    time.sleep(1)
    checkpoint('16_red_wing_persists', 'COLOR', feedback='WRONG', painted='RED')
    key(22); key(23, 1.3)
    checkpoint('17_color_success_lumi', 'COLOR', feedback='CORRECT', painted='BLUE', launched='false')
    time.sleep(1.5)
    checkpoint('18_blue_wing_waits_for_launch', 'COLOR', painted='BLUE', launched='false')
    key(23, .2)
    screenshot('19_launch_lumi')
    time.sleep(2)
    checkpoint('20_launch_complete', 'COLOR', launched='true')
    key(23, 1)
    checkpoint('21_calm_return', 'HOME', focusKey='game:1')
    key(21); key(22)  # an explicit final focus event is also present on the VFR video tail
    time.sleep(1)
    pid = adb('shell', 'pidof', 'screenrecord', check=False).strip()
    if pid: adb('shell', 'kill', '-2', *pid.split())
    record.wait(timeout=20); record = None
    record_log.close(); record_log = None
    adb('pull', remote_video, str(OUT / 'recordings/games-v2-ux-polish.mp4'), timeout=60)

    extract_count_frames(OUT / 'recordings/games-v2-ux-polish.mp4')

    # All later rounds must start with the world and require explicit numeric selection.
    key(21); key(23)
    for i in range(3):
        checkpoint(f'22_round_{i + 1}_world', 'MATH', combined='false', choices=0, equation='hidden', focusKey='world')
        key(23, 1.5); key(22); key(23, 1.9)
        checkpoint(f'23_round_{i + 1}_explicit_correct', 'MATH', feedback='CORRECT', round=i)
        key(23)
    checkpoint('24_finite_return', 'HOME')

    # Pausing during Help must cancel counting, retain ?, and allow a fresh deterministic Help.
    key(23); key(23, 1.5); key(23, 1.3); key(19); key(23, 1.1)
    adb('shell', 'am', 'start', '-a', 'android.settings.SETTINGS')
    time.sleep(.5)
    # Settings may reuse a nested task; resume the existing preview explicitly.
    adb('shell', 'am', 'start', '--activity-single-top', '-n', COMPONENT)
    time.sleep(.7)
    checkpoint('25_help_pause_resume', 'MATH', feedback='HELP', equation='?', counting='false', focusKey='help', mathChoice=4)
    help_count('26_help_after_resume', capture=False)
    key(4)
    checkpoint('27_back_restores_math', 'HOME', focusKey='game:0')
    state_log = logs()
    for reaction in ['HELP', 'WRONG', 'SUCCESS', 'LAUNCH']:
        assert f'lumi={reaction} start' in state_log, reaction
    (OUT / 'logs/memory-final.txt').write_text(adb('shell', 'dumpsys', 'meminfo', 'ru.familytv'))
    print('PASS', len(checks), 'checks', flush=True)
finally:
    if record is not None:
        pid = adb('shell', 'pidof', 'screenrecord', check=False).strip()
        if pid: adb('shell', 'kill', '-2', *pid.split(), check=False)
        record.wait(timeout=20)
        if record_log: record_log.close()
        adb('pull', remote_video, str(OUT / 'recordings/games-v2-ux-polish-partial.mp4'), check=False, timeout=60)
    if args.normal_animation:
        if old_scale == 'null': adb('shell', 'settings', 'delete', 'global', 'animator_duration_scale')
        else: adb('shell', 'settings', 'put', 'global', 'animator_duration_scale', old_scale)
    after = adb('shell', 'settings', 'get', 'global', 'animator_duration_scale').strip()
    (OUT / 'logs/animation-setting.json').write_text(json.dumps(dict(before=old_scale, during='1' if args.normal_animation else old_scale, after=after, restored=old_scale==after), indent=2))
    text = adb('logcat', '-d', '-v', 'threadtime', '-T', epoch)
    (OUT / 'logs/logcat.txt').write_text('\n'.join(s for s in text.splitlines() if any(token in s for token in ['StitchGames', 'ru.familytv', 'AndroidRuntime', 'FATAL EXCEPTION', 'Fatal signal'])) + '\n')
    (OUT / 'logs/checks.json').write_text(json.dumps(checks, indent=2, ensure_ascii=False))
