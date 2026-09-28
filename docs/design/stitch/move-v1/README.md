# FamilyTV Move V1 — approved Stitch reference

Flagship experience:

Разбудим звёзды

Canonical screens:

1. 01_move_home
2. 02_ready
3. 03_reach
4. 04_comets
5. 05_freeze
6. 06_balance
7. 07_ending

## Product loop

SEE MOVEMENT
→ MOVE WITH LUMI
→ WORLD CHANGES
→ NEXT MOVEMENT
→ CALM FINITE ENDING

## V1 constraints

- Android TV 1920x1080 / 16:9
- D-pad + narration
- no touch dependency
- no camera required
- no body/movement detection
- no fake sensing
- no score / XP / repetitions / calories
- no reward economy
- no child profile required
- works for several children together
- remote should not be required between every movement beat
- session is finite

## Stitch export limitation

Some exported screen.png files do not contain the full remote/background
illustration even though the approved code.html references it.

Therefore:

- code.html is authoritative for full layout geometry and referenced artwork;
- screen.png is a visual/style reference only;
- native implementation must use a full local 1920x1080 composition;
- do not reproduce black/missing-art regions from broken Stitch exports;
- all runtime art must be packaged locally; no runtime network dependency.

## Lumi

Use one canonical Lumi identity.

Only pose changes between movement beats:

- idle
- arms up / stretch
- side step
- move
- freeze
- arms out / balance
- calm breathing
- calm ending

Do not change Lumi into a different robot between screens.

## Truthfulness

Never claim that FamilyTV detected the child's movement.

Do not display:

- movement detected
- correct pose
- room safety percentage
- active camera/body tracking
- points for movement

Progress is deterministic/story-based.

The design direction is approved.
Do not redesign it during native preview implementation.
