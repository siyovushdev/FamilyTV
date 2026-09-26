# Content design guide (Beta)

FamilyTV content is **data/packages**, not dedicated Activities.

## Philosophy

Useful finite experiences. Prefer interaction over long passive stretches. Calm feedback. Accessible to children who do not read well.

## Experience rhythm (recommended)

short education → question → more content → small game → movement → story/finale

Sessions should end. Do not chain endless autoplay.

## Interaction modes

Use Engine v3 steps: `message`, `video`, `choice`, `true_false`, `move`, `question` (voice), optional `game`.

Not every item needs voice. Always provide a non-voice path when mic is unavailable.

## Narration / accessibility

Optional fields:

- `promptAudio` — package audio for prompts
- `feedback.correctAudio` / `wrongAudio` / `unknownAudio` (or `feedbackAudio`)

Runtime priority: package audio → offline TTS if available → text-only UI.

Keep TV text short (soft warn ~90 characters in `quality` checks).

## Metadata

Prefer:

- `ageMin` / `ageMax`
- `estimatedMinutes`
- `topics`, `skills`, `interactionModes`
- `contentPurpose` (`learn` / `play` / `story` / `move` / `session`)
- `homeSections` for Home placement

Household age filter in Parent Mode is a **content filter**, not a profile.

## Feedback tone

- Correct: positive, calm («Молодец!»)
- Wrong: encourage retry, no shame
- Unknown voice: «Не расслышал. Попробуй ещё раз.»
- Never: score penalties, streaks, loot

## Quality linter

```bash
python3 tools/content/cli.py quality lessons/animals
```

Warnings are authoring safety checks (long passive runs, missing minutes/age, retry loops without hints, TV text length). They do **not** prove educational quality.

## Packaging

```bash
python3 tools/content/cli.py validate lessons/colors-shapes
python3 tools/content/cli.py build lessons/colors-shapes -o dist
```

See [CONTENT_AUTHORING.md](CONTENT_AUTHORING.md) and [CONTENT_PACKAGES.md](CONTENT_PACKAGES.md).
