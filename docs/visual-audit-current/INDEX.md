# FamilyTV Visual Audit Pack — INDEX

**Purpose:** Honest visual snapshot of the **current** FamilyTV build on real H728 after Milestone 8.2.  
**Not** a redesign pass. Placeholders and engineering UI are intentional in this pack.

**Device:** `192.168.68.109:5555` · 1920×1080 · Android 14 · see `DEVICE_STATE.md`  
**Contact sheets:** `contact_*.png` in this folder (thumbnails with filenames).  
**Recordings:** `recordings/*.mp4` (screenrecord; **no internal audio** on this device path).

### Legend

- **REAL** — UI shown by normal Activity launch + remote/D-pad/tap  
- **SEEDED** — adventure/journey progress prefs written so a real scene opens (existing stores)  
- **INJECT** — `inject_transcript` / debug extras for voice/dialogue/journey nodes  
- **KNOWN VISUAL ISSUE** — noted for reviewers; **not** fixed before capture  

---

## Home

| File | Screen / state | How | Content | Notes for reviewer |
|------|----------------|-----|---------|-------------------|
| `01_home_default.png` | Home top — brand, Lumi, journey hero | REAL | Journey hero + continuity chips | Soft pastel Home; journey banner dominant |
| `02_home_hero_focused.png` | Journey hero / CTA focus | REAL | Space grand expedition | Focus on «Начать» / hero |
| `03_home_explore_focused.png` | Mid Home scroll | REAL | Explore-ish region | **KNOWN:** focus/section targeting via D-pad is imprecise; may not be exact «Исследуй» header |
| `04_home_solve_focused.png` | Solve / games area | REAL | Math/Color cards | Same caveat |
| `05_home_create_focused.png` | Create region | REAL | Story / create shelf | Same caveat |
| `06_home_stories_focused.png` | Stories region | REAL | Stories shelf | Same caveat |
| `07_home_move_focused.png` | Move region | REAL | Разминка video card | Same caveat |
| `08_home_continue_journey.png` | Home with journey continuity | REAL | Completed Space journey copy | «Мы закончили…» / hero |
| `09_home_today.png` | Today section area | REAL | Today shelf | Scroll-dependent |
| `10_home_non_reader_mode.png` | Non-reader simulation ON | REAL (debug pref) | Home | Text reduced; icons denser — **debug QA mode**, not Parent setting |
| `11_home_audio_first_state.png` | ReadingSupportMode AUDIO_FIRST | REAL (pref) | Home | Presentation pref; layout largely same as other modes |
| `85_home_continue_journey_after_progress.png` | Alias of continue journey | REAL | Same as 08 | After journey prefs |

---

## Lumi

Guide emotions are scene-driven. Several requested names map to overlapping visuals.

| File | Intended state | How | Honest note |
|------|----------------|-----|-------------|
| `20_lumi_idle.png` | Idle / curious on Home | REAL | Lumi avatar top-left on Home |
| `21_lumi_listening.png` | Listening-ish | REAL/SEEDED | Adventure or mic affordance; may not equal VoiceRuntime LISTENING |
| `22_lumi_thinking.png` | Thinking | SEEDED `science_talk` | Dialogue scene guideEmotion THINKING |
| `23_lumi_helping.png` | Helping | SEEDED + Help tap attempt | If Help not hit, still challenge chrome |
| `24_lumi_happy.png` | Happy | SEEDED `finale` | Reflection / end beat |
| `25_lumi_encouraging.png` | Encouraging | SEEDED forest `hello` | Forest intro guide |
| `26_lumi_surprised.png` | Surprised / excited | REAL intro Space | Intro uses EXCITED; **no distinct SURPRISED art** if asset shared |

**KNOWN:** Lumi is a small circular avatar + emotion enum; many emotions share similar drawn frames — do not expect large character acting.

---

## Space Adventure

Package/content: `adv-space-expedition` / adventure id `space-expedition`.

| File | State | How | Notes |
|------|-------|-----|-------|
| `30_space_intro.png` | Intro STORY | REAL (clear progress) | Purple space stage, «Дальше», Lumi |
| `31_space_mission.png` | Visual search challenge | SEEDED `launch_search` | Same family as 32 |
| `32_space_visual_search.png` | VISUAL_SEARCH | SEEDED | Object tiles (пробка/камень/…) |
| `33_space_sort_classify.png` | SORT_AND_CLASSIFY | SEEDED `planet_sort` | Water/land/air bins |
| `34_space_pattern_sequence.png` | PATTERN_SEQUENCE | SEEDED `repair_pattern` | 2,4,?,8 |
| `35_space_path_maze.png` | PATH_MAZE | SEEDED `nav_maze` | Even-number grid |
| `36_space_repeat.png` | Repeat affordance | SEEDED + tap | Icon buttons near actions |
| `37_space_help.png` | Help affordance | SEEDED + tap | |
| `38_space_group_prompt.png` | Group discuss line | SEEDED | «Обсудите вместе…» |
| `39_space_offscreen.png` | OFFSCREEN | SEEDED `offscreen_circle` | Room circle hunt |
| `40_space_ask_lumi.png` | Ask Lumi | INJECT attempted | **KNOWN:** capture often still shows challenge UI if inject races; dialogue may be in guide line only |
| `41_space_world_reaction.png` | After object tap | SEEDED + tap | Selection highlight |
| `42_space_completion.png` | Finale reflection | SEEDED `finale` | Calm end copy |

