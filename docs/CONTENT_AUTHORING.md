# Content Authoring

FamilyTV lessons are authored as **source directories**, then built into versioned packages.
Android code is not required for new experiences.

## Workflow

```bash
# 1. Create a lesson folder
lessons/my-lesson/
  lesson.json          # package metadata + content entries (no hashes)
  experiences/*.json   # interactive definitions
  media/               # videos/audio
  images/              # covers
  sessions/*.json      # optional curated sessions

# 2. Validate
python3 tools/content/cli.py validate lessons/my-lesson

# 3. Build ZIP (hashes computed automatically)
python3 tools/content/build.py lessons/my-lesson
# → dist/my-lesson-v1.zip

# 4. Inspect / diff
python3 tools/content/cli.py inspect dist/my-lesson-v1.zip
python3 tools/content/cli.py diff dist/pkg-v1.zip dist/pkg-v2.zip
```

Authors never write SHA-256 by hand.

Host validation rules in `tools/content/schema.py` mirror device
`ContentPackageParser` (schema, path safety, forbidden extensions, sizes, SHA).

## lesson.json

```json
{
  "packageId": "animal-world",
  "packageVersion": 1,
  "title": "...",
  "content": [
    {
      "id": "pkg-animal-world",
      "type": "INTERACTIVE",
      "experienceRef": "experiences/animal-world.json",
      "homeSections": ["today", "learn"],
      "metadata": {
        "ageMin": 4,
        "ageMax": 9,
        "skills": ["WORLD_KNOWLEDGE"],
        "estimatedMinutes": 4,
        "contentPurpose": "learn"
      }
    }
  ]
}
```

## Quality principles

Prefer: short content → interaction → reflection → movement/rest.

Avoid: endless feed, streaks, engagement maximization, forever autoplay.

See `docs/LEARNING_EXPERIENCES.md`.

## Device import

Push ZIP to `Android/data/ru.familytv/files/FamilyTV/import-packages/`
and use Content Admin → **Import package ZIP**.
