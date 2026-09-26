# FamilyTV Games V2 — native Android TV debug preview

Status: debug implementation complete and ready for visual review. Device acceptance was captured on 2026-09-25; saved artifacts were reverified on 2026-09-26. This is a debug experiment; no production Math/Color promotion or design approval is implied.

## Run

```sh
adb shell am start -n ru.familytv/.games.stitch.StitchGamesPreviewActivity
```

Activity, artwork and theme exist only in `app/src/debug`. Debug state tests are in `app/src/testDebug`. No entry was added to production Home. Release source sets do not contain this Activity or its assets.

## Source of truth

Only `docs/design/stitch/games-v2/`: the eight `screen.png` / `code.html` pairs and `familytv_android_tv_cosmic_hearth_v2/DESIGN.md`. Native Views and Canvas use the 1920×1080 fixed canvas, navy surfaces, cyan focus, warm amber encouragement and 5% focused-control enlargement. The shared `ReferenceStage`/`ReferenceDrawing` supply coordinate/drawing primitives only; production game views, old quiz visuals and other design variants are not used.

Some exported screenshots have gray illustration placeholders. The illustrations linked by those same HTML files supply the approved art. Two backgrounds were edited with built-in imagegen to remove playable objects; Canvas supplies the moving apples/baskets, paint cans, rocket and wing. [Exact prompts and edit scope](ART_PROMPTS.md). Packaged WebP assets and original-source provenance are in `app/src/debug/assets/stitch_games/`. [Exported asset hashes](logs/asset-export-manifest.json) identify the exact packaged WebP files separately from source-image hashes.

## World → action → consequence

- **Math:** two baskets visibly hold 2 and 3 apples. The initial focus is on the world: OK combines both groups into one basket, with animated apple movement. The apples remain countable. Help highlights and numbers them in sequence. An incorrect count retains the same apples and round; a correct count leaves the completed basket and waits for explicit Next. The finite series is 2+3, 1+2, 2+2, then Games Home.
- **Color:** the outlined rocket wing and blue sample establish the target. Choosing a paint can moves a brush toward the wing and changes its color. Red/yellow really paint the wing and remain visible during retry. Blue unlocks an explicit Launch action. The rocket flies from its platform; a calm return button follows. No automatic launch or next task.
- **Home:** Math and Color open preview worlds. Weather and Maze retain the supplied card design and are honestly marked “Скоро”. They do not launch production games.

There are no scores, coins, stars-as-rewards, streaks, reward economy or model-generated questions. Decorative stars remain part of the approved scenery.

## Reference states

| Approved screen | Device screenshot |
| --- | --- |
| 1. Games Home | [01_games_home.png](screenshots/01_games_home.png) |
| 2. Math Game | [02_math_game.png](screenshots/02_math_game.png) |
| 3. Math Help | [03_math_help.png](screenshots/03_math_help.png) |
| 4. Math Wrong | [04_math_wrong.png](screenshots/04_math_wrong.png) |
| 5. Math Correct | [05_math_correct.png](screenshots/05_math_correct.png) |
| 6. Color Game | [06_color_game.png](screenshots/06_color_game.png) |
| 7. Color Wrong | [07_color_wrong.png](screenshots/07_color_wrong.png) |
| 8. Color Correct | [08_color_correct.png](screenshots/08_color_correct.png) |

Additional evidence: [combined apples](screenshots/09_math_combined.png), [math retry focus](screenshots/12_math_retry_focus.png), [red wing retained while blue is selected](screenshots/14_color_retry_focus.png), [rocket in flight](screenshots/10_rocket_launch.png), [empty launch platform after flight](screenshots/11_color_after_launch.png), [calm return](screenshots/15_calm_return.png).

[Continuous, unedited H728 screen recording](recordings/games-v2-flow.mp4): **44.39 seconds, H.264, 1920×1080**. Capture uses normal 1× animator duration; the device originally used 0.25×. The setting was restored after recording. The [earlier 0.25× capture](recordings/games-v2-flow-device-025x.mp4) predates the final Repeat initialization/footer refinements and is retained only as comparison evidence. [Restoration record](logs/animation-setting-restored.json). The main recording has no audio track (native screenrecord). It contains Home → Math → combine → wrong count → Help → correct count → Home → Color → red wing → blue wing → explicit Launch → Home. Screenshots and video are device captures, not generated UI mockups.

