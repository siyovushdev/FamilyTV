# Future Camera / ContextSignals (boundary only — not implemented)

Milestone 6.1 documents the integration point. **No camera, face recognition, or age model ships now.**

## Principle

Vision must never drive Android UI directly.

```
Camera / on-device vision
    → ContextSignal (allowlisted)
    → Interaction / Adventure runtime
    → FamilyAction or presentation hint
```

## Allowed future signals (examples)

| Signal | Meaning |
|--------|---------|
| `presence` | Someone is in front of the TV |
| `peopleCount` | Approximate count (siblings) |
| `approximateAgeRange` | Soft band suggestion |
| `gesture` | Coarse gesture (wave, thumbs-up) |
| `activityCompleted` | Off-screen move likely done |
| `objectDetected` | Coarse class (ball, book) — no identity |

## Forbidden now / forever (product)

- Face identity / recognition profiles
- Binding a session to “who is watching”
- Irreversible content lock based on camera age
- Creating child accounts from vision

## Age estimation safety

If a future model estimates age:

1. It is an **approximate signal**, not truth.
2. **Parent / device settings win** (`HouseholdAgeBand`, `ReadingSupportMode`).
3. Camera may **suggest** presentation or difficulty.
4. Camera must **not** permanently block content, create profiles, assert exact age, or change parental policy alone.

## Relation to FamilyAction

New input source enum already includes `InputSource.VISION` / `GESTURE` for future dispatch — unused in 6.1.
