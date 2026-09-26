# Milestone — Signed LAN Content Distribution (H728)

Device: ADT-3 / H728. Publisher: `192.168.68.101:8080` via `adb reverse`.

## Proven

| Step | Result |
|------|--------|
| Offline Home cold start | TotalTime ≈ 1789–2019 ms; no AI init |
| Configure source + cleartext (debug) | OK |
| Sync signed index v11/v12 | `Index OK` |
| Download + verify SHA + install | `animal-world`, `move-break`, `apple-adventure` |
| Home reflects remote content | «Мир животных» without APK rebuild |
| Tampered index | `Invalid index signature (tampered or wrong key)` |
| Offline after reverse removed | Installed content + Home still work |

## Cold start / PSS (this milestone)

| Metric | 4.2 baseline | After distribution + Home cache fix |
|--------|--------------|--------------------------------------|
| Cold Home | ~2387 ms | ~1789–2019 ms |
| Fresh Home PSS | ~110624 KB | ~105316–105734 KB |

Home no longer force-`reload()`s the catalog on every resume (uses cached generation).

## Screenshots

`docs/images/platform-dist/`