---

## Forest Adventure

`adv-forest-trail` / `forest-trail`.

| File | State | How | Notes |
|------|-------|-----|-------|
| `50_forest_intro.png` | Intro | REAL | Green theme vs Space purple — **same chrome pattern**, different palette/copy |
| `51_forest_scene.png` | Search scene | SEEDED | |
| `52_forest_visual_search.png` | VISUAL_SEARCH | SEEDED | Living creatures |
| `53_forest_classification.png` | SORT_AND_CLASSIFY | SEEDED | Plant/animal |
| `54_forest_owl_question.png` | Ask Lumi owl | INJECT | May show challenge + guide answer |
| `55_forest_ask_lumi_followup.png` | Follow-up «А днём?» | INJECT | |
| `56_forest_help.png` | Help | SEEDED | |
| `57_forest_group_prompt.png` | Group prompt | SEEDED | |
| `58_forest_completion.png` | End reflection | SEEDED `end` | |

**Reviewer takeaway:** Forest is **themed reskin + different tasks**, not a wholly different product shell.

---

## Weather Lab / Discovery

`adv-weather-lab` / `weather-lab`.

| File | State | How | Notes |
|------|-------|-----|-------|
| `60_weather_lab_intro.png` | Intro | REAL | Ice / predict framing |
| `61_weather_prediction.png` | PREDICT_AND_TEST options | SEEDED | Sun/shade/cold |
| `62_weather_test_setup.png` | After/near prediction | REAL tap | |
| `63_weather_build_test.png` | BUILD_AND_TEST | SEEDED `build_shelter` | |
| `64_weather_observation.png` | Observation after test | INJECT «проверить» | |
| `65_weather_ask_why.png` | WHY ice | INJECT | |
| `66_weather_ask_to_do.png` | Ask→Do invite | INJECT «теплее» | |
| `67_weather_result.png` | Observed result | INJECT test | |

---

## Creative Studio / Story

`CreativeStudioActivity` / `lumi-story`.

| File | State | How | Notes |
|------|-------|-----|-------|
| `70_story_start.png` | Menu / start | REAL | |
| `71_story_choose_hero.png` | Hero step | REAL | |
| `72_story_choose_world.png` | World step | REAL | |
| `73_story_natural_voice_mapping.png` | Freeform map | INJECT | «маленького смешного робота» |
| `74_story_branch.png` | Later choice | REAL | |
| `75_story_result.png` | Retell / result | REAL | May be mid-flow if finish not reached |
| `76_story_replay_gallery.png` | Journal stories | REAL | Gallery in Discovery Journal |

---

## Journey / Journal

| File | State | How | Notes |
|------|-------|-----|-------|
| `80_space_journey_map_start.png` | Map start | REAL | Nodes Старт→…; dark map chrome |
| `81_space_journey_map_progress.png` | After launch complete | SEEDED prefs | Moon open |
| `82_space_journey_branch.png` | Branch / hub | SEEDED/INJECT debug | Light vs Heat path |
| `83_space_journey_completed.png` | Journey done | SEEDED prefs | Completion line |
| `84_forest_journey_map.png` | Forest map | REAL | Trail style |
| `90_journal_default.png` | Journal | REAL | Unlocked entries |
| `91_journal_facts.png` | Facts region | REAL | Scroll |
| `92_journal_experiments.png` | Experiments region | REAL | May overlap facts if short list |
| `93_journal_stories.png` | Stories | REAL | |
| `94_journal_after_space.png` | Filtered Space | REAL | |
| `95_journal_after_forest.png` | Filtered Forest | REAL | |

---

## Math / Color / Video

| File | State | How | Notes |
|------|-------|-----|-------|
| `100_math_default.png` | Math game | REAL | Legacy game chrome vs adventures |
| `101_math_repeat_help.png` | After Repeat tap | REAL | |
| `102_math_feedback_correct.png` | Feedback | Attempted | **INJECT unsupported** on Math — may duplicate default |
| `103_math_feedback_wrong_or_hint.png` | Feedback | Attempted | Same limitation |
| `104_color_default.png` | Color game | REAL | |
| `105_color_feedback.png` | Feedback | Attempted inject | May duplicate default |
| `110_video_player.png` | Video | REAL `video-warm` | Dark player |
| `111_video_controls.png` | After OK | REAL | Large frame possible |
| `112_continue_watching_home.png` | Continue section | REAL | May be empty |
| `113_content_details.png` | Details Space | REAL | |

