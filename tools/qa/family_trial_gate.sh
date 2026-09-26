#!/usr/bin/env bash
# Milestone 5.1.1 H728 household stability + content smoke (keyevent BACK, not am-start leak loops)
set -euo pipefail
DEV=${DEV:-192.168.68.109:5555}
REPORT=${REPORT:-/tmp/ftv511_stability.txt}
OUT=${OUT:-/home/siovush/AndroidStudioProjects/FamilyTV/docs/images/family-trial}
: > "$REPORT"
log(){ echo "$1" | tee -a "$REPORT"; }
mem(){ adb -s "$DEV" shell dumpsys meminfo ru.familytv | rg "TOTAL PSS|Native Heap:|Graphics:|Activities:|ViewRootImpl|Views:" | tee -a "$REPORT"; }
top(){ adb -s "$DEV" shell dumpsys activity activities | rg "topResumedActivity|Hist #.*ru.familytv" | head -8 | tee -a "$REPORT"; }
back(){ adb -s "$DEV" shell input keyevent KEYCODE_BACK; sleep 1; }

adb -s "$DEV" connect "$DEV" >/dev/null || true
adb -s "$DEV" logcat -c
adb -s "$DEV" shell am force-stop ru.familytv
sleep 1
adb -s "$DEV" shell am start -W -n ru.familytv/.home.HomeActivity >/dev/null
sleep 3
log "=== BEFORE ==="
mem
top

# Content smoke ids (bundled + active packages)
IDS=(
  video-sky video-forest video-warm move-stretch
  game-math game-color interactive-count-apples
  pkg-apple-adventure pkg-animal-world pkg-colors-shapes
  pkg-space-world pkg-memory-focus pkg-move-break pkg-short-story
  session-today-together session-morning
)

# Resolve real ids from package store / known prefixes by probing open
SMOKE_OK=0
SMOKE_FAIL=0
SMOKE_SKIP=0

open_and_back() {
  local component="$1" extra="$2" name="$3"
  adb -s "$DEV" shell am start -n "$component" $extra >/dev/null 2>&1 || { log "FAIL open $name"; SMOKE_FAIL=$((SMOKE_FAIL+1)); return; }
  sleep 2
  local topact
  topact=$(adb -s "$DEV" shell dumpsys activity activities | awk '/topResumedActivity=/{print; exit}')
  if echo "$topact" | rg -q 'HomeActivity'; then
    log "SKIP/UNAVAILABLE $name (bounced Home)"
    SMOKE_SKIP=$((SMOKE_SKIP+1))
    return
  fi
  # capture once for key screens
  case "$name" in
    *apple*|interactive*|session-today*|game-math*)
      adb -s "$DEV" shell screencap -p /sdcard/ftv_smoke.png >/dev/null
      adb -s "$DEV" pull /sdcard/ftv_smoke.png "$OUT/smoke-$name.png" >/dev/null 2>&1 || true
      ;;
  esac
  back
  # games/parent may need leave
  topact=$(adb -s "$DEV" shell dumpsys activity activities | awk '/topResumedActivity=/{print; exit}')
  if ! echo "$topact" | rg -q 'HomeActivity'; then back; fi
  sleep 0.5
  topact=$(adb -s "$DEV" shell dumpsys activity activities | awk '/topResumedActivity=/{print; exit}')
  if echo "$topact" | rg -q 'HomeActivity'; then
    log "OK $name"
    SMOKE_OK=$((SMOKE_OK+1))
  else
    log "FAIL cleanup $name -> $topact"
    SMOKE_FAIL=$((SMOKE_FAIL+1))
    adb -s "$DEV" shell am start -n ru.familytv/.home.HomeActivity >/dev/null
    sleep 1
  fi
}

log "=== SMOKE ==="
# Discover package content ids via admin-ish list from Home isn't easy; try common ids from packages
for id in \
  video-sky video-forest video-warm move-stretch \
  interactive-count-apples \
  pkg-apple-adventure apple-adventure \
  pkg-animal-world animal-world \
  pkg-colors-shapes colors-shapes \
  pkg-space-world space-world \
  pkg-memory-focus memory-focus \
  pkg-move-break move-break \
  pkg-short-story short-story \
  today-together morning-session session-today-together
 do
  case "$id" in
    game-*) continue ;;
    *session*|today*|morning*)
      open_and_back ru.familytv/.session.LearningSessionActivity "-e contentId $id" "$id" ;;
    interactive*|count*|apple*|animal*|colors*|space*|memory*|move*|short*)
      open_and_back ru.familytv/.interactive.InteractiveExperienceActivity "-e contentId $id" "$id" ;;
    video*|move-stretch)
      open_and_back ru.familytv/.player.VideoPlayerActivity "-e contentId $id" "$id" ;;
  esac
done

