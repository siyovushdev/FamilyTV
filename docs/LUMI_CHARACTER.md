# Lumi — FamilyTV guide character

Original character. Not based on any copyrighted IP.

## Personality

Curious, calm, encouraging companion. Speaks short, clear Russian. Never invents curriculum facts — only authored / constrained guide lines.

## Silhouette

Soft teal orb body with gentle teardrop proportion, single antenna with glowing tip. Reads as “friendly light guide” at 2–3 meters.

## Face

Oversized eyes with white highlights; simple mouth curves for emotion. No detailed teeth / nose clutter.

## Proportions

Body ≈ circle; antenna ≈ 0.4 body radius above. Face features in upper-middle third.

## Emotions / states

| State | Visual cue | Motion |
|-------|------------|--------|
| CALM | Soft smile, low glow | idle |
| HAPPY | Wide smile | celebrate bounce |
| CURIOUS | Soft smile, attentive eyes | listen pulse |
| THINKING | Smaller eyes | think pulse |
| ENCOURAGING | Warm smile | help lean |
| SURPRISED | Round mouth, large eyes | quick react |
| CELEBRATING | Wide smile + glow | celebrate |
| LISTENING | Attentive | listen |
| HELPING | Warm smile | point/help |
| REST | Closed eyes | idle |
| EXCITED | Alias of HAPPY/CELEBRATING (legacy JSON) | celebrate |

## UI sizes

- Chrome / Home: 96–128 dp
- Adventure companion: 160–220 dp

## Poses (lightweight)

appear · idle · listen · think · react-correct · react-wrong/gentle · point/show · celebrate · transition · help

Implemented in `LumiView` (canvas + short ValueAnimators) — no skeletal engine.
