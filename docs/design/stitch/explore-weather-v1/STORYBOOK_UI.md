---
name: Cosmic Storybook 10-Foot UI
colors:
  surface: '#0e1223'
  surface-dim: '#0e1223'
  surface-bright: '#34384b'
  surface-container-lowest: '#090d1e'
  surface-container-low: '#171b2c'
  surface-container: '#1b1f30'
  surface-container-high: '#25293b'
  surface-container-highest: '#303446'
  on-surface: '#dee1f9'
  on-surface-variant: '#bacac8'
  inverse-surface: '#dee1f9'
  inverse-on-surface: '#2c2f42'
  outline: '#859492'
  outline-variant: '#3c4948'
  surface-tint: '#3cdcd4'
  primary: '#5ff6ee'
  on-primary: '#003735'
  primary-container: '#38d9d2'
  on-primary-container: '#005b58'
  inverse-primary: '#006a66'
  secondary: '#ffb951'
  on-secondary: '#452b00'
  secondary-container: '#c2841a'
  on-secondary-container: '#3c2500'
  tertiary: '#ffd6db'
  on-tertiary: '#670025'
  tertiary-container: '#ffaeba'
  on-tertiary-container: '#9a1d42'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#62f8f1'
  primary-fixed-dim: '#3cdcd4'
  on-primary-fixed: '#00201e'
  on-primary-fixed-variant: '#00504d'
  secondary-fixed: '#ffddb3'
  secondary-fixed-dim: '#ffb951'
  on-secondary-fixed: '#291800'
  on-secondary-fixed-variant: '#633f00'
  tertiary-fixed: '#ffd9dd'
  tertiary-fixed-dim: '#ffb2bd'
  on-tertiary-fixed: '#400014'
  on-tertiary-fixed-variant: '#8c1038'
  background: '#0e1223'
  on-background: '#dee1f9'
  surface-variant: '#303446'
typography:
  display-tv:
    fontFamily: Quicksand
    fontSize: 56px
    fontWeight: '700'
    lineHeight: 68px
    letterSpacing: -0.01em
  headline-xl:
    fontFamily: Quicksand
    fontSize: 44px
    fontWeight: '700'
    lineHeight: 54px
  headline-lg:
    fontFamily: Quicksand
    fontSize: 36px
    fontWeight: '700'
    lineHeight: 46px
  headline-md:
    fontFamily: Quicksand
    fontSize: 28px
    fontWeight: '600'
    lineHeight: 38px
  body-xl:
    fontFamily: Quicksand
    fontSize: 24px
    fontWeight: '500'
    lineHeight: 34px
  body-lg:
    fontFamily: Nunito Sans
    fontSize: 20px
    fontWeight: '500'
    lineHeight: 30px
  body-md:
    fontFamily: Nunito Sans
    fontSize: 18px
    fontWeight: '400'
    lineHeight: 26px
  label-tv:
    fontFamily: Nunito Sans
    fontSize: 18px
    fontWeight: '700'
    lineHeight: 24px
    letterSpacing: 0.04em
  badge-micro:
    fontFamily: Nunito Sans
    fontSize: 15px
    fontWeight: '800'
    lineHeight: 20px
    letterSpacing: 0.06em
rounded:
  sm: 0.5rem
  DEFAULT: 1rem
  md: 1.5rem
  lg: 2rem
  xl: 3rem
  full: 9999px
spacing:
  gutter: 2rem
  margin: 5rem
  space-xs: 0.5rem
  space-sm: 1rem
  space-md: 1.5rem
  space-lg: 2.25rem
  space-xl: 3.5rem
---

## Brand & Style

This design system establishes a gentle, wonder-inspiring, 10-foot living room experience built purposefully for children aged 4 to 8 on Android TV (1920×1080 landscape). Unlike overstimulating, hyper-saturated entertainment apps, this system prioritizes emotional regulation, cognitive calm, and intuitive pre-reader spatial autonomy from a viewing distance of 2.5 to 3.5 meters.

