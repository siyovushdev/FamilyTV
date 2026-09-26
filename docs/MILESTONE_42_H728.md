# Milestone 4.2 — Rollback acceptance (H728)

Device: ADT-3 / H728 (`192.168.68.109:5555`)

## apple-adventure v1 → v2 → rollback → v2

| Step | `active:apple-adventure` | Home card description |
|------|--------------------------|------------------------|
| Activate v2 | `2` | `Видео, выбор, голос и разминка (v2)` |
| Rollback | `1` | `Короткое занятие: видео, выбор и голос` |
| Activate v2 again | `2` | `Видео, выбор, голос и разминка (v2)` |

Screenshots: `docs/images/platform-42/95-prove-v2-home.png`,
`96-prove-v1-home.png`, `97-prove-v2b-home.png`.

## Experience progress across versions

`ExperienceProgressStore.resumeStepId` clears resume when `packageVersion` differs
(unit-tested). After rollback to v1, an in-progress v2 step is not resumed.

## Packages installed on device

`apple-adventure` [1,2], `animal-world`@1, `move-break`@1, `morning-session`@1.
