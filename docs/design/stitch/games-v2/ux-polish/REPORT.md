# Games V2 — final UX polish (debug only)

The existing approved preview is retained. No production promotion, new mechanics, new AI artwork or background changes.

## Changes

1. **Objects before numbers.** Math initially renders only the two baskets and their combine action. Equation and answer cards do not exist in the view tree until the combine animation completes. Then the equation ends in `?`, three answers become available, and focus starts at the first card (4 in the first round). Preview state rejects answer submissions before combination or during Help. Every new round starts with objects again.
2. **Help scaffolds counting.** Help preserves the same apples, submitted answer and `?`. Focus stays on Help throughout and afterward. The apples highlight one at a time with cumulative labels 1–5, then labels fade away. Counting beats last 850 ms independently of the system animator scale. The narration request is “Посчитаем вместе. Один, два, три, четыре, пять.” Later rounds count only their own three/four apples. Repeat Help restarts at one and never submits an answer. Pausing cancels the transient sequence safely.
3. **Lumi reacts.** The existing illustrated Lumi gets native cyan attention pulses for Help, a short restrained pulse for Wrong, and warmer glow/rays for Success and Launch. The existing header Lumi also tilts softly on Wrong and bounces on Success/Launch. During counting, attention dots connect Lumi to the active apple. All effects settle back to idle; there are no reward stars, coins, scores or confetti. No pixels in the supplied background assets were edited.

A device-discovered focus issue was also corrected in the debug control setup: explicit initial focus now works when returning from Android Settings in touch mode. This does not change Back routing.

## Review evidence

| Acceptance item | Evidence |
| --- | --- |
| Before combine: no answer nodes or numeric focus | [World-only Math](screenshots/02_math_world_only.png), [D-pad cannot reach answers](screenshots/03_before_combine_no_answer_focus.png), [native accessibility tree](logs/stage_a.xml) |
| Still no numbers during combine; actionable after completion | [Combine in progress](screenshots/04_combine_in_progress.png), [answers available](screenshots/05_answers_available.png), [native accessibility tree](logs/stage_b.xml) |
| Help retains `?`, never selects/confirms 5 | [Help finished](screenshots/08_help_finished.png), [Help repeated](screenshots/09_repeated_help_finished.png), [focus remains Help](logs/help_finished.xml) |
| Counting 1 → 2 → 3 → 4 → 5 | [1](screenshots/08_help_count_1.png), [2](screenshots/08_help_count_2.png), [3](screenshots/08_help_count_3.png), [4](screenshots/08_help_count_4.png), [5](screenshots/08_help_count_5.png) |
| Child explicitly returns to 4, selects 5, then submits | [4](screenshots/10_child_returns_to_4.png), [5 selected with `?` still present](screenshots/11_child_selects_5.png), [explicit success](screenshots/12_math_success_lumi.png) |
| Soft Lumi reactions | [Math Wrong](screenshots/06_math_wrong_lumi.png), [Help](screenshots/08_help_count_3.png), [Math Success](screenshots/12_math_success_lumi.png), [Color Wrong](screenshots/15_color_wrong_lumi.png), [Color Success](screenshots/17_color_success_lumi.png), [Launch](screenshots/19_launch_lumi.png) |
| Color behavior retained | [red persists](screenshots/16_red_wing_persists.png), [blue waits for Launch](screenshots/18_blue_wing_waits_for_launch.png), [launch completed](screenshots/20_launch_complete.png) |
| Pause/resume Help and finite Math ending | [Help resume](screenshots/25_help_pause_resume.png), [Help repeated after resume](screenshots/26_help_after_resume_finished.png), [finite ending](screenshots/24_finite_return.png) |

[New continuous native H728 recording](recordings/games-v2-ux-polish.mp4). It covers Home → Math world → combine → answers → wrong → Help → repeated Help → child choice → correct → Color → red paint → blue paint → explicit Launch → Home. It is unedited, with no generated mockup frames. Native screenrecord has no audio track.

## Reproduce

```sh
adb shell am start -n ru.familytv/.games.stitch.StitchGamesPreviewActivity
python3 tools/qa/stitch_games_v2_polish.py --normal-animation
```

The QA script temporarily uses normal 1× animator duration for review and restores the original setting in `finally`. Without the flag it honors the device setting. It preserves app data and uses ADB D-pad input only.

## Validation

- `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`: **passed**. **173 tests, 0 failures/errors/skips**. Four added regression tests cover staged answer gating, repeated Help without answer reveal/selection/submission, cancellation, and the finite round reset. [Build](logs/build.log), [tests](logs/unit-tests.json).
- Lint: **0 errors, 246 warnings** across the debug project. Existing/programmatic-view/KTX suggestions remain. [Lint](logs/lint.txt).
- **36 device checks completed** on H728 / ADT-3, Android 14, 1920×1080. Native accessibility trees prove 0 answer nodes before combination and 3 enabled/focusable answer nodes afterward. [Assertions](logs/checks.json), [device](logs/device.json).
- Main flow is one **73.10-second unedited H.264 recording**, fully decoded without errors. The five numeral stills are full frames decoded from that recording; other stills use native screencap. [Frame provenance/times](logs/count-frame-times.json), [video metadata](logs/video-info.json).
- Repeated Help passed twice in the main flow. The supplemental pause/resume check passed on the same Activity instance using explicit singleTop resume (Android Settings had reused a nested task); another 1–5 sequence passed at the original 0.25× animator scale. This supplemental check follows the recorded main flow. The QA script now handles that Settings behavior. [Execution log, including harness retries](logs/acceptance.txt).
- **266 protected files unchanged**: all 255 production source-set files plus all 11 existing debug artwork/provenance files. No approved backgrounds or production `AnswerJudge` changes. [SHA256 audit](logs/protected-audit.json).
- No captured app crash/ANR; AI runtime remained uninitialized. Original animator setting restored. [Final verification](logs/final-verification.json), [setting restoration](logs/animation-setting.json), [tested APK identity](logs/apk.json).

## Limits

The large Lumi illustration is baked into the approved background. Its reaction is a native halo/attention overlay; the small header character supplies tilt/bounce. There is no new expression artwork or replacement scene. Narration uses the existing device TTS/settings; voice recognition and subjective audio quality are not validated by the silent screenrecord. Physical handheld remote and child usability were not separately tested. The preview remains debug-only.
