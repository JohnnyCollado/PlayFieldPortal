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
