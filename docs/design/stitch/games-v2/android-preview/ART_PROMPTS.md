# Games V2 runtime art

Mode: built-in imagegen, two precise edits. No CLI/API fallback. Edit targets are the illustrations linked by the approved Games V2 `code.html` files; no other visual references were used. The original `screen.png`, `code.html`, and `DESIGN.md` remain untouched.

The static backgrounds omit the playable objects. Apples, baskets, paint cans, the rocket, paint changes, counting highlights and flight are native Canvas objects. Background exports are <=1280×720 WebP, quality 92, in `app/src/debug/assets/stitch_games/`. The adjacent `provenance.json` records original source URLs and SHA256 hashes. Generated originals remain under the imagegen output directory; packaged copies are self-contained and work offline.

## Orchard

Edit target: illustration linked by `familytv_2._math_game/code.html` (also used by Games Home).

Output: `app/src/debug/assets/stitch_games/orchard_background.webp`

Exact prompt:

> Use case: precise-object-edit. Asset type: interactive Android TV children's game background, landscape 16:9. Edit the attached approved FamilyTV Games V2 orchard illustration. Preserve this exact friendly cartoon storybook art direction, evening orchard, moon, warm glowing star lanterns, curved trees, flowers, wooden table, smiling cyan robot Lumi, composition and camera. Remove ONLY the two baskets and every apple on the table; leave the tabletop completely empty and clean so runtime animated baskets and apples can be placed there. Remove apples from distant trees as well, retaining leaves, so only runtime apples will be counted. Keep table in the same lower-center position and robot at the right. No UI, no text, no numbers, no new objects, no photo/realistic rendering, no branding. Full-bleed 16:9 landscape image, 1536 by 864 if possible. This is an asset derived solely from the supplied approved illustration, not a new design.

## Workshop

Edit target: illustration linked by `familytv_6._color_game/code.html`.

Output: `app/src/debug/assets/stitch_games/workshop_background.webp`

Exact prompt:

> Use case: precise-object-edit. Asset type: interactive Android TV children's game background, landscape 16:9. Edit this approved FamilyTV Games V2 cartoon storybook space workshop illustration. Preserve exact friendly hand-painted cartoon style, navy starry sky, smiling planets, crescent moon, flowers, curved shapes, warm wooden launch platform and cheerful cyan robot Lumi at the right. Remove ONLY the rocket, its glowing outlined right wing and the flame/stand immediately below its body; leave the launch platform and its supporting architecture. Remove the three paint cans and brushes from the foreground round wooden table, leaving that tabletop empty for runtime painting props. Keep original composition and camera, leave a clean open region for a runtime rocket centered near 58% of image width, above the launch platform. Do not put stars or substitute objects into the removed rocket region. No UI, no letters, no numbers, no photo realism, no new art direction, no branding. Full-bleed 16:9 landscape 1536 by 864 if possible. Derived solely from this supplied approved illustration.