# Games: mic explain without grant should not trap
adb -s "$DEV" shell pm revoke ru.familytv android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
open_and_back ru.familytv/.games.MathVoiceGameActivity "" "game-math-no-mic"
open_and_back ru.familytv/.games.ColorVoiceGameActivity "" "game-color-no-mic"

# Grant via permission UI path (not pm grant): launch math, press Allow, accept system dialog if present
log "=== MIC GRANT VIA UI ==="
adb -s "$DEV" shell am start -n ru.familytv/.games.MathVoiceGameActivity
sleep 2
adb -s "$DEV" shell input keyevent KEYCODE_DPAD_CENTER  # Разрешить
sleep 1
# System permission dialog: typically Allow is right/focusable
adb -s "$DEV" shell input keyevent KEYCODE_DPAD_RIGHT
adb -s "$DEV" shell input keyevent KEYCODE_DPAD_CENTER
sleep 2
adb -s "$DEV" shell screencap -p /sdcard/ftv_mic_after.png
adb -s "$DEV" pull /sdcard/ftv_mic_after.png "$OUT/mic-after-grant-attempt.png" >/dev/null 2>&1 || true
GRANTED=$(adb -s "$DEV" shell dumpsys package ru.familytv | awk '/android.permission.RECORD_AUDIO:/{getline; print; exit}')
log "mic_state $GRANTED"
back; back
adb -s "$DEV" shell am start -n ru.familytv/.home.HomeActivity >/dev/null

# Today Together complete walk (best-effort automation)
log "=== SESSION WALK ==="
for sid in today-together session-today-together pkg-today-together; do
  adb -s "$DEV" shell am start -n ru.familytv/.session.LearningSessionActivity -e contentId "$sid" >/dev/null 2>&1 || continue
  sleep 2
  topact=$(adb -s "$DEV" shell dumpsys activity activities | awk '/topResumedActivity=/{print; exit}')
  if echo "$topact" | rg -q LearningSession; then
    log "session_open $sid"
    # Advance a few steps: Start, interact briefly, back to session, repeat
    for step in 1 2 3 4 5 6 7 8; do
      adb -s "$DEV" shell input keyevent KEYCODE_DPAD_CENTER
      sleep 2
      topact=$(adb -s "$DEV" shell dumpsys activity activities | awk '/topResumedActivity=/{print; exit}')
      if echo "$topact" | rg -q 'LearningSession|HomeActivity'; then
        :
      else
        # in child content — wait a bit or back
        sleep 2
        back
        sleep 1
      fi
      # look for calm ending
      adb -s "$DEV" shell uiautomator dump /sdcard/ui.xml >/dev/null
      adb -s "$DEV" pull /sdcard/ui.xml /tmp/sess.xml >/dev/null
      if rg -q 'На сегодня всё' /tmp/sess.xml; then
        log "SESSION_COMPLETE"
        adb -s "$DEV" shell screencap -p /sdcard/ftv_sess_done.png
        adb -s "$DEV" pull /sdcard/ftv_sess_done.png "$OUT/session-complete.png" >/dev/null
        adb -s "$DEV" shell input keyevent KEYCODE_DPAD_CENTER
        sleep 1
        break 2
      fi
    done
    break
  fi
done

# 30-minute loop (~32 min wall)
log "=== 30MIN START $(date -Is) ==="
END=$((SECONDS+1920))
i=0
while (( SECONDS < END )); do
  i=$((i+1))
  adb -s "$DEV" shell am start -n ru.familytv/.player.VideoPlayerActivity -e contentId video-sky >/dev/null
  sleep 4; back
  adb -s "$DEV" shell am start -n ru.familytv/.interactive.InteractiveExperienceActivity -e contentId interactive-count-apples >/dev/null
  sleep 3; back
  adb -s "$DEV" shell am start -n ru.familytv/.session.LearningSessionActivity -e contentId today-together >/dev/null 2>&1 || true
  sleep 2; back
  adb -s "$DEV" shell am start -n ru.familytv/.games.MathVoiceGameActivity >/dev/null
  sleep 2; back
  adb -s "$DEV" shell am start -n ru.familytv/.parent.ParentModeActivity >/dev/null
  sleep 2; back
  adb -s "$DEV" shell am start -n ru.familytv/.home.HomeActivity >/dev/null
  sleep 2
  if (( i % 5 == 0 )); then log "loop $i mem"; mem; top; fi
done
log "=== 30MIN END $(date -Is) ==="
mem
top
log "=== FATAL/ANR ==="
adb -s "$DEV" logcat -d | rg -i "FATAL EXCEPTION|ANR in|WindowLeaked|Activity leak" | tee -a "$REPORT" || log "(none)"
log "SMOKE_OK=$SMOKE_OK FAIL=$SMOKE_FAIL SKIP=$SMOKE_SKIP"
log DONE
