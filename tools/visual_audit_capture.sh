#!/usr/bin/env bash
# FamilyTV Visual Audit Pack — capture only (H728). No product changes.
set -euo pipefail
DEVICE="${DEVICE:-192.168.68.109:5555}"
ADB=(adb -s "$DEVICE")
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/docs/visual-audit-current"
SHOT="$OUT/screenshots"
REC="$OUT/recordings"
PKG=ru.familytv
mkdir -p "$SHOT" "$REC"

log() { echo "[audit] $*"; }

force() { "${ADB[@]}" shell am force-stop "$PKG" >/dev/null 2>&1 || true; sleep 0.7; }

ensure_fg() {
  local fg
  fg=$("${ADB[@]}" shell dumpsys activity activities 2>/dev/null | tr -d '\r' | grep -E "mResumedActivity|topResumedActivity" | head -1 || true)
  if ! echo "$fg" | grep -q "$PKG"; then
    return 1
  fi
  return 0
}

snap() {
  local name="$1"
  local wait="${2:-2.2}"
  sleep "$wait"
  if ! ensure_fg; then
    log "WARN not in FamilyTV before $name — launching Home"
    start_home
    sleep 2.5
  fi
  "${ADB[@]}" shell screencap -p /sdcard/ftv_audit.png
  "${ADB[@]}" pull -a /sdcard/ftv_audit.png "$SHOT/$name" >/dev/null
  local sz
  sz=$(stat -c%s "$SHOT/$name" 2>/dev/null || echo 0)
  # Launcher tiles are typically dark/large; FamilyTV home soft bg often 100-400KB.
  # Flag suspiciously tiny or known-bad sizes for redo notes.
  if [[ "$sz" -lt 30000 ]]; then
    log "WARN tiny shot $name (${sz} bytes)"
  else
    log "shot $name (${sz} bytes)"
  fi
}

# Seed incomplete adventure resume scene (SharedPreferences XML)
seed_adv() {
  local adv_id="$1" scene="$2"
  force
  cat > /tmp/ftv_adv_progress.xml <<EOF
<?xml version="1.0" encoding="utf-8" standalone="yes" ?>
<map>
    <string name="device:${adv_id}:scene">${scene}</string>
    <boolean name="device:${adv_id}:completed" value="false" />
    <string name="device:${adv_id}:packageId"></string>
    <int name="device:${adv_id}:packageVersion" value="-1" />
    <long name="device:${adv_id}:updatedAt" value="1" />
</map>
EOF
  "${ADB[@]}" push /tmp/ftv_adv_progress.xml /data/local/tmp/ftv_adv_progress.xml >/dev/null
  "${ADB[@]}" shell "run-as $PKG cp /data/local/tmp/ftv_adv_progress.xml shared_prefs/familytv_adventure_progress.xml"
}

clear_adv() {
  force
  "${ADB[@]}" shell "run-as $PKG rm -f shared_prefs/familytv_adventure_progress.xml" 2>/dev/null || true
}

start_adv() {
  local content_id="$1"
  "${ADB[@]}" shell am start -W -n "$PKG/.adventure.AdventureActivity" --es contentId "$content_id" >/dev/null
  sleep 0.6
}

start_home() {
  "${ADB[@]}" shell am start -W -n "$PKG/.home.HomeActivity" >/dev/null
  sleep 0.4
}

start_map() {
  local jid="$1"
  "${ADB[@]}" shell am start -W -n "$PKG/.journey.JourneyMapActivity" --es journey_id "$jid" >/dev/null
  sleep 0.4
}

start_journal() {
  "${ADB[@]}" shell am start -W -n "$PKG/.journey.DiscoveryJournalActivity" >/dev/null
  sleep 0.4
}

key() {
  "${ADB[@]}" shell input keyevent "$1" >/dev/null
}

