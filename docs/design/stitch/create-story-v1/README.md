# FamilyTV Create / Story Builder V1 — approved Stitch reference

Canonical screens:

1. 01_create_home
2. 02_choose_hero
3. 03_choose_world
4. 04_choose_object
5. 05_story_choice
6. 06_consequence
7. 07_ending

## Geometry

The approved Android TV target is fixed 1920x1080 / 16:9.

Stitch screen.png exports were captured through a broken narrower viewport
and are 1280x1080.

Therefore:

- code.html is authoritative for full layout geometry;
- screen.png is authoritative for art direction, composition and styling;
- native Android implementation must use the full 1920x1080 composition;
- do not reproduce the cropped PNG dimensions.

## Product interaction source of truth

Story loop:

hero
→ world
→ physical story object
→ action choice
→ visible world consequence
→ finite ending
→ replay/save/new story

The same selected hero/world/object must remain visually continuous.

No correct/wrong.
No score.
No reward economy.
No mandatory generative AI.
No typed input.

## Native implementation adaptations

Do NOT reproduce incidental Stitch presentation copy literally when it
conflicts with FamilyTV child UX.

Avoid/remove items such as:

- "Любимец зрителей"
- "Хит вечера"
- fake/live microphone state
- "Голосовое управление" as persistent metadata
- "Скажи в пульт"
- persistent D-pad instruction footers
- "Нажми влево / вправо" instruction cards

Voice should remain architecture-ready without displaying fake listening state.

On Story Choice, native focus should belong to the physical door/keyhole
or forest path itself. Do not turn the choice back into large menu cards.

The design direction is approved.
Do not redesign it during native preview implementation.