The visual style blends **tactile organic warmth** with **cosmic storybook glassmorphism**:
- **Tactile Softness**: Deep pillowy cards, soft cloud geometry, and friendly rounded radii that evoke physical storybook tokens and wooden play pieces.
- **Enchanted Atmosphere**: Deep midnight navy canvases punctuated by starlight illumination, aurora gradients, and warm amber glows.
- **Pre-Reader Wayfinding**: High-salience visual iconography, persistent category micro-badges, and the friendly presence of Lumi—a living, glowing guide droplet that breathes and anchors interactive sequences.
- **Physical Remote Ergonomics**: Designed exclusively for 5-way D-pad (Up/Down/Left/Right/Center OK) and voice navigation. Every interactive surface features an unmissable luminous focus state with a springy physical bounce.

## Colors

The palette balances deep restful contrast for television panels with cozy, luminous accents that feel enchanted rather than blinding:

- **Primary (`#38D9D2` — Lumi Cyan)**: The signature life-force color. Used for active D-pad focus halos, Lumi's bioluminescent glow, primary confirmation pulses, and exploratory highlights.
- **Secondary (`#FFB84D` — Starlight Amber)**: Warm golden energy used for achievements, favorite pins, "Play" modes, and cozy campfire moments.
- **Tertiary (`#FF6B8B` — Coral Rose)**: Friendly emotional accent used for creative activities, celebration alerts, and lively category distinction.
- **Neutral Surface Hierarchy**:
  - `canvas-dark`: `#131728` — Deep midnight navy base that eliminates eye strain in dim living rooms.
  - `surface-card-warm`: `#FFF9F0` — Creamy storybook cloud surface used for high-contrast day mode or daylight hero carousels.
  - `surface-elevated`: `#1E2540` — Rich midnight blue container for resting TV tiles.
  - `surface-focus`: `#2B355B` — Tinted elevated tile state prior to halo projection.
- **Pre-Reader Category Semantics**:
  - **Watch**: `#4D9FFF` (Sky Cinema Blue)
  - **Play**: `#FFB84D` (Orb Amber)
  - **Explore**: `#38D9D2` (Cosmic Cyan)
  - **Create**: `#FF6B8B` (Spark Coral)
  - **Move**: `#52E396` (Spring Mint)

## Typography

Typography is calibrated strictly for the **10-foot television rule**:
- **Legibility Floor**: No text is ever rendered below `18px` (label-tv) or `15px` (all-caps badge codes). At 3 meters, standard mobile or desktop sizing becomes illegible; our baseline ensures children and parents read effortlessly without squinting.
- **Font Character**: **Quicksand** brings rounded, open apertures with friendly geometric terminals, echoing storybook picture titles. **Nunito Sans** handles body clarity and metadata with sturdy vertical stems that render crisply on standard 1080p LED/OLED arrays.
- **Spacious Rhythm**: Line heights are set between 125% and 145% to prevent crowding, supporting children who are in the early stages of letter recognition.

## Layout & Spacing

Android TV physical screens require strict adherence to **Overscan TV Safe Areas** and predictable directional navigation paths:

- **Overscan Margins**: Fixed canvas margin of `5rem` (80px on a 1920×1080 resolution) on all sides. Interactive content, focus states, and mascot positions never encroach into this zone to prevent bezel clipping on legacy televisions.
- **Grid Architecture**: Horizontal rail layout. Content is organized into semantic vertical rows (e.g., "Continue Adventure", "Play Games", "Explore Worlds") that contain horizontally scrolling pill/card carousels.
- **Focus Padding Buffer**: A minimum `2rem` (32px) gutter between carousel items accommodates the physical scale expansion (`1.08x`) of focused cards without overlapping neighboring elements.
- **Remote Traversal**: Linear and strictly orthogonal (horizontal row traversal; vertical rail jumping). Diagonal navigation vectors are prohibited.

## Elevation & Depth

Visual depth is achieved through tactile organic layering and light emission rather than harsh drop shadows:

- **Resting Layer (Level 0)**: Background space canvas (`#131728`) with slow subtle cosmic ambient gradients.
- **Container Layer (Level 1)**: Inactive cards feature soft opaque backgrounds (`#1E2540` or `#FFF9F0` story cream) with a subtle inset tone border (`rgba(255, 255, 255, 0.08)`).
- **Focused State (Level 3 - Luminous Surge)**:
  - **Scale**: The card scales to `1.08x` using a physical spring curve (`cubic-bezier(0.34, 1.56, 0.64, 1)`).
  - **Outer Glow**: A double-ring neon aura with a 0px offset, 24px blur, and 6px spread rendered in Lumi Cyan (`rgba(56, 217, 210, 0.6)`) or Starlight Gold (`rgba(255, 184, 77, 0.6)`).
  - **Border**: A 4px solid perimeter frame matching the accent color.
- **Lumi Character Depth**: Lumi floats at Level 4, anchored with a dynamic directional spotlight that moves toward the focused item.

## Shapes

The shape system adopts a **full pill-shaped (`roundedness: 3`)** and organic soft aesthetic:
- **Hero Containers & Cards**: Generous 32px to 40px rounded corners (`rounded-xl` to full pills) that feel safe, touchable, and soft.
- **Pill Buttons & Badges**: 100% capsule shapes for D-pad action buttons (`OK`, `Дальше`, `Начать`) and remote helper tags.
- **Character Housing**: Lumi rests within a circular aura or soft teardrop bubble, echoing the gentle curvature seen across all interactive surfaces.
- **Strictly No Sharp Corners**: Sharp 90-degree corners are eliminated across the system to maintain an inviting, child-centric feel.

## Components

### 1. TV Focusable Content Card (The Adventure Tile)
- **Resting**: 360px wide × 240px tall (or 320px × 380px for portrait activities). Background in warm cloud cream (`#FFF9F0`) or cosmic indigo (`#1E2540`). Rounded 32px corners. Features an upper activity icon, bold title (`headline-md`), and friendly subline (`body-md`).
- **Focused**: Scales to `1.08x`. Wrapped in a 4px high-contrast cyan or emerald border with a 30px soft ambient bloom. The title text increases in weight and brightness.
- **Pre-Reader Badge**: Top-left corner carries a circular 48px icon badge (e.g., Star Lens for Watch, Game Orb for Play, Telescope for Explore).

### 2. D-Pad Action Button
- **Structure**: Pill-shaped container, minimum height 64px, minimum width 160px.
- **Resting**: High-contrast solid white (`#FFFFFF`) with dark indigo text (`#131728`), or translucent starlight pill (`rgba(255, 255, 255, 0.15)`).
- **Focused**: 1.1x scale, bright Lumi Cyan halo, deep drop shadow, and a pulsing white highlight interior.
- **Remote Hint**: Accompanying pill badge displaying the explicit physical remote input required (e.g., visual ring icon representing center "OK").

### 3. Remote Control HUD & Assistant Hints
- **Parent Gate & Voice Pill**: Positioned safely in the top-right header area. Pill height 56px with clear iconography (Microphone for Voice Search, Lock/Family icon for Parent Zone).
- **Navigation Breadcrumbs**: Top-left back pill (`‹ Домой`) providing instant spatial orientation with a dedicated "Back" remote glyph.
- **D-Pad Direction Indicator**: Bottom-right contextual footer showing active available remote axes (Left/Right arrows for carousel exploration, Center ring for activation).

### 4. Lumi Mascot Guide (Interactive Droplet)
- **Anatomy**: 80px × 80px circular companion with expressive rounded eyes, glowing cyan body (`#38D9D2`), and soft breathing halo.
- **Behavior**: Floats adjacent to the current focused item or anchors the narrative dialogue box.
- **Speech Bubble**: Attached rounded container (`rounded-xl`, 28px) displaying conversational audio transcriptions in `headline-md` for shared parent-child reading.

### 5. Activity Category Selector (Top Filter Rail)
- **Resting**: Capsule pills with 20px padding horizontal, 12px vertical. Transparent background with a 2px low-contrast boundary.
- **Active / Focused**: Filled with vibrant category color (Mint, Amber, Coral, Cyan), bold label, and accompanied by micro-haptic TV sound cue.