# Prefer content-desc / text tap via uiautomator dump
tap_text() {
  local needle="$1"
  local dump=/tmp/ftv_ui.xml
  "${ADB[@]}" shell uiautomator dump /sdcard/ftv_ui.xml >/dev/null 2>&1 || return 1
  "${ADB[@]}" pull /sdcard/ftv_ui.xml "$dump" >/dev/null 2>&1 || return 1
  python3 - "$dump" "$needle" <<'PY' || return 1
import re, sys
xml=open(sys.argv[1],encoding='utf-8',errors='ignore').read()
needle=sys.argv[2]
# find node containing needle in text or content-desc
pat=re.compile(r'\[.*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]".*?\]', re.S)
# simpler: line-based
for m in re.finditer(r'text="([^"]*)"[^>]*content-desc="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"|content-desc="([^"]*)"[^>]*text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"|text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"|content-desc="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    g=m.groups()
    texts=[t for t in g[:2]+g[6:8]+g[12:13]+g[16:17] if t]
    if not any(needle.lower() in (t or '').lower() for t in texts if t):
        continue
    nums=[int(x) for x in g if x and x.isdigit()]
    if len(nums)<4:
        # extract bounds from match string
        bm=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', m.group(0))
        if not bm: continue
        x1,y1,x2,y2=map(int,bm.groups())
    else:
        # last 4 digit groups in match are bounds in most patterns
        bm=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', m.group(0))
        x1,y1,x2,y2=map(int,bm.groups())
    print((x1+x2)//2, (y1+y2)//2)
    break
else:
    sys.exit(1)
PY
}

tap_xy_from_text() {
  local needle="$1"
  local coords
  coords=$(tap_text "$needle") || return 1
  # shellcheck disable=SC2086
  "${ADB[@]}" shell input tap $coords >/dev/null
  log "tap '$needle' -> $coords"
}

set_pref_bool() {
  local prefs="$1" key="$2" val="$3"
  force
  "${ADB[@]}" shell "run-as $PKG cat shared_prefs/${prefs}.xml" >"/tmp/${prefs}.xml" 2>/dev/null || echo '<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map></map>' >"/tmp/${prefs}.xml"
  python3 - "/tmp/${prefs}.xml" "$key" "$val" <<'PY'
import sys,re
path,key,val=sys.argv[1],sys.argv[2],sys.argv[3]
xml=open(path,encoding='utf-8').read()
xml=re.sub(rf'<boolean name="{re.escape(key)}"[^/]*/>','',xml)
xml=xml.replace('</map>', f'    <boolean name="{key}" value="{val}" />\n</map>')
open(path,'w',encoding='utf-8').write(xml)
PY
  "${ADB[@]}" push "/tmp/${prefs}.xml" /data/local/tmp/ftv_pref.xml >/dev/null
  "${ADB[@]}" shell "run-as $PKG cp /data/local/tmp/ftv_pref.xml shared_prefs/${prefs}.xml"
}

set_pref_string() {
  local prefs="$1" key="$2" val="$3"
  force
  "${ADB[@]}" shell "run-as $PKG cat shared_prefs/${prefs}.xml" >"/tmp/${prefs}.xml" 2>/dev/null || echo '<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map></map>' >"/tmp/${prefs}.xml"
  python3 - "/tmp/${prefs}.xml" "$key" "$val" <<'PY'
import sys,re,html
path,key,val=sys.argv[1],sys.argv[2],sys.argv[3]
xml=open(path,encoding='utf-8').read()
xml=re.sub(rf'<string name="{re.escape(key)}">.*?</string>','',xml)
esc=html.escape(val)
xml=xml.replace('</map>', f'    <string name="{key}">{esc}</string>\n</map>')
open(path,'w',encoding='utf-8').write(xml)
PY
  "${ADB[@]}" push "/tmp/${prefs}.xml" /data/local/tmp/ftv_pref.xml >/dev/null
  "${ADB[@]}" shell "run-as $PKG cp /data/local/tmp/ftv_pref.xml shared_prefs/${prefs}.xml"
}

# ---------- HOME ----------
capture_home() {
  log "=== HOME ==="
  set_pref_bool familytv_debug non_reader_simulation false
  set_pref_string familytv_family_settings reading_mode AUDIO_AND_TEXT
  force
  start_home
  snap 01_home_default.png 3.0

  # Focus hero if possible
  key 20 # DPAD_DOWN a few times from brand
  key 20
  snap 02_home_hero_focused.png 1.0

  # Try section headers / cards via text
  for pair in \
    "03_home_explore_focused.png:Исследуй" \
    "04_home_solve_focused.png:Решай" \
    "05_home_create_focused.png:Создавай" \
    "06_home_stories_focused.png:Истории" \
    "07_home_move_focused.png:Двигайся"
  do
    IFS=: read -r fname needle <<<"$pair"
    start_home; sleep 1.5
    if tap_xy_from_text "$needle"; then
      sleep 0.5
      key 20
      snap "$fname" 0.8
    else
      # scroll and retry
      key 20; key 20; key 20
      if tap_xy_from_text "$needle"; then snap "$fname" 0.8; else
        log "WARN cannot focus $needle — capture scroll state"
        snap "$fname" 0.5
      fi
    fi
  done

  # Continue journey hero (journey progress should already exist from M8.2)
  force; start_home
  snap 08_home_continue_journey.png 2.5
  # alias requested name
  cp -f "$SHOT/08_home_continue_journey.png" "$SHOT/85_home_continue_journey_after_progress.png" 2>/dev/null || true

  # Today section
  start_home; sleep 1
  if tap_xy_from_text "Сегодня"; then snap 09_home_today.png 0.8; else
    key 20; key 20; snap 09_home_today.png 0.8
  fi

  # Non-reader
  set_pref_bool familytv_debug non_reader_simulation true
  force; start_home
  snap 10_home_non_reader_mode.png 2.5
  set_pref_bool familytv_debug non_reader_simulation false

  # Audio first
  set_pref_string familytv_family_settings reading_mode AUDIO_FIRST
  force; start_home
  snap 11_home_audio_first_state.png 2.5
  # also 150
  cp -f "$SHOT/11_home_audio_first_state.png" "$SHOT/150_audio_first.png"
  set_pref_string familytv_family_settings reading_mode AUDIO_AND_TEXT
  force; start_home
  snap 151_audio_and_text.png 2.0
  set_pref_string familytv_family_settings reading_mode TEXT_RICH
  force; start_home
  snap 152_text_rich.png 2.0
  set_pref_string familytv_family_settings reading_mode AUDIO_AND_TEXT
}

# ---------- LUMI via adventure guide emotions ----------
capture_lumi() {
  log "=== LUMI ==="
  # Idle/curious on home
  force; start_home
  snap 20_lumi_idle.png 2.5

  # Listening — try mic button (may show permission or listening UI)
  if tap_xy_from_text "Сказать"; then
    snap 21_lumi_listening.png 1.5
  else
    # Adventure inject path often sets listening-like UI — use help elsewhere
    seed_adv space-expedition intro
    start_adv adv-space-expedition
    snap 21_lumi_listening.png 2.0
  fi

  # Thinking — dialogue scene
  seed_adv space-expedition science_talk
  start_adv adv-space-expedition
  snap 22_lumi_thinking.png 2.2

  # Helping — inject after scene ready via Help button
  seed_adv space-expedition launch_search
  start_adv adv-space-expedition
  sleep 2
  if tap_xy_from_text "Подсказ" || tap_xy_from_text "Помощь" || tap_xy_from_text "Help"; then
    snap 23_lumi_helping.png 1.2
  else
    # DPAD to help if present
    key 22; key 22; key 23
    snap 23_lumi_helping.png 1.0
  fi

  # Happy / celebrating — finale reflection / completion
  seed_adv space-expedition finale
  start_adv adv-space-expedition
  snap 24_lumi_happy.png 2.2

  # Encouraging — forest hello / guide
  seed_adv forest-trail hello
  start_adv adv-forest-trail
  snap 25_lumi_encouraging.png 2.2

  # Surprised — may map to EXCITED intro
  clear_adv
  start_adv adv-space-expedition
  snap 26_lumi_surprised.png 2.5
}

capture_space() {
  log "=== SPACE ==="
  clear_adv
  start_adv adv-space-expedition
  snap 30_space_intro.png 2.5

  seed_adv space-expedition launch_search
  start_adv adv-space-expedition
  snap 31_space_mission.png 2.2
  snap 32_space_visual_search.png 0.3

  seed_adv space-expedition planet_sort
  start_adv adv-space-expedition
  snap 33_space_sort_classify.png 2.2

  seed_adv space-expedition repair_pattern
  start_adv adv-space-expedition
  snap 34_space_pattern_sequence.png 2.2

  seed_adv space-expedition nav_maze
  start_adv adv-space-expedition
  snap 35_space_path_maze.png 2.2

  # Repeat / Help buttons
  seed_adv space-expedition launch_search
  start_adv adv-space-expedition
  sleep 2
  if tap_xy_from_text "Повтор"; then snap 36_space_repeat.png 1.0; else
    key 21; snap 36_space_repeat.png 0.8
  fi
  seed_adv space-expedition launch_search
  start_adv adv-space-expedition
  sleep 2
  if tap_xy_from_text "Подсказ" || tap_xy_from_text "Помощь"; then snap 37_space_help.png 1.0
  else snap 37_space_help.png 0.8
  fi

  # Group prompt visible on discuss scenes
  seed_adv space-expedition launch_search
  start_adv adv-space-expedition
  snap 38_space_group_prompt.png 2.0

  seed_adv space-expedition offscreen_circle
  start_adv adv-space-expedition
  snap 39_space_offscreen.png 2.2

  # Ask Lumi
  seed_adv space-expedition launch_search
  start_adv adv-space-expedition
  sleep 2
  "${ADB[@]}" shell 'am start -n ru.familytv/.adventure.AdventureActivity --es contentId adv-space-expedition --es inject_transcript "Почему на Луне почти нет воздуха?"'
  snap 40_space_ask_lumi.png 3.0

  # World reaction — after mechanic select (tap an object)
  seed_adv space-expedition launch_search
  start_adv adv-space-expedition
  sleep 2
  if tap_xy_from_text "Пробка" || tap_xy_from_text "Мяч"; then
    snap 41_space_world_reaction.png 1.2
  else
    key 22; key 23; snap 41_space_world_reaction.png 1.0
  fi

  # Completion — mark completed false but open finale then complete via advancing
  seed_adv space-expedition finale
  start_adv adv-space-expedition
  snap 42_space_completion.png 2.2
}

capture_forest() {
  log "=== FOREST ==="
  clear_adv
  start_adv adv-forest-trail
  snap 50_forest_intro.png 2.5

  seed_adv forest-trail search_leaves
  start_adv adv-forest-trail
  snap 51_forest_scene.png 2.0
  snap 52_forest_visual_search.png 0.3

  seed_adv forest-trail sort_nature
  start_adv adv-forest-trail
  snap 53_forest_classification.png 2.2

  # Owl question via Ask Lumi inject (knowledge owl)
  seed_adv forest-trail search_leaves
  start_adv adv-forest-trail
  sleep 2
  "${ADB[@]}" shell 'am start -n ru.familytv/.adventure.AdventureActivity --es contentId adv-forest-trail --es inject_transcript "Почему сова летает ночью?"'
  snap 54_forest_owl_question.png 3.0
  "${ADB[@]}" shell 'am start -n ru.familytv/.adventure.AdventureActivity --es contentId adv-forest-trail --es inject_transcript "А днём?"'
  snap 55_forest_ask_lumi_followup.png 2.5

  seed_adv forest-trail search_leaves
  start_adv adv-forest-trail
  sleep 2
  if tap_xy_from_text "Подсказ" || tap_xy_from_text "Помощь"; then snap 56_forest_help.png 1.0
  else snap 56_forest_help.png 0.8
  fi

  seed_adv forest-trail search_leaves
  start_adv adv-forest-trail
  snap 57_forest_group_prompt.png 2.0

  seed_adv forest-trail end
  start_adv adv-forest-trail
  snap 58_forest_completion.png 2.2
}

capture_weather() {
  log "=== WEATHER ==="
  clear_adv
  start_adv adv-weather-lab
  snap 60_weather_lab_intro.png 2.5

  seed_adv weather-lab predict_melt
  start_adv adv-weather-lab
  snap 61_weather_prediction.png 2.2
  snap 62_weather_test_setup.png 0.3

  # Select prediction then test
  sleep 0.5
  if tap_xy_from_text "солнце" || tap_xy_from_text "Солнце"; then
    sleep 0.8
    snap 62_weather_test_setup.png 0.5
    if tap_xy_from_text "Провер" || tap_xy_from_text "проверить"; then
      snap 64_weather_observation.png 1.5
    else
      # voice inject test
      "${ADB[@]}" shell 'am start -n ru.familytv/.adventure.AdventureActivity --es contentId adv-weather-lab --es inject_transcript "проверить"'
      snap 64_weather_observation.png 2.0
    fi
  else
    snap 64_weather_observation.png 0.5
  fi

  # build_shelter scene
  seed_adv weather-lab build_shelter
  start_adv adv-weather-lab
  snap 63_weather_build_test.png 2.2

  seed_adv weather-lab predict_melt
  start_adv adv-weather-lab
  sleep 2
  "${ADB[@]}" shell 'am start -n ru.familytv/.adventure.AdventureActivity --es contentId adv-weather-lab --es inject_transcript "Почему лёд тает?"'
  snap 65_weather_ask_why.png 3.0
  "${ADB[@]}" shell 'am start -n ru.familytv/.adventure.AdventureActivity --es contentId adv-weather-lab --es inject_transcript "А если сделать теплее?"'
  snap 66_weather_ask_to_do.png 3.0

  # result / observation again after sun+test if possible
  seed_adv weather-lab predict_melt
  start_adv adv-weather-lab
  sleep 2
  tap_xy_from_text "Солнце" || true
  sleep 0.5
  "${ADB[@]}" shell 'am start -n ru.familytv/.adventure.AdventureActivity --es contentId adv-weather-lab --es inject_transcript "проверить"'
  snap 67_weather_result.png 2.5
}

capture_story() {
  log "=== STORY ==="
  force
  "${ADB[@]}" shell am start -n "$PKG/.creative.CreativeStudioActivity" >/dev/null
  snap 70_story_start.png 2.5
  if tap_xy_from_text "Начать" || tap_xy_from_text "истор" || tap_xy_from_text "Создать"; then
    snap 71_story_choose_hero.png 2.0
  else
    key 23
    snap 71_story_choose_hero.png 2.0
  fi
  # choose hero
  if tap_xy_from_text "робот" || tap_xy_from_text "Робот"; then
    sleep 1
    snap 72_story_choose_world.png 1.5
  else
    key 23; sleep 1; snap 72_story_choose_world.png 1.0
  fi
  # natural voice mapping
  "${ADB[@]}" shell 'am start -n ru.familytv/.creative.CreativeStudioActivity --es inject_transcript "Хочу маленького смешного робота."'
  snap 73_story_natural_voice_mapping.png 2.5
  # continue choices
  if tap_xy_from_text "лес" || tap_xy_from_text "Лес"; then
    sleep 1; snap 74_story_branch.png 1.2
  else
    key 22; key 23; snap 74_story_branch.png 1.0
  fi
  # Try to finish if buttons available — otherwise capture current
  for _ in 1 2 3 4 5; do
    tap_xy_from_text "Дальше" || tap_xy_from_text "Готово" || key 23 || true
    sleep 0.8
  done
  snap 75_story_result.png 1.5

  force
  start_journal
  snap 76_story_replay_gallery.png 2.5
}

capture_journey_journal() {
  log "=== JOURNEY / JOURNAL ==="
  # Reset space journey to start-ish for map_start — use debug complete selectively
  force
  # Ensure journey progress exists: start map
  start_map space-grand-expedition
  snap 80_space_journey_map_start.png 2.5

  # Progress mid — complete launch via debug
  "${ADB[@]}" shell am start -n "$PKG/.journey.JourneyMapActivity" \
    --es journey_id space-grand-expedition --es debug_complete_content adv-space-expedition >/dev/null
  sleep 1.5
  snap 81_space_journey_map_progress.png 1.5

  "${ADB[@]}" shell am start -n "$PKG/.journey.JourneyMapActivity" \
    --es journey_id space-grand-expedition --es debug_complete_node moon-hub >/dev/null
  sleep 1.2
  # branch picker may show
  snap 82_space_journey_branch.png 1.5

  # Complete remaining quickly for completed map
  for extra in \
    "--es debug_complete_content game-color" \
    "--es debug_complete_content adv-weather-lab" \
    "--es debug_complete_content move-stretch" \
    "--es debug_complete_content video-warm" \
    "--es debug_complete_node calm-end"
  do
    # shellcheck disable=SC2086
    "${ADB[@]}" shell am start -n "$PKG/.journey.JourneyMapActivity" --es journey_id space-grand-expedition $extra >/dev/null
    sleep 1.0
  done
  snap 83_space_journey_completed.png 1.8

  start_map forest-secrets
  snap 84_forest_journey_map.png 2.2

  force; start_home
  snap 85_home_continue_journey_after_progress.png 2.5

  start_journal
  snap 90_journal_default.png 2.5
  # scroll for facts / experiments / stories
  key 20; key 20
  snap 91_journal_facts.png 1.0
  key 20; key 20
  snap 92_journal_experiments.png 1.0
  if tap_xy_from_text "истор" || tap_xy_from_text "Истори"; then
    snap 93_journal_stories.png 1.0
  else
    key 20; key 20; snap 93_journal_stories.png 0.8
  fi

  "${ADB[@]}" shell am start -n "$PKG/.journey.DiscoveryJournalActivity" --es journey_id space-grand-expedition >/dev/null
  snap 94_journal_after_space.png 2.2
  "${ADB[@]}" shell am start -n "$PKG/.journey.DiscoveryJournalActivity" --es journey_id forest-secrets >/dev/null
  snap 95_journal_after_forest.png 2.2
}

capture_games_video() {
  log "=== GAMES / VIDEO ==="
  force
  "${ADB[@]}" shell am start -n "$PKG/.games.MathVoiceGameActivity" >/dev/null
  snap 100_math_default.png 2.5
  if tap_xy_from_text "Повтор" || tap_xy_from_text "Помощь"; then
    snap 101_math_repeat_help.png 1.0
  else
    key 22; snap 101_math_repeat_help.png 0.8
  fi
  # inject correct-ish — may be wrong; capture feedback either way
  "${ADB[@]}" shell 'am start -n ru.familytv/.games.MathVoiceGameActivity --es inject_transcript "пять"' 2>/dev/null || true
  # Math may not support inject — try answer via UI if any
  snap 102_math_feedback_correct.png 2.0
  snap 103_math_feedback_wrong_or_hint.png 0.3

  force
  "${ADB[@]}" shell am start -n "$PKG/.games.ColorVoiceGameActivity" >/dev/null
  snap 104_color_default.png 2.5
  "${ADB[@]}" shell 'am start -n ru.familytv/.games.ColorVoiceGameActivity --es inject_transcript "красный"' 2>/dev/null || true
  snap 105_color_feedback.png 2.0

  force
  "${ADB[@]}" shell am start -n "$PKG/.player.VideoPlayerActivity" --es contentId video-warm >/dev/null
  snap 110_video_player.png 2.5
  key 23
  snap 111_video_controls.png 1.0

  force; start_home
  # continue watching section may be empty
  if tap_xy_from_text "Продолжить"; then snap 112_continue_watching_home.png 1.0
  else snap 112_continue_watching_home.png 0.8
  fi

  "${ADB[@]}" shell am start -n "$PKG/.home.ContentDetailsActivity" --es contentId adv-space-expedition >/dev/null
  snap 113_content_details.png 2.2
}

capture_parent() {
  log "=== PARENT ==="
  force
  "${ADB[@]}" shell am start -n "$PKG/.parent.ParentModeActivity" >/dev/null
  snap 120_parent_pin.png 2.0

  "${ADB[@]}" shell am start -n "$PKG/.parent.ParentModeActivity" --ez unlocked true >/dev/null
  snap 121_parent_dashboard.png 2.5
  key 20; key 20
  snap 122_parent_age_range.png 1.0
  key 20; key 20
  snap 123_parent_reading_mode.png 1.0
  key 20; key 20
  snap 124_parent_screen_time.png 1.0
  key 20; key 20
  snap 125_parent_activity_history.png 1.0
  key 20; key 20
  snap 126_parent_packages.png 1.0
  # trial — may need scroll to debug section
  key 20; key 20; key 20
  snap 127_parent_trial_summary.png 1.0
}

capture_errors_voice() {
  log "=== ERRORS / VOICE ==="
  seed_adv space-expedition launch_search
  start_adv adv-space-expedition
  sleep 2
  "${ADB[@]}" shell 'am start -n ru.familytv/.adventure.AdventureActivity --es contentId adv-space-expedition --es inject_transcript "Расскажи про чёрную дыру"'
  snap 130_unknown_lumi.png 3.0
  "${ADB[@]}" shell 'am start -n ru.familytv/.adventure.AdventureActivity --es contentId adv-space-expedition --es inject_transcript "Почему Луна или корабль?"'
  snap 131_clarification_lumi.png 3.0

  # Offline — remove reverse briefly
  "${ADB[@]}" reverse --remove tcp:8080 >/dev/null 2>&1 || true
  force; start_home
  snap 132_offline_content_available.png 2.5
  "${ADB[@]}" reverse tcp:8080 tcp:8080 >/dev/null 2>&1 || true

  force; start_home
  sleep 1.5
  if tap_xy_from_text "Сказать" || tap_xy_from_text "🎤"; then
    snap 133_mic_unavailable_or_permission.png 1.5
  else
    snap 133_mic_unavailable_or_permission.png 0.8
  fi

  # child-safe error — unavailable adventure via bad id if activity handles
  force
  "${ADB[@]}" shell am start -n "$PKG/.adventure.AdventureActivity" --es contentId missing-adventure-xyz >/dev/null
  snap 134_child_safe_error.png 2.0

  # Voice UI states on home
  force; start_home
  snap 140_voice_ready.png 2.0
  if tap_xy_from_text "Сказать" || tap_xy_from_text "🎤"; then
    snap 141_voice_listening.png 1.2
    sleep 1
    snap 142_voice_processing.png 0.8
  else
    cp -f "$SHOT/140_voice_ready.png" "$SHOT/141_voice_listening.png"
    cp -f "$SHOT/140_voice_ready.png" "$SHOT/142_voice_processing.png"
  fi
  "${ADB[@]}" shell 'am start -n ru.familytv/.home.HomeActivity --es inject_transcript "покажи карту"'
  snap 143_voice_result.png 2.5
  "${ADB[@]}" shell 'am start -n ru.familytv/.home.HomeActivity --es inject_transcript "повтори"'
  snap 144_voice_repeat_command.png 2.0
}

# Age filter screenshots — same adventure intro with different age prefs
capture_age() {
  log "=== AGE BANDS ==="
  for pair in "153_age_3_5.png:3:5" "154_age_5_7.png:5:7" "155_age_7_9.png:7:9"; do
    IFS=: read -r fname amin amax <<<"$pair"
    force
    # family settings age keys from ParentStores
    set_pref_string familytv_family_settings age_min "$amin" 2>/dev/null || true
    # Prefer writing ints if keys exist — Home filter uses contentFilter ageMin/ageMax
    "${ADB[@]}" shell "run-as $PKG cat shared_prefs/familytv_family_settings.xml" > /tmp/fs.xml 2>/dev/null || echo '<?xml version="1.0"?><map></map>' >/tmp/fs.xml
    python3 - <<PY
import re
xml=open('/tmp/fs.xml',encoding='utf-8').read()
for k,v in [('age_min','$amin'),('age_max','$amax')]:
    xml=re.sub(rf'<int name="{k}"[^/]*/>','',xml)
    xml=re.sub(rf'<string name="{k}">.*?</string>','',xml)
    xml=xml.replace('</map>', f'    <int name="{k}" value="{v}" />\n</map>')
open('/tmp/fs.xml','w').write(xml)
PY
    "${ADB[@]}" push /tmp/fs.xml /data/local/tmp/ftv_pref.xml >/dev/null
    "${ADB[@]}" shell "run-as $PKG cp /data/local/tmp/ftv_pref.xml shared_prefs/familytv_family_settings.xml"
    start_home
    snap "$fname" 2.2
  done
}

record_clip() {
  local name="$1" secs="$2" prep_cmd="$3"
  log "record $name (${secs}s)"
  force
  eval "$prep_cmd"
  sleep 1.2
  "${ADB[@]}" shell screenrecord --time-limit "$secs" /sdcard/ftv_rec.mp4 &
  local rpid=$!
  sleep 0.5
  # light navigation during record
  for _ in $(seq 1 "$secs"); do
    key 22 || true
    sleep 0.35
    key 20 || true
    sleep 0.35
  done
  wait $rpid 2>/dev/null || true
  sleep 0.5
  "${ADB[@]}" pull -a /sdcard/ftv_rec.mp4 "$REC/$name" >/dev/null
  log "saved $name"
}

capture_recordings() {
  log "=== RECORDINGS ==="
  record_clip 01_home_navigation.mp4 25 'start_home'
  record_clip 02_space_mechanics.mp4 40 'seed_adv space-expedition launch_search; start_adv adv-space-expedition'
  record_clip 03_lumi_repeat_help.mp4 25 'seed_adv space-expedition launch_search; start_adv adv-space-expedition'
  record_clip 04_journey_map.mp4 25 'start_map space-grand-expedition'
  record_clip 05_weather_discovery.mp4 40 'seed_adv weather-lab predict_melt; start_adv adv-weather-lab'
  record_clip 06_story_builder.mp4 35 '"${ADB[@]}" shell am start -n "$PKG/.creative.CreativeStudioActivity" >/dev/null'
}

main() {
  local phase="${PHASE:-all}"
  log "device=$DEVICE phase=$phase"
  "${ADB[@]}" wait-for-device
  "${ADB[@]}" reverse tcp:8080 tcp:8080 >/dev/null 2>&1 || true
  case "$phase" in
    home) capture_home ;;
    lumi) capture_lumi ;;
    space) capture_space ;;
    forest) capture_forest ;;
    weather) capture_weather ;;
    story) capture_story ;;
    journey) capture_journey_journal ;;
    games) capture_games_video ;;
    parent) capture_parent ;;
    errors) capture_errors_voice ;;
    age) capture_age ;;
    rec) capture_recordings ;;
    from_lumi)
      capture_lumi
      capture_space
      capture_forest
      capture_weather
      capture_story
      capture_journey_journal
      capture_games_video
      capture_parent
      capture_errors_voice
      capture_age
      capture_recordings
      ;;
    all|*)
      capture_home
      capture_lumi
      capture_space
      capture_forest
      capture_weather
      capture_story
      capture_journey_journal
      capture_games_video
      capture_parent
      capture_errors_voice
      capture_age
      capture_recordings
      ;;
  esac
  log "CAPTURE DONE shots=$(ls -1 "$SHOT"/*.png 2>/dev/null | wc -l) recs=$(ls -1 "$REC"/*.mp4 2>/dev/null | wc -l)"
}

main "$@"
