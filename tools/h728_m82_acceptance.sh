#!/usr/bin/env bash
# Milestone 8.2 H728 acceptance — journeys / journal / branch / resume / offline
set -euo pipefail
DEVICE="${DEVICE:-192.168.68.109:5555}"
PKG=ru.familytv
ADB=(adb -s "$DEVICE")
MAP="$PKG/.journey.JourneyMapActivity"
HOME="$PKG/.home.HomeActivity"
JOURNAL="$PKG/.journey.DiscoveryJournalActivity"

log() { echo "[m82] $*"; }

pss_kb() {
  "${ADB[@]}" shell dumpsys meminfo "$PKG" 2>/dev/null | awk '/TOTAL PSS:/{print $3; exit}'
}

"${ADB[@]}" wait-for-device
log "connect ok"
cd "$(dirname "$0")/.."
./gradlew :app:installDebug -q
"${ADB[@]}" reverse tcp:8080 tcp:8080 || true
"${ADB[@]}" shell am force-stop "$PKG"
"${ADB[@]}" logcat -c

log "=== 1 Fresh Home ==="
T0=$(date +%s%3N)
"${ADB[@]}" shell am start -W -n "$HOME" | tee /tmp/m82_home.txt
sleep 3
HOME_PSS=$(pss_kb)
HOME_MS=$(awk -F'TotalTime: ' '/TotalTime:/{print $2}' /tmp/m82_home.txt | head -1)
AI_LINES=$("${ADB[@]}" logcat -d -t 120 | grep -E "VoiceRuntime|AiReadiness|AI=0|models loaded|Qwen" | head -15 || true)
log "Home TotalTime=${HOME_MS:-?} PSS_KB=${HOME_PSS:-?}"
log "AI_log<<"; echo "$AI_LINES"; log ">>AI_log"

log "=== 2 Space journey progress (debug extras) ==="
"${ADB[@]}" shell am start -n "$MAP" --es journey_id space-grand-expedition \
  --es debug_complete_content adv-space-expedition
sleep 2
"${ADB[@]}" shell am start -n "$MAP" --es journey_id space-grand-expedition \
  --es debug_complete_node moon-hub
sleep 1
"${ADB[@]}" shell am start -n "$MAP" --es journey_id space-grand-expedition \
  --es debug_branch_from moon-hub --es debug_branch to-light
sleep 1
"${ADB[@]}" shell am start -n "$MAP" --es journey_id space-grand-expedition \
  --es debug_complete_content game-color
sleep 1
"${ADB[@]}" shell am start -n "$MAP" --es journey_id space-grand-expedition \
  --es debug_complete_content adv-weather-lab
sleep 1
"${ADB[@]}" shell am start -n "$MAP" --es journey_id space-grand-expedition \
  --es debug_complete_content move-stretch
sleep 1
"${ADB[@]}" shell am start -n "$MAP" --es journey_id space-grand-expedition \
  --es debug_complete_content video-warm
sleep 1
"${ADB[@]}" shell am start -n "$MAP" --es journey_id space-grand-expedition \
  --es debug_complete_node calm-end
sleep 2
MAP_PSS=$(pss_kb)
log "Map after progress PSS_KB=${MAP_PSS:-?}"
"${ADB[@]}" logcat -d -t 80 | grep FamilyJourney | tail -25 || true

log "=== 3 Journal + what we discovered ==="
"${ADB[@]}" shell am start -n "$JOURNAL"
sleep 2
JOURNAL_PSS=$(pss_kb)
log "Journal PSS_KB=${JOURNAL_PSS:-?}"
"${ADB[@]}" shell am start -n "$HOME"
sleep 1
"${ADB[@]}" shell 'am start -n ru.familytv/.home.HomeActivity --es inject_transcript "что мы узнали?"'
sleep 2
"${ADB[@]}" logcat -d -t 40 | grep -E "FamilyHome|inject" | tail -15 || true

log "=== 4 Force-stop resume ==="
"${ADB[@]}" shell am force-stop "$PKG"
sleep 1
"${ADB[@]}" shell am start -n "$HOME"
sleep 2
"${ADB[@]}" shell am start -n "$MAP" --es journey_id space-grand-expedition
sleep 2
"${ADB[@]}" logcat -d -t 30 | grep FamilyJourney | tail -10 || true

log "=== 5 Forest journey ==="
"${ADB[@]}" shell am start -n "$MAP" --es journey_id forest-secrets \
  --es debug_complete_content adv-forest-trail
sleep 1
"${ADB[@]}" shell am start -n "$MAP" --es journey_id forest-secrets \
  --es debug_complete_node owl-note
sleep 1
"${ADB[@]}" shell am start -n "$MAP" --es journey_id forest-secrets \
  --es debug_complete_content video-forest
sleep 1
"${ADB[@]}" shell am start -n "$MAP" --es journey_id forest-secrets \
  --es debug_complete_node forest-end
sleep 2
"${ADB[@]}" logcat -d -t 40 | grep FamilyJourney | tail -15 || true

log "=== 6 Offline (no reverse) ==="
"${ADB[@]}" reverse --remove tcp:8080 || true
"${ADB[@]}" shell am force-stop "$PKG"
sleep 1
"${ADB[@]}" shell am start -n "$HOME"
sleep 2
OFF_PSS=$(pss_kb)
"${ADB[@]}" shell am start -n "$MAP" --es journey_id space-grand-expedition
sleep 1
"${ADB[@]}" shell am start -n "$JOURNAL"
sleep 1
log "Offline Home PSS_KB=${OFF_PSS:-?} (journeys from assets)"

log "=== 7 Restore reverse ==="
"${ADB[@]}" reverse tcp:8080 tcp:8080 || true

cat <<EOF

M82_SUMMARY
HOME_MS=${HOME_MS:-?}
HOME_PSS_KB=${HOME_PSS:-?}
MAP_PSS_KB=${MAP_PSS:-?}
JOURNAL_PSS_KB=${JOURNAL_PSS:-?}
OFFLINE_HOME_PSS_KB=${OFF_PSS:-?}
EOF
