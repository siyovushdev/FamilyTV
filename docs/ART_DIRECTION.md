# FamilyTV Art Direction

**Status:** Milestone 7.0 product baseline  
**Audience:** children ~4–10 and adults nearby · TV at 2–3 m

## Mood

FamilyTV should feel **friendly, warm, curious, modern, calm, and high-quality**.

It is a living shared world — not a dashboard, not a streaming grid, not a Material demo.

| Do | Don't |
|----|-------|
| Soft depth, layered scenes | Flat grey Android chrome |
| Clear silhouettes at 3 m | Tiny icon clutter |
| Warm light, gentle contrast | Casino neon / hyperstimulation |
| Coherent world identities | Unrelated random skins |
| Calm motion | Looping distraction |

## Forms & depth

- Prefer **rounded organic shapes** with soft corners (TV cards ~20–28 dp radius).
- Depth via **layered gradients + silhouettes**, not heavy drop shadows.
- Scenes read as **foreground / mid / far** planes (e.g. ship → hangar → stars).
- Avoid hard 1 px system rectangles for focus; use **glowing rim + scale**.

## Composition (TV)

- Safe overscan margins (~5–6% edges).
- One primary focus region per screen; supporting text secondary.
- Hero / mission area dominates Home first viewport.
- World portals are illustrated destinations, not list rows.

## Typography

- Short phrases for AUDIO_FIRST (large, 1–2 lines).
- Medium weight sans for titles; avoid thin decorative fonts at distance.
- Text never the only path to understanding; always paired with visual + speech when AUDIO_FIRST.
- TEXT_RICH may use longer support copy; still scannable.

## Cards & focus language

- **Portal cards:** tinted by world, large title, one motif (icon/illustration).
- Focus: teal/warm rim stroke + slight scale (1.04), never harsh red outline.
- Selected mechanic objects: fill shift + soft highlight, not checkbox chrome.

## Illustration style

- Original, geometric-friendly characters and props.
- Limited palette per world; shared FamilyTV neutrals + world accent.
- No copyrighted character likenesses.
- Prefer vector / small PNG layers; TV-pre-scaled assets.

## Scene layering

1. Atmosphere gradient (world identity)
2. Mid props (ship, trees, panels)
3. Interactive objects
4. Lumi (guide layer)
5. Short text support

## Character scale

- **UI Lumi:** ~96–128 dp (Home / chrome)
- **Scene Lumi:** ~160–220 dp companion
- Face features oversized for 3 m readability

## Icon language

Simple filled shapes with one accent. Worlds:

| World | Motif |
|-------|--------|
| Исследуй | telescope / leaf / planet |
| Решай | puzzle piece / number nodes |
| Создавай | brush / block |
| Истории | open book / soft moon |
| Двигайся | footprint / stretch arc |

## Motion principles

- Short (180–450 ms), ease-out.
- Celebrate briefly; then still.
- No perpetual bounce loops on Home.
- Correct = world changes + Lumi react; wrong = gentle, stable world.

## Color

Shared neutrals: warm cream `#FFF9EF`, ink `#263748`, teal focus `#197363`.

World accents (related family):

- Explore: deep teal → indigo night / forest greens
- Solve: soft lavender → cool blue
- Create: peach / coral warmth
- Stories: dusk violet / parchment
- Move: energetic mint / sunrise amber

Age suitability: high contrast for focus, but never alarming red “fail” punishment.

## Quality bar

Home 3.0 + Lumi + Space + Forest define the bar. Other lessons may stay simpler until they receive a visual pass.
