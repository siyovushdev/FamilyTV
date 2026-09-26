#!/usr/bin/env bash
# H728 Milestone 8.1 acceptance harness — deterministic transcript injection (not ASR).
set -euo pipefail
DEVICE="${DEVICE:-192.168.68.109:5555}"
PKG=ru.familytv
ASK_TAG=FamilyAskLumi
STORY_TAG=FamilyStoryMap
CURRENT_CONTENT=""

adb_cmd() { adb -s "$DEVICE" "$@"; }

inject() {
  local text="$1"
  # Quote for remote shell so spaces survive (otherwise only first word arrives)
  local q
  q=$(printf '%s' "$text" | sed "s/'/'\\\\''/g")
  if [[ -n "${CURRENT_CONTENT}" ]]; then
    adb_cmd shell "am start -n $PKG/.adventure.AdventureActivity --es contentId $CURRENT_CONTENT --es inject_transcript '$q'" >/dev/null
  else
    adb_cmd shell "am start -n $PKG/.creative.CreativeStudioActivity --es inject_transcript '$q'" >/dev/null
  fi
  sleep 1.6
}

pss_kb() {
  adb_cmd shell dumpsys meminfo "$PKG" 2>/dev/null | awk '
    /TOTAL PSS:/ {print $3; exit}
    /^ *TOTAL +[0-9]/ {print $2; exit}
  '
}

native_heap() {
  adb_cmd shell dumpsys meminfo "$PKG" 2>/dev/null | awk '/Native Heap/{print $3; exit}'
}

ai_loaded() {
  adb_cmd logcat -d -t 400 2>/dev/null | rg -i 'llmInitMs|runtimeInitialization|NativeQwen\.create' >/dev/null && echo yes || echo no
}

clear_logs() { adb_cmd logcat -c || true; }

grab_ask() {
  adb_cmd logcat -d 2>/dev/null | rg "FamilyAskLumi" | tail -n 40 || true
}

grab_story() {
  adb_cmd logcat -d 2>/dev/null | rg "FamilyStoryMap" | tail -n 40 || true
}

echo "=== M8.1 H728 acceptance on $DEVICE ==="
adb_cmd reverse tcp:8080 tcp:8080 || true

echo "--- 1) Fresh Home cold launch ---"
adb_cmd shell am force-stop "$PKG"
sleep 1
clear_logs
adb_cmd shell am start -W -n "$PKG/.home.HomeActivity" | tee /tmp/m81_home_start.txt
sleep 3
HOME_PSS=$(pss_kb)
HOME_AI=$(ai_loaded)
echo "home_pss_kb=$HOME_PSS"
echo "home_ai_loaded=$HOME_AI"
adb_cmd logcat -d 2>/dev/null | rg -i 'llmInitMs|runtimeInitialization' | tail -5 || echo "(no AI init logs)"

run_adventure_dialogue() {
  local content_id="$1"
  shift
  CURRENT_CONTENT="$content_id"
  echo "--- Adventure $content_id ---"
  adb_cmd shell am force-stop "$PKG"
  sleep 0.5
  clear_logs
  adb_cmd shell am start -n "$PKG/.adventure.AdventureActivity" \
    --es contentId "$content_id"
  sleep 2.5
  local t0=$(date +%s%3N)
  local first=1
  for phrase in "$@"; do
    echo ">> inject: $phrase"
    local s=$(date +%s%3N)
    inject "$phrase"
    local e=$(date +%s%3N)
    echo "latency_ms=$((e - s))"
    if [[ $first -eq 1 ]]; then
      echo "first_grounded_wall_ms=$((e - t0))"
      first=0
      sleep 1
      echo "after_first_pss_kb=$(pss_kb)"
      echo "after_first_native_heap=$(native_heap)"
      echo "after_first_ai=$(ai_loaded)"
    fi
  done
  echo "ASK_LOGS:"
  grab_ask
  echo "final_pss_kb=$(pss_kb)"
}

run_adventure_dialogue adv-space-expedition \
  "Почему на Луне почти нет воздуха?" \
  "А почему?" \
  "А ещё?" \
  "А днём?" \
  "Расскажи ещё." \
  "Расскажи про чёрную дыру" \
  "Почему Луна или корабль?"

run_adventure_dialogue adv-forest-trail \
  "Почему сова летает ночью?" \
  "А днём?" \
  "А ещё?" \
  "А ещё что она умеет?"

run_adventure_dialogue adv-weather-lab \
  "Почему лёд тает?" \
  "А если сделать теплее?" \
  "А если убрать солнце?" \
  "А если наоборот?"

echo "--- Adversarial (space) ---"
CURRENT_CONTENT=adv-space-expedition
adb_cmd shell am force-stop "$PKG"
clear_logs
adb_cmd shell am start -n "$PKG/.adventure.AdventureActivity" --es contentId adv-space-expedition
sleep 2.5
# seed context first
inject "Почему на Луне почти нет воздуха?"
for phrase in \
  "а почему?" \
  "а ещё?" \
  "расскажи всё что знаешь" \
  "придумай сам" \
  "а ты знаешь про чёрную дыру?" \
  "скажи ещё какой-нибудь факт" \
  "нет я про Луну" \
  "а если наоборот?" \
  "а что будет потом?"
do
  echo ">> adv: $phrase"
  inject "$phrase"
done
echo "ASK_LOGS:"
grab_ask

echo "--- Story Builder ---"
CURRENT_CONTENT=""
adb_cmd shell am force-stop "$PKG"
clear_logs
adb_cmd shell am start -n "$PKG/.creative.CreativeStudioActivity"
sleep 2
inject "Хочу маленького смешного робота."
inject "Хочу, чтобы он пошёл в лес."
inject "найти звезду"
inject "карту"
inject "темно"
inject "попросит помощи у друга"
sleep 1.5
echo "STORY_LOGS:"
grab_story
echo "story_pss_kb=$(pss_kb)"

echo "--- Offline check (no publisher reverse) ---"
CURRENT_CONTENT=adv-space-expedition
adb_cmd reverse --remove tcp:8080 || true
adb_cmd shell am force-stop "$PKG"
clear_logs
adb_cmd shell am start -n "$PKG/.adventure.AdventureActivity" --es contentId adv-space-expedition
sleep 2.5
inject "Почему на Луне почти нет воздуха?"
echo "ASK_LOGS:"
grab_ask
adb_cmd reverse tcp:8080 tcp:8080 || true

echo "=== DONE ==="