## Deliberate runtime adaptations

- The Math world is the initial actionable focus. Answer cards retain the approved visual language after combining. This supplies the requested combine/count action instead of opening as a quiz.
- Wrong paint changes the actual wing. The blue sample remains the target. Wrong math preserves the actual collection.
- No fake voice-active badge or fake screen-time figure. Header says “Пульт · OK”. Repeat uses the existing narration coordinator with device narration availability; the shared TTS provider warms asynchronously without speaking on entry; microphone/STT/Qwen are not started. Remote control is sufficient for every preview action.
- The preview has three finite math rounds and one paint/launch scene. It does not fake the reference's production mission counters.
- Simplified native dynamic props follow the reference shapes/colors. The screenshot exports' gray placeholders and inconsistent squeezed dimensions were not copied into the app.

## Architecture and lifecycle

`GamesPreviewState` owns disposable presentation state and calls unchanged production `AnswerJudge` for answer semantics. It does not mutate catalog progress, game sessions, trial logs or family settings. Assets decode once on `Dispatchers.IO`; no networking occurs in the preview. Animation frames only draw cached background bitmaps and native objects.

Explicit D-pad routes cover cards, world, answers/paints, Help, Repeat, Next/Launch and Back. Android Back uses `OnBackPressedCallback`. Back restores the selected game card. Animators stop on pause; settled semantic state and focus restore on resume and are saved in Bundle for recreation. A single focus observer is removed on destruction; action-handler detach is identity-safe.

## Validation

- `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`: passed. **169 tests, 0 failures/errors/skips.** Three new state tests cover wrong-answer retention, explicit progression/finite ending, actual wrong paint and launch gating. [Build log](logs/build.log), [unit totals](logs/unit-tests.json).
- Lint: **0 errors, 245 warnings** across the debug project. Programmatic-only custom view constructor/KTX suggestions remain; no assertion of a warning-free repository. [Lint report](logs/lint.txt).
- Source isolation: SHA256 audit of **all 255 files under `app/src/main`**, captured before this task, found **0 changed and 0 added**. Includes production Math/Color, shared rules, main manifest and assets. [Audit](logs/production-unchanged.json).
- Device: H728 / ADT-3, Android 14, arm64, physical 1920×1080. [Device details](logs/device.json). D-pad events were sent by ADB; a physical handheld remote was not separately tested.
- **23 device checkpoints passed:** eight reference states, combine/retry/launch consequences, three finite Math rounds, pause/resume retaining the red wing, Repeat and Help retaining the scene, Back focus restoration, and the future-world cards remaining in preview. See [checkpoint assertions](logs/checks.json). The suite sends D-pad events; Repeat verifies dispatch/state retention, not audible output.
- No app fatal exception or ANR appeared in the captured [bounded logcat](logs/logcat.txt). AI runtime remained uninitialized; no `.gguf`/`.onnx` [model mappings](logs/model-mappings.txt). PSS snapshots: **Home 114.5 MiB, Math 123.2 MiB, Color 126.2 MiB**; these are snapshots, not a measured peak.
- Artifact verification: **24 native screenshots**, all 1920×1080; all 10 packaged WebP hashes match; complete video decode passed. [Final verification](logs/final-verification.json), [tested APK identity](logs/apk.json), [video metadata](logs/video-info.json). The APK is `app/build/outputs/apk/debug/app-debug.apk` and was installed on H728 with app data preserved.
- Reproduce with `python3 tools/qa/stitch_games_v2_acceptance.py` after installing the current debug APK. The script honors the device's current animator duration setting and does not change it. The normal-speed evidence run used a temporary 1× setting in an external `try/finally` wrapper that restored 0.25×.

Voice recognition, subjective listening quality and child usability are outside this visual preview verification. Narration depends on the installed TTS/parent accessibility settings; visible instructions and native actions remain available.
