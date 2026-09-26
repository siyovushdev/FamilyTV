# Lumi — Production Visual Specification

**Version:** 7.1  
**Status:** Production-ready brief for illustrator / image pipeline  
**Runtime:** `LumiView` + `LumiAssets` slots (canvas fallback until final art lands)

## Personality (unchanged)

Calm, curious family guide. Never a scold. Never a free chatbot.

## Silhouette (locked)

- Soft **teal orb** body with slight vertical teardrop (height ≈ 1.1 × width)
- Single **antenna** with glowing tip — primary recognizer from 3 m
- No legs required for UI size; optional soft “hover” shadow for scene size
- Outline: thick enough to read on dark Space and light Home

## Proportions

| Region | Ratio |
|--------|-------|
| Body diameter | 1.0 (unit) |
| Antenna length | 0.40 |
| Antenna tip | 0.14 |
| Eye spacing | 0.56 body |
| Eye diameter | 0.22–0.28 body (state-dependent) |
| Mouth width | 0.40–0.55 body |

## Face

- Oversized eyes with white specular highlight (upper-left)
- Simple mouth curves only — no teeth, no detailed nose
- Brows optional only for SURPRISED / THINKING (thin arcs)

## Emotion / state sheet (required)

| Slot id | Emotion | Eyes | Mouth | Body language |
|---------|---------|------|-------|---------------|
| `lumi_idle` | CALM | Normal | Soft line/smile | Still, soft glow |
| `lumi_listening` | LISTENING | Slightly larger | Soft smile | Gentle pulse |
| `lumi_speaking` | HAPPY | Normal | Open soft oval or smile | Antenna tip brighter |
| `lumi_thinking` | THINKING | Slightly smaller | Flat/soft | Antenna tip dim pulse |
| `lumi_helping` | HELPING | Warm | Encouraging smile | Lean / point cue |
| `lumi_curious` | CURIOUS | Bright | Soft smile | Lean forward |
| `lumi_happy` | HAPPY | Bright | Wide smile | Small bounce |
| `lumi_celebrating` | CELEBRATING | Large | Wide smile | Celebrate bounce |
| `lumi_encouraging` | ENCOURAGING | Soft | Warm smile | Open posture |
| `lumi_surprised` | SURPRISED | Large round | Round O | Quick lift |
| `lumi_rest` | REST | Closed arcs | Soft line | Low glow |

## Size variants

| Variant | Height | Use |
|---------|--------|-----|
| `ui` | 72–96 dp | Home chrome |
| `scene` | 160–220 dp | Adventure companion |
| `hero` | optional 280 dp | Intro splash only |

## Gestures (lightweight — not skeletal)

appear · idle · listen · speak · think · help/point · celebrate · gentle-wrong · transition

Max duration ~450 ms. No perpetual bounce on Home.

## Asset delivery

Prefer **WebP** or **PNG** with transparent background, square canvas, TV-pre-scaled:

- `lumi_ui_<slot>.webp` @ 192×192
- `lumi_scene_<slot>.webp` @ 384×384

Naming must match `LumiAssets` slots. Runtime never depends on canvas geometry once a drawable exists for the slot.

## Do / Don't

- Do: unique FamilyTV original character
- Don't: copy copyrighted mascots / franchise faces
- Don't: hyperstimulating sparkle spam
