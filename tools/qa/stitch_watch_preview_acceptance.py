#!/usr/bin/env python3
"""Native Watch Flow evidence. Requires an already-installed debug APK; preserves app data.
The capture run opens episode 1; use a fresh preview fixture for the first-view screenshot.
Never relabel fallback-device captures as H728.
"""
import argparse, json, re, subprocess, time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'docs/design/stitch/watch-v2/android-preview'
p=argparse.ArgumentParser();p.add_argument('--device',required=True);p.add_argument('--phase',choices=['capture','verify'],required=True)
a=p.parse_args(); ADB=['adb','-s',a.device];PKG='ru.familytv';WATCH=PKG+'/.home.stitch.watch.StitchWatchPreviewActivity'
for f in ['screenshots','recordings','logs']: (OUT/f).mkdir(parents=True,exist_ok=True)
def adb(*args,binary=False):
 r=subprocess.run(ADB+list(args),capture_output=True,check=True,timeout=90)
 return r.stdout if binary else r.stdout.decode(errors='replace')
def log(name,s): (OUT/'logs'/name).write_text(s)
def key(k,delay=.6): adb('shell','input','keyevent','KEYCODE_'+k);time.sleep(delay)
def shot(name):
 (OUT/'screenshots'/name).write_bytes(adb('exec-out','screencap','-p',binary=True));print('Captured',name,flush=True)
def process_log(): return adb('logcat','-d','--pid='+adb('shell','pidof',PKG).strip(),'-v','threadtime')
def focus(expected,player=False):
 matches=re.findall(r'StitchWatchPlayer: focus=(\S+)' if player else r'StitchWatch: screen=\S+ focus=(\S+)',process_log())
 assert matches and matches[-1]==expected,(expected,matches[-3:]);print('PASS focus',expected,flush=True)
def memory(name):
 data=adb('shell','dumpsys','meminfo',PKG);log('meminfo_'+name+'.txt',data)
 match=re.search(r'TOTAL PSS:\s*(\d+)',data);return int(match[1]) if match else None
