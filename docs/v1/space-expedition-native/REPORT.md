# Space Expedition V1 — native production (no WebView)

Presentation/performance migration. JourneyEngine, JourneyCoordinator, progress stores, AdventureRuntime, ContentNavigator, Forest, and other worlds were not changed. No commit.

Accepted WebView goldens in `docs/v1/space-expedition-visual-layer/android/` were **not** overwritten. This folder is the native evidence.

Device: ADT-3 / h728, 1920×1080.

## 1. WebView components removed

Deleted from production:

- `SpacePatternBoard` (WebView + `challenge_active.html`)
- `SpaceHearthBoard` (WebView + `entry.html` / `journey.html` / `intro.html` / `help.html` / `success.html`)

`SpaceExpeditionActivity` no longer calls `loadUrl`, `evaluateJavascript`, or `android.webkit`. A single `setContentView` happens in `onCreate`.

Local HTML/CSS in `assets/space_expedition_v1/*.html` remains as an unused visual spec. Runtime decode is JPEG only (`SpaceRoutes.BACKGROUNDS`).

## 2. Native architecture

Static art + dynamic Android Views:

- `ReferenceStage` (1920×1080) + `clearExcept(world)` so Help/Wrong/Success do not rebuild the world view
- one `SpaceWorldView` for the whole Space activity
- JPEG backgrounds decoded `RGB_565`, `inScaled=false`
- Plus Jakarta Sans from `space_expedition_v1/fonts/PlusJakartaSans.ttf`
- invisible `SpaceTarget` hit rects for D-pad / OK
- HUD, orbs, path, Help overlay, Wrong toast, Success card drawn in Canvas

Challenge → Help → Challenge → Wrong stay on the same view (overlay / state flags). No HTML reload, no first-paint hole.

## 3. Visual parity per screen

Golden WebView vs native H728 (side-by-side in `comparisons/`):

| Screen | Golden | Native | Notes |
| --- | --- | --- | --- |
| Entry | `visual-layer/android/07_entry.png` | `android/07_entry.png` | Cockpit art + HUD + Continue / Map / Journal |
| Journey | `05_journey.png` | `android/05_journey.png` | Clean cosmic plate + native nodes/path |
| Intro | `04_intro.png` | `android/04_intro.png` | Empty engine bays, production copy |
| Challenge | `00_challenge_active_accepted.png` | `android/00_challenge.png` | 3 glass cards, authored 5/6/7, pairs, cyan selected |
| Help | `02_help.png` | `android/02_help.png` | Scaffold overlay on the same challenge view |
| Wrong | `03_wrong.png` | `android/03_wrong.png` | Soft amber toast, cards stay |
| Success | `01_success_install.png` | `android/01_success_install.png` | Accepted success art + native glow/ship overlay |
| Updated Journey | `06_updated.png` | `android/06_updated.png` | Clean plate + native labels; see limitations |

Native is not a simplified quiz: PATTERN still shows three glass cards, pair rows, HUD, and focus scale.

## 4. Background cleanup / localization

`journey_bg.jpg` already had no baked destination names.

`journey_updated_bg.jpg` had Earth / Moon Crater / Starlight Nebula / Солнечная гавань. Cleanup:

- original: `app/src/main/assets/space_expedition_v1/journey_updated_bg.original.jpg`
- clean + runtime copy: `journey_updated_clean.jpg` / `journey_updated_bg.jpg`
- SHA-256 and boxes: `logs/asset-provenance.json`

Hull lettering «Северное сияние» kept. Production node labels (Старт, Луна, …) are always native. No baked BACK in the plate.

## 5. Success consequence animation

Correct option still enters `SUCCESS` with `INSTALL` → `IGNITE` → `TRAVEL` over `SPACE_CONSEQUENCE_MS` (3200 ms), then Journey with `SETTLE`.

Native 2D overlay on the accepted success artwork:

- energy glow on INSTALL
- engine glow on IGNITE
- ship translation / trail on TRAVEL
- background scale/parallax on TRAVEL

Same `SpaceWorldView` (already-decoded `success_bg.jpg`) so Challenge → Success is not a dark first-paint.

H728 still: `android/01_success_install.png`. Later stills raced the auto-return (same as the WebView golden pass). Motion is in `recordings/space-expedition-native.mp4`.

## 6. PSS / Graphics before → after

WebView baseline from `docs/v1/space-expedition-visual-layer/logs/`. Native from this run (`logs/performance.json`, `logs/pss-*.txt`). Figures are KiB.

| Surface | PSS before | PSS after | Graphics before | Graphics after |
| --- | ---: | ---: | ---: | ---: |
| Entry | 361467 | 151128 | 174140 | 39908 |
| Journey | 336371 | 162854 | 144096 | 48304 |
| Challenge | 433718 | 170139 | 241008 | 52776 |
| Help | 400440 | 167803 | 206936 | 53040 |
| Success* | 350763 | 180171 | 152324 | 64040 |
| Updated | 416215 | 179423 | 217304 | 64040 |
| Intro | 357453 | 178784 | 164820 | 52620 |

\* Native success meminfo landed after auto-return to Journey (dumpsys duration). Order of magnitude is still ~180 MiB vs ~351 MiB.

All native Space samples are **under 220 MiB PSS**. Challenge Graphics 241 → 53 MiB. No `System.gc()`.

## 7. WebView = 0 for Space

Native `maps-challenge.txt` (and the other Space maps):

- **no** `space_expedition_v1/*.html`
- **no** `/product/app/webview/webview.apk`
- **no** `/data/data/ru.familytv/app_webview`

WebView golden maps had both `webview.apk` and `app_webview/...`.

Remaining: Android’s tiny `/system/lib64/libwebviewchromium_loader.so` (framework stub, also present without Space HTML). Space UI does not instantiate `WebView`.

AI=0 on every `FamilySpace` line. No `.gguf` / `.onnx`.

## 8. Tests / build / release

- `./gradlew :app:testDebugUnitTest` — `SpaceProductionTest`, `HomeProductionTest`, `ExploreRoutingTest`
- New regressions: no WebView in Space sources, JPEG RGB_565, single `setContentView`, native Entry controls, Help/Wrong stay on Challenge, INSTALL/IGNITE/TRAVEL then Journey
- Existing domain tests unchanged (PATTERN 5/6/7, Forest, AI=0, pause, back)
- `./gradlew :app:lintDebug` — pass
- `./gradlew :app:assembleDebug` — pass
- `./gradlew :app:compileReleaseKotlin :app:mergeReleaseAssets :app:processReleaseMainManifest` — pass

## 9. H728 flow

Cold Home → Space Entry → Journey → Challenge → Help → Wrong → Correct → Success (INSTALL still) → Updated Journey → Home.

Video: `recordings/space-expedition-native.mp4`

QA: `python3 tools/qa/space_expedition_native_h728.py`

## 10. Remaining limitations

- TRAVEL stills can race a long `dumpsys meminfo`; use the video plus `01_success_install.png` for consequence.
- Overlay ship on Success is a 2D glow/hull, not a 3D rig.
- SORT / maze / visual-search remain Canvas boards on the same native world (not the PATTERN glass-card layout).
- Unused HTML spec files still ship in assets; they are not loaded.
- System `libwebviewchromium_loader.so` remains mapped by Android; Space does not load Chromium or HTML.

## Updated Journey (final)

Journey and Updated Journey share `journey_bg.jpg`. Native node states carry the difference. Patched `journey_updated_bg.jpg` is archived, not decoded.

H728 still: `android/updated_journey_final.png`. Transition video: `recordings/success_to_journey.mp4`.
