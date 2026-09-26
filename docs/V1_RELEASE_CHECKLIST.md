# FamilyTV V1 Release Checklist

Device target: H728 / ADT-3.  
Architecture freeze: no new runtime/dispatcher/package protocol in V1.

## Child experience (PASS/FAIL)

| Check | Status | Notes |
|-------|--------|-------|
| Home understandable without reading | | icons + spoken + Lumi |
| Space understandable without reading | | NON_READER_SIM |
| Forest understandable without reading | | |
| REPEAT works (remote + voice) | | icon affordance |
| HELP works (remote + voice) | | |
| Remote D-pad navigation | | |
| Voice action architecture (allowlisted) | | |
| Narration coverage Space/Forest semantic | | INTERIM / V1_CANDIDATE |
| Parent Mode PIN | | |
| Offline child-critical paths | | |
| Package verification / signed dist | | |
| Performance Home ≤ ~3 s / ≤ ~130 MB / AI=0 | | |
| Stability 30-min smoke | | |
| Local trial events (no raw voice) | | |

## Separate tracks (do not mix into PASS above)

| Track | Status | Notes |
|-------|--------|-------|
| FINAL_ART | | Lumi + props illustrator |
| FINAL_VOICE | | talent recording — see voice script |
| MIC_ACOUSTIC | | ASR quality not claimed |

## Content shelf

| Shelf | Meaning |
|-------|---------|
| V1_VISIBLE (`v1` tag) | Child Home |
| DEV_ONLY (`dev_only`) | Admin / debug |
| EXPERIMENTAL (`experimental`) | Hidden from V1 Home |

## Blockers before real child trial

1. FINAL_VOICE_RECORDING_REQUIRED (unless V1_CANDIDATE_VOICE accepted for trial)
2. FINAL_ART for Lumi + mechanic props
3. Seated silent-adult playtest sign-off
4. Optional: longer ambience loops + music Parent toggle UX
