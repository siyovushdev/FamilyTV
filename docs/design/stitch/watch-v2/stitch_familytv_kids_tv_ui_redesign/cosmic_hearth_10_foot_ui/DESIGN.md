---
name: Cosmic Hearth 10-Foot UI
colors:
  surface: '#0d1225'
  surface-dim: '#0d1225'
  surface-bright: '#33384d'
  surface-container-lowest: '#080d20'
  surface-container-low: '#161b2e'
  surface-container: '#1a1f32'
  surface-container-high: '#24293d'
  surface-container-highest: '#2f3448'
  on-surface: '#dde1fc'
  on-surface-variant: '#bdc8d1'
  inverse-surface: '#dde1fc'
  inverse-on-surface: '#2b2f44'
  outline: '#87929a'
  outline-variant: '#3e484f'
  surface-tint: '#7bd0ff'
  primary: '#8ed5ff'
  on-primary: '#00354a'
  primary-container: '#38bdf8'
  on-primary-container: '#004965'
  inverse-primary: '#00668a'
  secondary: '#ffb95f'
  on-secondary: '#472a00'
  secondary-container: '#ee9800'
  on-secondary-container: '#5b3800'
  tertiary: '#56e5a9'
  on-tertiary: '#003824'
  tertiary-container: '#30c88f'
  on-tertiary-container: '#004e34'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#c4e7ff'
  primary-fixed-dim: '#7bd0ff'
  on-primary-fixed: '#001e2c'
  on-primary-fixed-variant: '#004c69'
  secondary-fixed: '#ffddb8'
  secondary-fixed-dim: '#ffb95f'
  on-secondary-fixed: '#2a1700'
  on-secondary-fixed-variant: '#653e00'
  tertiary-fixed: '#6ffbbe'
  tertiary-fixed-dim: '#4edea3'
  on-tertiary-fixed: '#002113'
  on-tertiary-fixed-variant: '#005236'
  background: '#0d1225'
  on-background: '#dde1fc'
  surface-variant: '#2f3448'
typography:
  display-hero:
    fontFamily: Plus Jakarta Sans
    fontSize: 64px
    fontWeight: '800'
    lineHeight: 76px
  headline-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 48px
    fontWeight: '700'
    lineHeight: 58px
  headline-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 36px
    fontWeight: '700'
    lineHeight: 46px
  title-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 30px
    fontWeight: '600'
    lineHeight: 40px
  title-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 26px
    fontWeight: '600'
    lineHeight: 34px
  body-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 24px
    fontWeight: '500'
    lineHeight: 36px
  body-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 20px
    fontWeight: '400'
    lineHeight: 30px
  label-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 22px
    fontWeight: '700'
    lineHeight: 28px
  label-sm:
    fontFamily: Plus Jakarta Sans
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 24px
rounded:
  sm: 0.5rem
  DEFAULT: 1rem
  md: 1.5rem
  lg: 2rem
  xl: 3rem
  full: 9999px
spacing:
  gutter: 2rem
  margin: 5.5rem
  space-xs: 0.5rem
  space-sm: 1rem
  space-md: 1.5rem
  space-lg: 2.25rem
  space-xl: 3.5rem
---

## Brand & Style

This design system powers a premium, child-first, living-room educational media platform tailored exclusively for television interfaces (Android TV / Apple TV / 10-foot UI, 1920×1080 canvas, viewed from 2.5–3.5 meters). 

The emotional tone is cozy, wonder-filled, warm, and cinematic. It completely rejects dark patterns, addictive streaks, and sensory overload (flashing lights, chaotic micro-animations, or dense text walls). Instead, it fosters gentle curiosity, safe exploration, and family co-viewing. The central emotional anchor is Lumi — an expressive, robotic guide whose luminous reactions, glowing antenna, and gentle presence guide children who may not yet know how to read.

Visually, the style combines **Tactile Softness** with **Spatial Glassmorphism**:
- Soft, pillow-like volumetric surfaces with pill and stadium geometries that look touchable and friendly.
- Ambient celestial lighting where focused elements float and emit a warm, radiant halo instead of harsh drop shadows.
- Clear, unhurried visual hierarchy with ample breathing room so preschool and primary-age children can navigate effortlessly via remote control (D-pad) or voice.

## Colors

The color architecture is built specifically for large-format TV panels (OLED and LED) to prevent eye fatigue in dimly lit living rooms while ensuring rich contrast under ambient daylight.

- **Canvas & Backdrops:**
  - Base Deep Space: `#0D1120` (Midnight Magic) and `#12172A` (Twilight Slate).
  - Exploration Morning Mode (Light Ambient): Soft warm cream `#F7F8F5` with subtle twilight tint `#EBEFEF` for daytime viewing.
  - Surface Elevation Tiers: `#1A2238` (Deck Surface), `#242F4D` (Floating Card/Pod), and `#303E65` (Selected/Elevated Surface).
- **Primary Accent (Lumi Beam / Ultramarine Glow):** `#38BDF8` transitioning to electric sky `#0284C7`. Used for the active TV focus ring, interactive energy indicators, and Lumi's speech ripples.
- **Secondary Accent (Amber Sun / Discovery):** `#F59E0B` and soft warm gold `#FDE68A`. Used for story highlights, achievements, and warm call-to-actions.
- **Tertiary Accent (Emerald Biome / Nature):** `#10B981` and `#34D399`. Used for science, nature quests, and success confirmation states.
- **Creative Coral:** `#FB7185` and `#F43F5E`. Dedicated to arts, music, and maker-mode modules.
- **Parental Safeguard:** Deep neutral slate `#475569` with muted lilac `#A5B4FC` to clearly differentiate adult settings from playful child spaces.

