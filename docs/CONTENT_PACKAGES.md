# Content Packages

Versioned installable content units for FamilyTV. Packages add VIDEO / GAME / INTERACTIVE
entries **without rebuilding the APK**.

## Package layout

```
package/
  package.json
  media/
  images/
  experiences/
```

## package.json schema (schemaVersion = 1)

| Field | Meaning |
|-------|---------|
| schemaVersion | Must be `1` |
| packageId | Stable id (`[a-z0-9][a-z0-9._-]{1,63}`) |
| packageVersion | Integer ≥ 1, monotonic |
| title | Display title |
| content[] | Catalog entries (same fields as catalog items; relative refs) |
| files[] | `{ path, sha256, size, mediaType? }` |

Rules:

- Paths are relative, no `..`, no absolute paths
- No executable extensions (apk/dex/so/sh/…)
- SHA-256 verified at import
- Content IDs must not conflict with bundled catalog or other active packages

Example: `docs/examples/apple-adventure.package.json`  
Demo ZIP: `content-packages/apple-adventure-v1.zip`

## Storage layout

```
FamilyTV/packages/<packageId>/<version>/   # installed payloads + index.json
FamilyTV/import-packages/                  # drop ZIP or directory here (admin)
```

Active version is metadata in SharedPreferences (`familytv_package_store`).
Home does **not** re-hash media on startup — trust install-time verification + index.

## Install flow

```
incoming (dir or ZIP)
  → staging
  → parse package.json
  → path / type / size / SHA checks
  → content-id conflict checks
  → atomic move to packages/<id>/<version>
  → set active version
  → ContentRepository.reload()
```

Failure deletes staging and leaves the previous active version untouched.
Same version cannot overwrite an already installed folder.
Rollback activates the highest installed version lower than current.

## Repository merge

`LocalContentRepository` = bundled baseline (+ optional legacy catalog import) **∪** active packages.

Conflict policy (at install): reject. Never silently override unrelated IDs.

## Admin

Debug long-press **FamilyTV** on Home → Content Admin:

- Import package ZIP / directory from `import-packages/`
- List versions, activate, rollback, remove non-active

## Authoring (Milestone 4.2)

Do not hand-write `files[].sha256`. Build from `lessons/`:

```bash
python3 tools/content/build.py lessons/apples
# → dist/apple-adventure-vN.zip
```

Optional content metadata (ageMin/ageMax, skills, topics, …) and SESSION items
are documented in `docs/CONTENT_AUTHORING.md`.

Demo packages in `dist/`:

- `apple-adventure-v1.zip` / `apple-adventure-v2.zip` (math + move)
- `animal-world-v1.zip` (world knowledge)
- `move-break-v1.zip` (movement)
- `morning-session-v1.zip` (curated session)

## Future remote CMS path

```
Authoring Tool → signed package → Remote CMS/index → device download
  → ContentPackageStore (existing)
```

SHA-256 proves integrity, not publisher authenticity.
Remote distribution must add cryptographic package/index signature verification
before activation.

Legacy sketch:

```
CMS → package index → download to staging
  → hash (/ future signature) validation
  → ContentPackageStore
  → atomic activation
  → ContentRepository
```

Digital signing is not implemented; the insertion point is after SHA verification
in `ContentPackageParser.verifyPayload` / store install.
