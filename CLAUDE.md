# PlayFieldPortal — Contributor Conventions

## General

- Never use emojis in any output — commit messages, release notes, docs, code comments, PR
  descriptions. Only include emojis when explicitly asked for them in that specific request.
  This overrides any pasted template or convention that includes emojis (e.g. emoji section
  headers): strip them and use the plain-text equivalent.

## Commit messages

Follow Chris Beams' "Seven Rules" plus Conventional Commits.

**Subject:** `<type>(<optional scope>): <description>`
- Types: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`
- Imperative mood ("Add", not "Added"), capitalized description, no trailing period
- 50 characters max
- Example: `feat(artwork): add media-URL cache for ScreenScraper`

**Body** (optional, after a blank line):
- Wrap at 72 characters
- Explain *what* the change solves and *why*, not how it was coded
- Bullet points for structural breakdowns
- Reference issues/PRs at the bottom (e.g. `Closes #123`)

**Hygiene:**
- Atomic commits — one single-purpose change each; never mix an unrelated fix into a feature
- No generic messages ("Fixed stuff", "Updates", "Fixing typo again")

## GitHub release summaries

Organize logically and prioritize user impact — a readable summary for both developers and
end users, not a raw commit dump.

**Structure:**
- Group changes under plain-text headers: `Breaking Changes`, `New Features`, `Bug Fixes`
- Put breaking changes at the very top, with migration/upgrade instructions
- Human-readable summaries: short sentences on the tangible value/impact of each change
- Reference issue and PR numbers (`#123`) for context
- Credit external contributors with `@mentions` at the bottom

**Versioning and metadata:**
- Semantic Versioning tags: `MAJOR.MINOR.PATCH`
- Release title = version tag plus an optional memorable codename
- Flag alpha/beta/RC builds as pre-release
- Attach compiled binaries / installers / build artifacts

Commit types map onto release categories: `feat` -> New Features, `fix` -> Bug Fixes,
anything flagged breaking -> Breaking Changes.