## Typography

Typography adheres strictly to the 10-foot viewing rules. Every character must be instantly decipherable by a 5-year-old child and elderly grandparents from 3 meters away.

- **Typeface Selection:** **Plus Jakarta Sans** is selected for its wide counters, friendly rounded geometric apertures, clear distinction between similar glyphs (I, l, 1), and gentle, humanist cadence.
- **Scale Rules for TV:**
  - Absolute minimum text size on TV is **20px** (used only for secondary metadata like duration or episode counts).
  - Primary body text is **24px–26px**.
  - Section titles and world markers are **36px–48px**.
  - Hero display banners reach **64px**.
- **Child Readability:** Lines are limited to a maximum of 40–50 characters. Generous line height (130–150%) prevents crowding and keeps text tracking effortless.

## Layout & Spacing

The layout is built for 1920×1080 16:9 widescreen television displays with fixed overscan safety boundaries:

- **TV Safe Margins:** Strict screen safe padding is set to **88px horizontal and 72px vertical** (`margin: 5.5rem`). No actionable controls, text elements, or status indicators may sit outside this safe zone to avoid panel clipping on domestic TVs.
- **Navigation Axis:** Primary navigation relies on horizontal carousels with fixed-edge focus snapping. When a child navigates using the remote's D-pad (Left/Right/Up/Down), the focused item remains in the central third of the screen while the lane scrolls smoothly behind it.
- **Grid Structure:** A 6-pod modular row layout for content worlds (cards span 2 or 3 columns). Gaps between cards are consistently 32px (`space-lg`) to give ample room for focus ring expansion without overlapping adjacent artwork.

## Elevation & Depth

Visual hierarchy on TV cannot rely on faint mouse hover states. Instead, depth is conveyed through **Focus-Driven Spatial Luminescence**:

1. **Resting Depth (Base Level):** 
   Unfocused cards sit flush on the background or slightly lifted via semi-translucent dark slate backing (`rgba(255, 255, 255, 0.06)` or `#1E2742`), with an inner edge border of 1.5px at 15% opacity.
2. **Focused State (Elevated 1.10x):**
   When navigated via D-pad, the element instantly pops forward:
   - Scale transform: `scale(1.10)`.
   - Continuous spring timing: `cubic-bezier(0.34, 1.56, 0.64, 1)`.
   - Surrounding Focus Ring: 4px solid `#38BDF8` with an outer ambient glow (`0 0 32px rgba(56, 189, 248, 0.45)`).
   - Z-index elevated above all neighboring tiles.
3. **Voice Activation State:**
   When the microphone button is pressed, the screen gently dims with a deep sapphire vignette (`rgba(10, 14, 26, 0.7)`), and Lumi's companion slot expands with an organic radial audio pulse.

## Shapes

The interface embraces organic, friendly softness (Roundedness Level 3 - Pill / Pebble). 

- Standard interactive buttons, voice pill triggers, and parent locks use full stadium pills (`border-radius: 9999px`).
- World portals and mission cards feature heavy, welcoming curvature (`border-radius: 2rem / 32px`), giving them the appearance of soft tactile cushions or game cartridges.
- Modal popups and Lumi dialog balloons use asymmetrical rounded corners (e.g., rounded 36px on three sides and 12px near Lumi's antenna anchor) to emphasize storybook character speech.

## Components

### 1. D-Pad TV Focus Ring & Interactive Cards
- **Dimensions:** Standard World Card is 380px × 260px; Large Hero Feature is 820px × 340px.
- **Resting:** Rounded 32px, subtle 1px border (`rgba(255, 255, 255, 0.1)`), vibrant world artwork with bold pictorial icons (animals, planets, paints) to allow pre-readers to choose visually.
- **Focused:** Expands smoothly to 1.10x. Encircled by a 4px high-contrast cyan glow ring (`#38BDF8`) with an inner 2px white highlight ring to guarantee high contrast regardless of the background imagery.

### 2. Lumi Companion Slot & Voice Badge
- Fixed at the top-right header safe zone.
- Displays Lumi's animated face (friendly eye blinks, curious tilt).
- Adjacent Voice Capsule (`border-radius: 9999px`, height 56px, horizontal padding 28px) displaying a clear microphone icon and the Russian prompt: *"Нажми и скажи"* (Press and speak). 
- In active listening mode, concentric wave rings expand outward using the amber and cyan palette.

### 3. TV Action Buttons (Large Touchless Hit Areas)
- **Minimum TV Height:** 64px for standard buttons; 80px for primary "Начать" (Start) adventure triggers.
- **Pill Shape:** Fully rounded edges, filled with bright crisp white (`#FFFFFF`) with deep navy bold typography (`#0F172A`) for maximum contrast against deep space backdrops.
- **Focused Button:** Inverts or gains an energetic outer ring with subtle spring bounce on remote press.

### 4. Parent Lock & Family Corner
- Positioned in top-left or top-right opposite to child controls.
- Visual icon: Soft shielded padlock with text "Родителям".
- Interaction safeguard: Requires a hold-down remote action (e.g., "Hold OK for 3 seconds") or a simple spoken math gate to prevent unintended access by young children.

### 5. Content Categorization Chips & Badges
- Used above world titles (e.g., "Путешествие", "Игра", "Приключение").
- Monospaced or bold uppercase small capsules with soft translucent fills (`rgba(56, 189, 248, 0.15)`) and contrasting pastel text to categorize learning biomes without visual chaos.