---

## Parent Mode

Opened with `--ez unlocked true` for dashboard (no PIN digits shown). PIN gate shown empty.

| File | State | How | Notes |
|------|-------|-----|-------|
| `120_parent_pin.png` | PIN gate | REAL | Empty field — **no secret PIN visible** |
| `121_parent_dashboard.png` | Unlocked top | REAL debug unlock | Today / history |
| `122_parent_age_range.png` | Age filter area | REAL scroll | |
| `123_parent_reading_mode.png` | Reading modes | REAL scroll | |
| `124_parent_screen_time.png` | Screen time | REAL scroll | |
| `125_parent_activity_history.png` | History | REAL scroll | |
| `126_parent_packages.png` | Packages | REAL scroll | |
| `127_parent_trial_summary.png` | Trial / debug area | REAL scroll | May still be packages if scroll short |

**KNOWN:** Parent UI is utilitarian engineering settings, not child-world polish.

---

## Errors / Voice / Reading / Age

| File | State | How | Notes |
|------|-------|-----|-------|
| `130_unknown_lumi.png` | UNKNOWN | INJECT black hole | If race, challenge UI — check guide text |
| `131_clarification_lumi.png` | Clarification | INJECT | Options moon/ship when successful |
| `132_offline_content_available.png` | Offline Home | REAL (reverse off) | Bundled content still launches |
| `133_mic_unavailable_or_permission.png` | Mic affordance | REAL tap mic | Permission / listening chrome |
| `134_child_safe_error.png` | Bad content id | REAL | Soft unavailable |
| `140_voice_ready.png` | Home ready | REAL | Mic not auto |
| `141_voice_listening.png` | Listening | REAL tap | |
| `142_voice_processing.png` | Processing | REAL | May equal listening if ephemeral |
| `143_voice_result.png` | After inject map | INJECT «покажи карту» | Often lands on Journey Map |
| `144_voice_repeat_command.png` | Repeat | INJECT | |
| `150_audio_first.png` | AUDIO_FIRST | REAL pref | Copy of 11 |
| `151_audio_and_text.png` | AUDIO_AND_TEXT | REAL | Layout largely unchanged |
| `152_text_rich.png` | TEXT_RICH | REAL | Layout largely unchanged |
| `153_age_3_5.png` … `155_age_7_9.png` | Age filter | REAL prefs | **Shelf filtering**; not a different art system |

**Reading modes:** visual difference is subtle (narration/behavior more than layout). Documented honestly rather than faking three unique UIs.

---

## Recordings

| File | Duration target | Content | Audio |
|------|-----------------|--------|-------|
| `recordings/01_home_navigation.mp4` | ~25s | D-pad around Home | No internal audio |
| `recordings/02_space_mechanics.mp4` | ~40s | Space visual search | No |
| `recordings/03_lumi_repeat_help.mp4` | ~25s | Space challenge chrome | No |
| `recordings/04_journey_map.mp4` | ~25s | Journey map | No |
| `recordings/05_weather_discovery.mp4` | ~40s | Weather predict | No |
| `recordings/06_story_builder.mp4` | ~35s | Creative Studio | No |

---

## Impossible / weak captures (honest)

| Requested | Outcome |
|-----------|---------|
| Distinct Lumi SURPRISED vs HAPPY art | Shared / similar avatar frames |
| Guaranteed Ask Lumi answer frame every time | Race with inject + singleTop; some shots still show challenge |
| Math/Color voice feedback frames | No inject path on those Activities |
| Perfect Home section focus for every shelf | D-pad/uiautomator imprecise on nested HorizontalScrollViews |
| Age 3–5 / 5–7 / 7–9 unique art | Only content filter, not separate skins |
| Acoustic listening waveform UI | Mic not used as ASR lab |

---

## Contact sheets

- `contact_home.png`
- `contact_space.png`
- `contact_forest.png`
- `contact_discovery_create.png`
- `contact_journey_journal.png`
- `contact_parent.png`

---

## Overall product impression (for external reviewer)

**Already feels like a TV kids product:** Home worlds, journey hero, Lumi avatar, themed adventure chrome, journal continuity, Parent Mode separation.

**Still visibly engineering / placeholder:** repeated geometric icons on Home cards, adventure “world stage” as simple shapes, text-heavy challenge prompts, Math/Color legacy look, Parent utilitarian forms, Lumi as small badge rather than full character performance.
