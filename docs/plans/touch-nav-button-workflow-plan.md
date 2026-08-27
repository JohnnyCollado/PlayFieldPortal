# Touch Navigation Button — Behavior Inventory & Workflow Decisions

Context: the [settings-hierarchy-plan](settings-hierarchy-plan.md) introduces a second drill level
under the Settings category. Every existing drill-in hides the bottom-right contextual
("navigation") button, so we must decide what the button does in each new settings state before
shipping. This doc records how it works today and the decisions pending sign-off.

## What the button is

`AppDrawerButton` in the shell's bottom-right corner (`XMBShell.kt`). Its visibility follows
`uiState.resolvedShowTouchButton && !uiState.hasBlockingOverlay && !uiState.isInSubItem`, and its
single action is `onOpenAppDrawer()` (opens the Android app drawer filtered to All Apps).

## The user-facing setting

Display ▸ Touch Navigation Button cycles three modes (`TouchNavButtonMode`):

| Mode          | Resolved visibility                     |
|---------------|------------------------------------------|
| Auto (default)| Only when the last input was touch        |
| Always Show   | Always eligible (overlay/drill rules below still apply) |
| Always Hide   | Never shown                               |

Resolved eligibility (`resolvedShowTouchButton`) is then gated by the state rules below.

## Where it appears today

- **Top-level lists of every category** (Photo, Music, Video, Games, Network, App Store,
  Settings): visible when eligible; tap opens the app drawer.
- **Nothing drilled, no overlay** is the only family of states where it renders.

## Where it is hidden today, and what replaces it

1. **Any blocking overlay** (`hasBlockingOverlay`) — every fullscreen Settings screen, the app
   drawer itself, game/app detail pages, music browser, dialogs, context menus. The button is
   simply behind/gone; each overlay carries its own Back affordance (e.g. every settings screen's
   title-bar BACK routed through `SettingsScaffold`).
2. **Any drill-in** (`isInSubItem`) — a Games platform/collection card, Music, Video, Photo,
   Discord Social, Shiba Coins hub views. Replacements:
   - **Gamepad B** steps back exactly one level.
   - **Left-edge swipe** performs the same one-level back (`onHomeBack`).
   - **Tapping the active memory-card icon** in the drill flyout's left column backs out too.
   At an undrilled root, B / swipe instead opens the app drawer.

## States the settings hierarchy adds

| State                              | Button today's rules say…            | Open decision |
|------------------------------------|---------------------------------------|---------------|
| Settings root (6 section rows)     | Visible (eligible) — tap opens drawer | Q2 below      |
| L1 section flyout open (new drill) | Hidden, because sections join `isInSubItem` | Q1 below |
| L2 screen open over a flyout       | Hidden (blocking overlay, unchanged)  | none — screens own Back, which pops to the owning flyout |

## Decisions requested

**Q1 — While a settings section flyout is open:** follow the established drill contract (button
hidden; back = swipe / tap the active section icon / controller B), or keep the button visible in
this one place?

**Q2 — At the Settings root (section list):** keep current top-level behavior (eligible touch users
see it; tap opens the app drawer), or hide it inside the Settings category so it reads purely as a
drawer shortcut for content categories?
