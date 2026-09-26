# Milestone 7.2 — FamilyTV V1 Production Candidate

Device: ADT-3 / H728 (`192.168.68.109:5555`).  
Date: 2026-09-19.

## Verdict

**PASS as FIRST CHILD TRIAL CANDIDATE (with known blockers).** Architecture frozen. V1 Home shelf curated; Lumi personality softened; Space/Forest + Math/Color polish; ambience; trial telemetry; voice script + checklist.  
**Not** FINAL_ART / FINAL_VOICE — those remain explicit blockers before scaled trial.

Primary success: coherent child-facing V1 loop without new frameworks.

---

## 1. V1 product scope

**In:** Home, Lumi, Space, Forest, Math, Color, Move, Parent Mode, Continue, AUDIO_FIRST path.  
**Out of Home shelf:** demo videos / experimental interactive (`dev_only` tags).  
Priority order respected: Home → Lumi → Space → Forest → mechanics → Math/Color.

## 2. Home V1

- `V1ContentShelf` filters `v1` vs `dev_only` / `experimental`
- World portal icons + hero mission + spoken AUDIO_FIRST labels
- Debug: DEV shelf toggle in Parent Mode

Screenshot: `docs/images/milestone-72/01-home-v1.png`

## 3. Lumi V1 candidate

- Spec: `docs/LUMI_PRODUCTION_SPEC.md` + `LumiAssets` slots  
- Personality: GuideDirector never says «Неправильно!» — uses «Посмотрим ещё раз.» / «Хочешь, помогу?» / «Интересная мысль!»  
- Art status: **V1_CANDIDATE** procedural + slot pipeline — **FINAL_ART** still required

## 4. Space flagship

Mission-purpose copy, paced narration, world-reaction lines by scene, ambience `ambience/space.wav`, glyph mechanics, icon Repeat/Help. Screenshot: `10-space.png`, `11-space-mechanic.png`

## 5. Forest

Distinct calm identity + forest ambience; same runtime. Screenshot: `20-forest.png`

## 6. Mechanic polish

Sort bins, pattern blank highlight, path ship glyph, visual-search tiles — from 7.1, retained.

## 7. Narration / voice result

Quality marker: **V1_CANDIDATE_VOICE** (semantic, paced).  
**Blocker:** `FINAL_VOICE_RECORDING_REQUIRED` — do not claim FINAL_VOICE.

## 8. V1 voice script

`docs/V1_VOICE_SCRIPT_RU.md` — COMMON / HOME / SPACE / FOREST / MATH / COLOR / SESSION / HELP / REPEAT / RECOVERY / COMPLETION.

## 9. Audio mix

Narration ducks SFX; optional low ambience; soft success; soft wrong.

## 10. Non-reader gate

NON_READER_SIM + icon Repeat/Help + world icons + spoken labels remain the acceptance path (from 7.1).

## 11. Age variants

Guidelines in `docs/AGE_COMPLEXITY.md`; metadata ageMin/Max on V1 items; validator warnings for dense choices.

## 12. Group UX

Discuss / someone_answer / choose_together phrases + on-screen group prompts.

## 13. Math / Color polish

Icon Repeat/Help; shared FamilyAction path; Lumi-aligned soft feedback language via coordinator.

## 14. V1 content curation

Manifest `catalogVersion: 3` — Space/Forest/Math/Color/Move/warm story = `v1`; demos = `dev_only` with empty `homeSections`.

## 15–16. Trial telemetry + report

`TrialEventStore` + Parent Mode **TRIAL_MODE** + summary dialog (local JSON: duration, opens, completes, repeat/help/retry/offscreen, exit points). No raw voice. Explicitly not a skill score.

## 17. H728 performance

| Metric | Target | Measured (smoke) |
|--------|--------|------------------|
| Cold Home | ≤ ~3 s | **2348 ms** |
| Home PSS | ≤ ~130 MB | **~117 MB** |
| Space PSS (no AI) | — | **~119 MB** |
| Forest PSS | — | **~122 MB** |
| AI=0 Home (fresh) | yes | **yes** |
| After Math (AI loaded) | expect rise | **~1 GB PSS** — VoiceRuntime lease from Math; force-stop clears |

## 18. 30-minute stability

Short multi-surface smoke (Home→Space→Forest→Math→Home): no FATAL/ANR in sampled logcat. **Full 30-minute soak** still recommended before family trial day. Note: Math/Color intentionally load AI; return to Home does not unload models until process death.

## 19. Screenshots

`docs/images/milestone-72/` — Home, Space, Space mechanic, Forest, Math.

## 20. V1 release checklist

`docs/V1_RELEASE_CHECKLIST.md` — PASS/FAIL child experience vs separate FINAL_ART / FINAL_VOICE / MIC_ACOUSTIC tracks.

## 21. Remaining blockers before real child trial

1. **FINAL_VOICE_RECORDING_REQUIRED** (talent or HQ TTS → replace V1_CANDIDATE)
2. **FINAL_ART** for Lumi + mechanic props (glyphs interim)
3. Silent-adult seated playtest sign-off on NON_READER_SIM
4. Optional full 30-minute soak + Graphics PSS notes
5. Accept V1_CANDIDATE_VOICE explicitly for first micro-trial if talent delayed

Architecture: frozen. Ready for first controlled family trial once voice/art blockers are accepted or cleared.