info=adb('shell','getprop ro.product.model; getprop ro.soc.model; getprop ro.hardware; getprop ro.build.version.release; wm size; wm density')
assert 'h728' in info.lower(), 'This acceptance profile requires H728: '+info
log('device.txt',info)
if a.phase=='capture':
 adb('shell','am','force-stop',PKG)
 log('entry_start.txt',adb('shell','am','start','-W','-n',PKG+'/.home.stitch.StitchHomePreviewActivity'))
 time.sleep(2)
 remote='/sdcard/stitch_watch_flow_full.mp4'
 recorder=subprocess.Popen(ADB+['shell',f'screenrecord --size 1920x1080 --bit-rate 6000000 --time-limit 170 {remote} & rec=$!; echo REC_PID:$rec; wait $rec'],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
 first=recorder.stdout.readline().decode();pid=re.search(r'REC_PID:(\d+)',first)[1]
 try:
  key('DPAD_LEFT');key('DPAD_CENTER',2);focus('category:space');shot('01_watch_home_default.png')
  measurements={'watch_home_pss_kb':memory('watch_home')}
  key('DPAD_LEFT');focus('category:nature');shot('02_watch_home_focus.png')
  key('DPAD_RIGHT');key('DPAD_RIGHT');focus('category:how');shot('11_category_how_focus.png')
  key('DPAD_RIGHT');shot('12_category_stories_focus.png');key('DPAD_RIGHT');shot('13_category_creativity_focus.png')
  key('DPAD_LEFT');key('DPAD_LEFT');key('DPAD_LEFT');key('DPAD_CENTER');focus('video:stitch-watch-space-1');shot('03_browse_default.png')
  measurements['browse_pss_kb']=memory('browse')
  key('DPAD_RIGHT');focus('video:stitch-watch-space-2');shot('04_browse_focus.png')
  key('DPAD_RIGHT');focus('video:stitch-watch-space-3');shot('14_browse_third_focus.png')
  key('DPAD_LEFT');key('DPAD_LEFT');key('DPAD_CENTER');focus('play');shot('05_details.png')
  measurements['details_pss_kb']=memory('details')
  key('DPAD_CENTER',3);focus('play',True)
  assert re.search(r'ready id=stitch-watch-space-1 positionMs=\d+ durationMs=90000',process_log())
  key('DPAD_CENTER');shot('07_player_controls.png')
  measurements['player_pss_kb']=memory('player')
  key('DPAD_RIGHT');key('DPAD_CENTER');key('DPAD_LEFT');key('DPAD_LEFT');key('DPAD_CENTER')
  key('DPAD_RIGHT');key('DPAD_RIGHT');key('DPAD_RIGHT');focus('captions',True);key('DPAD_CENTER')
  key('DPAD_RIGHT');focus('audio',True);key('DPAD_CENTER');key('DPAD_CENTER')
  key('DPAD_LEFT');key('DPAD_LEFT');key('DPAD_LEFT');focus('play',True);key('DPAD_CENTER')
  time.sleep(6);shot('08_player_controls_hidden.png');assert 'controls_hidden' in process_log()
  key('BACK');focus('play');shot('06_details_continue.png')
  saved=int(re.findall(r'progress_saved id=stitch-watch-space-1 positionMs=(\d+)',process_log())[-1]);assert saved>=1000
  key('BACK');focus('video:stitch-watch-space-1');shot('10_return_focus.png')
  key('BACK');focus('category:space');shot('09_continue_watching.png')
  key('DPAD_UP');focus('continue');key('DPAD_CENTER');focus('play');key('DPAD_CENTER',2)
  requested=int(re.findall(r'resume_request id=stitch-watch-space-1 positionMs=(\d+)',process_log())[-1]);assert requested==saved,(saved,requested)
  ready=int(re.findall(r'ready id=stitch-watch-space-1 positionMs=(\d+)',process_log())[-1]);assert abs(ready-saved)<2000
  key('DPAD_CENTER');shot('15_resumed_player.png');key('BACK')
  measurements.update(saved_position_ms=saved,resume_request_ms=requested,resume_ready_ms=ready)
  log('measurements.json',json.dumps(measurements,indent=2))
 finally:
  adb('shell','kill','-2',pid)
  stdout,stderr=recorder.communicate(timeout=15)
  log('screenrecord.txt',first+stdout.decode(errors='replace')+stderr.decode(errors='replace'))
  adb('pull',remote,str(OUT/'recordings/watch_flow_full.mp4'))
  log('capture_logcat.txt',process_log())
 print('PASS continuous flow and real resume',flush=True)
else:
 adb('shell','am','force-stop',PKG);adb('shell','am','start','-W','-n',WATCH);time.sleep(2)
 focus('category:space')
 key('DPAD_LEFT')
 for category in ['nature','space','how','stories','creativity']:
  focus('category:'+category);key('DPAD_CENTER');focus('video:stitch-watch-'+category+'-1')
  key('BACK');focus('category:'+category)
  if category!='creativity':key('DPAD_RIGHT')
 for _ in range(3):key('DPAD_LEFT')
 for k,e in [('DPAD_LEFT','category:nature'),('DPAD_LEFT','category:nature'),('DPAD_RIGHT','category:space'),('DPAD_RIGHT','category:how'),('DPAD_RIGHT','category:stories'),('DPAD_RIGHT','category:creativity'),('DPAD_RIGHT','category:creativity'),('DPAD_DOWN','guide'),('DPAD_UP','category:creativity'),('DPAD_UP','continue'),('DPAD_RIGHT','parent'),('DPAD_RIGHT','parent'),('DPAD_DOWN','category:creativity')]: key(k);focus(e)
 for _ in range(3): key('DPAD_LEFT')
 key('DPAD_CENTER');focus('video:stitch-watch-space-1')
 key('DPAD_LEFT');focus('video:stitch-watch-space-1')
 for _ in range(5):key('DPAD_RIGHT')
 focus('video:stitch-watch-space-5');key('DPAD_DOWN');focus('guide');key('DPAD_UP');focus('video:stitch-watch-space-5')
 key('DPAD_UP');focus('back');key('DPAD_RIGHT');focus('parent');key('DPAD_DOWN');focus('video:stitch-watch-space-5')
 key('DPAD_CENTER');focus('play');key('DPAD_RIGHT');focus('series');key('DPAD_CENTER');focus('video:stitch-watch-space-5')
 key('DPAD_CENTER');key('DPAD_UP');focus('guide');key('DPAD_UP');focus('back');key('DPAD_DOWN');focus('play')
 key('DPAD_CENTER',2);key('MEDIA_PAUSE');focus('play',True)
 key('DPAD_UP');focus('timeline',True);key('DPAD_LEFT');key('DPAD_RIGHT');key('MEDIA_PLAY',3);key('MEDIA_PAUSE');shot('17_player_captions.png');key('DPAD_UP');focus('back',True);key('DPAD_DOWN');focus('timeline',True);key('DPAD_DOWN');focus('play',True)
 for _ in range(6):key('DPAD_RIGHT')
 focus('voice',True);key('DPAD_CENTER');assert 'voice_control_only AI=0' in process_log()
 key('BACK');focus('play');key('BACK');focus('video:stitch-watch-space-5');key('BACK');focus('category:space');key('BACK')
 state=adb('shell','dumpsys','activity','activities');assert not re.search(r'(?:topResumedActivity|mResumedActivity).*StitchWatch',state)
 adb('shell','am','start','-W','-n',WATCH);time.sleep(1)
 data=process_log();log('acceptance_logcat.txt',data)
 assert 'AI=0 runtimePresent=false' in data
 assert not re.search(r'FATAL EXCEPTION|ANR in ru.familytv|runtimeInitialization count=|playback_error|catalog_error|load_error',data)
 pid=adb('shell','pidof',PKG).strip();maps=adb('shell','run-as',PKG,'cat','/proc/'+pid+'/maps');log('process_maps.txt',maps)
 assert not re.search(r'\.(gguf|onnx)(?:\s|$)',maps,re.I)
 log('acceptance_result.txt','PASS: H728 ADB DPAD bounded edges, all 5 categories, 5 videos, vertical navigation, series, details, player timeline, pause, seek, Back, focus restoration, voice control AI=0. Physical remote not used.\n')
 # Existing PIN entry is reused; do not enter or change any PIN/settings.
 key('DPAD_UP');key('DPAD_RIGHT');focus('parent');key('DPAD_CENTER',1)
 parent=adb('shell','dumpsys','activity','activities');log('parent_entry_activity.txt','FamilyTV lines from dumpsys activity:\n'+'\n'.join(x for x in parent.splitlines() if PKG in x))
 assert re.search(r'(?:topResumedActivity|mResumedActivity).*ParentModeActivity',parent)
 shot('16_existing_parent_pin.png');key('BACK',1)
 production=adb('shell','dumpsys','activity','activities');log('parent_back_activity.txt','FamilyTV lines from dumpsys activity:\n'+'\n'.join(x for x in production.splitlines() if PKG in x))
 assert re.search(r'(?:topResumedActivity|mResumedActivity).*HomeActivity',production)
 # Verify the original Home C station also regains focus after the Watch stack exits.
 adb('shell','am','force-stop',PKG)
 adb('shell','am','start','-W','-n',PKG+'/.home.stitch.StitchHomePreviewActivity');time.sleep(1.5)
 key('DPAD_LEFT');key('DPAD_CENTER',1);focus('category:space');key('BACK',1)
 entry_log=process_log();log('home_c_return_logcat.txt',entry_log)
 matches=re.findall(r'StitchHomePreview: focus=(\S+) lastWorld=',entry_log)
 assert matches and matches[-1]=='world:0',matches
 print('PASS focus Home C world:0',flush=True);shot('18_home_c_return_focus.png')
 key('DPAD_CENTER',1)

 print('PASS additional navigation and AI checks',flush=True)
