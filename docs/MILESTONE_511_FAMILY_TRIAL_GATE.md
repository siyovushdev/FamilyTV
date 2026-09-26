# Milestone 5.1.1 — Family Trial Gate

Device: ADT-3 / H728 (`192.168.68.109:5555`), 1920×1080.

## Goal

Close remaining blockers so FamilyTV Beta can be handed to a family without ADB or a developer at the TV.

## Fixes in this gate

1. **Mic permission UX** — `MicPermissionGate` + TV explain screen («Нужен микрофон» / «Микрофон недоступен»). Math/Color/Interactive voice do **not** initialize `VoiceRuntime` until permission is granted. No Home-time mic request. System dialog: parent chooses **«При использовании приложения»** (not one-time).
2. **Parent Mode BACK** — focused `EditText` swallowed BACK (clear-focus only). Fixed with `dispatchKeyEvent` leave-to-Home so one remote BACK returns to Home.
3. **Ultra-short video progress** — clips with duration **&lt; 8s** are non-resumable / marked completed (`WatchProgressStore.NON_RESUMABLE_MAX_MS`). Documented in `docs/FAMILYTV_BETA.md`. Normal-length resume unchanged.
4. **Screen-time Home refresh** — reminder label ticks every 30s while Home is resumed.
5. **BACK stack hygiene** — Interactive/Session/Games prefer `finish()` on BACK so Home stays the stable root without extra CLEAR_TOP churn; «Домой» buttons still clear to Home where needed.
6. **Player progress** — header «Назад» saves progress; last-known Exo position used when current is 0.

## Final gate PASS/FAIL

| Gate | Result |
|------|--------|
| Physical / keyevent BACK navigation | **PASS** |
| Activity cleanup (task Hist) | **PASS** — Home `sz=1` after BACK flows |
| Mic permission without adb | **PASS** — explain → dialog → `granted=true` USER_SET |
| 30-minute stability | **PASS** — ~31 min; Native/Graphics flat after AI warm; no FATAL/ANR/WindowLeaked |
| Full Today session | **PASS** — `pkg-today-together` opens and advances; calm ending path verified |
| Screen-time soft warning | **PASS** — «Осталось 1 мин» via Parent UI; content not killed; no stale «перерыв» after cold stop |
| Offline restart | **PASS** |
| Content smoke | **PASS 16/16** |
| Fresh Home AI=0 | **PASS** |
| Memory before/after 30 min | **PASS** (see below) |

### Fresh Home (force-stop, AI not loaded)

| Metric | Value |
|--------|-------|
| TOTAL PSS | **107755–108387 KB** |
| Native Heap | ~15456 KB |
| Graphics | ~29792 KB |
| Activities / ViewRootImpl / Views | **1 / 1 / 140** |
| AI log hits | **none** |

### Cold Home (5 runs)

2477, 2401, 2384, 2391, 2390 ms — **median 2391 ms** (acceptable for trial).

### 30-minute run (keyevent BACK after each open)

| | BEFORE (fresh) | AFTER (~31 min, AI warmed) |
|--|--|--|
| TOTAL PSS | 108387 KB | ~1076–1080 MB |
| Native Heap | 15456 KB | ~567–568 MB (**flat** after first Math/VoiceRuntime load) |
| Graphics | 29792 KB | ~30–31 MB (**flat**) |
| Activities | 1 | 3–4 typical (task Hist remains Home `sz=1`) |
| ViewRootImpl | 1 | 3–4 |
| FATAL / ANR / WindowLeaked | — | **none** |

PSS jump after first voice game is the expected on-device AI model footprint, not a progressive leak. Fresh Home without opening Math/Color remains ~108 MB / AI=0.

### Content smoke (16/16)

| Item | Result |
|------|--------|
| video-sky, video-forest, video-warm, move-stretch | OK |
| interactive-count-apples | OK |
| pkg-apple-adventure, pkg-animal-world, pkg-colors-shapes, pkg-space-world, pkg-memory-focus, pkg-move-break, pkg-short-story | OK |
| pkg-today-together, pkg-morning-session | OK |
| game-math, game-color | OK (after in-app mic grant) |

## Tests / build

- `:app:testDebugUnitTest` PASS (`MicPermissionGateTest`, ultra-short progress)
- `:app:assembleDebug` PASS
- `:app:lintDebug` PASS

## Screenshots

`docs/images/family-trial/` — Home, Parent PIN, mic explain, system mic dialog, screen-time warning, session, interactive/move, details, activity history.

## Remaining notes (non-blocking)

1. Cold start ~2.4s vs historical 1.8–2.0 — accepted; no optimize without profile.
2. No Russian system TTS on this image — text / package-audio fallback.
3. Ultra-short demos never enter Continue Watching by design; use ≥8s media to demo CW.
4. After voice games, process retains ~0.5 GB native for models until process death — expected.
5. Parent BACK may briefly show Activities=2 in meminfo while the finished Activity is collected; task Hist is Home-only.

## Primary criterion

**PASS.** A clean H728 can run core Beta flows without ADB grants, Android Studio, or a developer at the TV — parent grants microphone once via the on-TV dialog («При использовании приложения»).
