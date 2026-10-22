# Issue reports

One file per bug we fixed, so a past fix can be looked up before its code is changed. Each report
records symptoms, affected devices, root cause, the fix (files, functions, guarding tests), and
**Do not change** warnings. Read the matching report before touching code it names.

File name: `YYYY-MM-DD-<short-slug>.md` (date the report was written).

| Date | Issue | Status | Area |
|------|-------|--------|------|
| 2026-10-22 | [Parasite Eve II randomly shows as missing until reboot](2026-10-22-parasite-eve-missing.md) | Fixed, awaiting device check | ROM scan / missing flags |

## Report template

```
# <Title>
Status · Date · Branch/commit
## Symptoms        what the user sees
## Affected        devices, storage (internal/SD), app version
## Root cause      plain words, then file:line
## Fix             files, functions, tests that guard it
## Do not change   "Don't make X do Y — it fixes Z (see test T)"
## History         earlier related fixes
```
