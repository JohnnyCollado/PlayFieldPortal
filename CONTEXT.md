# PlayFieldPortal domain glossary

This glossary names the domain concepts used by the library scan module and its callers.

## Memory Card

A configured library record for one platform. A Memory Card may read ROMs from its own SAF tree,
a platform folder under one or more managed ROM roots, or a legacy raw directory. It is a logical
library concept and does not require a physically removable card.

## ROM source

One configured location from which ROMs for a Memory Card can be surveyed. The source may be a SAF
tree, a managed ROM-root subfolder, or a legacy directory.

## ROM survey

A read of every resolved ROM source for one Memory Card. A survey reports newly discovered games,
the set of present ROM paths when the result is trustworthy, and any source error or inability to
survey.

## Missing reconciliation

The non-destructive policy applied after a trustworthy ROM survey. ROM rows whose paths are absent
from the surveyed present set are marked Missing; they are never deleted. A later survey can clear
the Missing state when the file returns.

## Missing

The recoverable library state for a known game whose ROM path was not present in the last trustworthy
survey. Missing affects visibility and launchability, not ownership of the game row, artwork,
favorites, collections, or play history.

## LibraryScanner

The deep module that owns the ROM survey, new-game upserts, optional Missing reconciliation,
changed-only scan persistence, per-card single-flight, and IO execution for configured Memory Cards.
The settings interface maps its outcomes to messages; trigger adapters decide when to request a scan.

## Triggered rescan

A ROM survey requested by an app-resume or strong Android signal such as media mount or USB unplug.
Trigger timing, debounce, throttle, and single-flight behavior are scheduling concerns rather than
ROM survey policy.

## Icon slot

One named, replaceable position in the XMB's icon set. Slots come from `CustomizableIcons` and
cover both theme slots (the category-bar and menu glyphs) and `sysicon_*` console slots. A slot key
is used verbatim as a filename, so key validation is what keeps a crafted key inside its directory.

## Render tier

The precedence that decides which image a given icon slot draws: **user pick > theme icon >
built-in**. A user pick lives in `custom-icons/`, a theme icon in the applied bundle's extracted
`theme-icons/`, and the built-in is the bundled drawable from `CategoryIcons`. Applying a theme
never clears user picks — it changes only the middle tier.

## Applied look

The flattened result of the render tiers as they currently draw — what the user actually sees,
after user picks have won over theme icons. `saveCurrentLook()` captures this as a new bundle.
It deliberately excludes device-specific state such as the XMB layout adjustment.

## Theme bundle

A `.pfptheme` archive: a manifest plus wallpaper, icons, console art, and an optional motion
wallpaper. Schema v3 is additive over v2 — readers never gate on `schemaVersion`, so a v3 bundle
still opens on a v2-era build, which simply sees the v2 subset.

## Motion wallpaper

A looping video or animated image used as the XMB background, paired with a poster still captured
at import. Video decodes through a player; animated GIF/WebP decode as animated images and never
construct one. Every freeze condition releases the decoder outright and falls back to the poster,
rather than holding a paused player.

## UI media slot

One named, replaceable position in the interface's sound and boot media — the six menu sounds, the
boot video/audio pair, and GameBoot's single replaceable clip. Each slot carries its own duration
and byte caps (`UiMediaLimits`); duration is always bounded. Import is staged, so a rejected pick
cannot disturb a working assignment. `BOOT_AUDIO` ships a bundled default too (`sfx_opening`), so
the boot sequence is audible out of the box. GameBoot has no audio slot: its built-in sequence
carries its own bundled sound (`sfx_launch`), and assigning a clip replaces the whole presentation,
audio included.

## Bundled default

The sample or clip a UI media slot falls back to when the user has assigned nothing. Resolution
happens inside the player, so a custom assignment replaces the default with no change at the
roughly ninety call sites that fire these events.
