# FamilyTV — Большая космическая экспедиция V1

Approved Stitch design reference.

Canonical screens:

1. 01_expedition_entry
2. 02_expedition_journey
3. 03_challenge_intro
4. 04_challenge_active
5. 05_help
6. 06_success
7. 07_updated_progress

## Product goal

The expedition must feel like ONE continuous space adventure:

RESUME / ENTER
→ SEE JOURNEY
→ ENTER CURRENT STAGE
→ UNDERSTAND THE CHALLENGE
→ ACT IN THE WORLD
→ GET VISUAL CONSEQUENCE
→ SEE JOURNEY PROGRESS UPDATE

Not:

tracker
→ quiz page
→ correct/wrong message
→ tracker

## Geometry

The real Stitch HTML canvas is fixed 1920×1080 / 16:9.

Exported screen.png files were captured through a broken viewport and are
1600×1350, therefore:

- code.html is authoritative for full geometry/layout;
- screen.png is authoritative for visual direction/styling;
- native Android must target full 1920×1080;
- never reproduce the exported PNG aspect ratio.

## Semantics

The existing production journey/content data is authoritative for:

- real journey id;
- actual stages/nodes;
- completed/current/locked state;
- challenge rules;
- progress/history/Journey semantics.

The Stitch labels/stage examples are visual references and must NOT replace
real production journey semantics when they differ.

## Challenge

The even-number challenge is the canonical visual reference for a space
challenge:

world problem
→ child chooses
→ world reacts
→ journey moves forward

Do not turn existing adventure mechanics into generic quiz screens.

## FamilyTV rules

- Android TV 1920×1080
- D-pad first
- non-reader-friendly
- visual consequence before explanation
- soft wrong feedback
- no score/XP/reward economy
- no fake voice/listening state
- no mandatory AI
- runtime assets local, no network dependency

The current guide character is a working FamilyTV guide.
Do not create additional architecture coupled specifically to its name/brand.

The design direction is approved.
Do not redesign it during implementation.
