# Challenge Active — Stitch → Android (PATTERN_SEQUENCE only)

Domain / routing / Journey / mechanics were not changed.

## Stitch HTML breakdown (`04_challenge_active/code.html`)

Canvas: **1920×1080**, safe **88×72**.

### Visual hierarchy
1. Immersive cosmic photograph (full-bleed, scale 1.05) + top/bottom dim + 2px blur veil
2. HUD row: Pause pill · Lumi banner · mission fuel crystals
3. Three glass orb-cards (idle 380×460, focused 430×520 at scale 1.10)
4. Footer remote-guidance pill

### Visible objects
| Object | Static art vs interactive |
| --- | --- |
| Spaceship / stars / nebula photograph | static art (HTML `<img>`) |
| Pause pill + «⏯ ПДУ» | interactive (Pause) |
| Lumi sparkle avatar + banner | static chrome |
| Fuel crystals (2 full + 1 empty) + «Цель миссии» | static chrome |
| Idle orb cards «Орбита А/В», glass sphere, number, pair check | interactive (D-pad) |
| Focused card: cyan aura, «ВЫБРАНА», 118px number, even-pair tray, «OK · Запустить в двигатель» | interactive + focus language |
| Footer «◄ ►» / «OK Запустить в двигатель» | static chrome |

### Artwork
Remote illustration from `code.html` saved locally (no runtime HTTP):

`app/src/main/assets/space_expedition_v1/challenge_active_bg.jpg`  
Plus Jakarta Sans: `…/fonts/PlusJakartaSans.ttf`  
Provenance: `asset-provenance.json`

### Authored values in Stitch presentation
Production options **5 / 6 / 7** occupy the three Stitch orb cards (left / center / right). Pair diagrams are derived from those numbers (5 = two pairs + leftover, 6 = three pairs, 7 = three pairs + leftover). Stitch mock used 3 / 6 / 7; numbers are not hardcoded as quiz answers.

## Evidence

| | Path |
| --- | --- |
| Android H728 | `docs/v1/space-expedition-challenge-active/android.png` |
| Stitch 1920×1080 (`code.html` render) | `docs/v1/space-expedition-challenge-active/stitch-reference.png` |

Device: ADT-3 / h728, 1920×1080. Scene `repair_pattern`, mechanic `PATTERN_SEQUENCE`, focus `opt:6`. No further screens.

## Comparison

Android matches Stitch on composition (HUD / three glass orbs / footer), focus language (cyan scaled center card, «ВЫБРАНА», OK pill), typography (Plus Jakarta Sans), background photograph, pair-star scaffolding, and remote hint bar.

Intentional domain difference: left orb is **5**, not Stitch’s example **3**.
