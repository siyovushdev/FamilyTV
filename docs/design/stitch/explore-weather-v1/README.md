# FamilyTV Explore / Weather V1 — approved Stitch reference

Canonical screens:

1. 01_explore_home
2. 02_weather_intro
3. 03_prediction
4. 04_experiment
5. 05_result
6. 06_ask_lumi
7. 07_build_test

## Important Stitch export limitation

The HTML files define the approved fixed Android TV canvas as 1920×1080.

Stitch's exported screen.png files were captured through a narrower
1280 px viewport and are therefore horizontally cropped.

Therefore:

- code.html is authoritative for canvas/layout geometry;
- screen.png is authoritative for illustration/art direction and visible styling;
- do NOT interpret screen.png dimensions as the Android TV target;
- native implementation and any reconstructed reference screenshots must use
  the full 1920×1080 composition.

Do not restore the discarded _1 Home/Intro variants.

The design direction is approved.
Do not redesign it during Android implementation.
