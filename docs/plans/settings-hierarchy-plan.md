# Settings Hierarchy Refactor Plan

## Goal

Replace the current flat Settings list with a multi-level, XMB-native hierarchy. Level 1 section entries will open flyouts using the same interaction model as Music XMB items. Level 2 entries will launch the existing settings screens initially, allowing the navigation structure to land before larger screen-level splits are attempted.

## Target hierarchy

```text
Settings
├── Android Settings                         L1 — unchanged; opens Android system settings
├── Library                                  L1 nested XMB item
│   ├── Platforms                            L2 — current Library Manager
│   ├── Collections                          L2 — current Collections settings
│   ├── Artwork                               L2 — current Artwork settings
│   └── Hidden Games                          L2 — moved from Display settings
├── Emulators                                L1 nested XMB item
│   ├── Installed                             L2 — installed/not-installed knowledge-base apps
│   ├── Custom Emulators                      L2 — custom emulator profiles and Add Custom Emulator
│   └── RetroArch                              L2 — RetroArch core detection
├── Interface                                L1 nested XMB item
│   ├── Categories                            L2 — current Categories settings
│   ├── Themes                                L2 — current Themes settings
│   ├── Display                               L2 — current Display settings
│   └── Controller                            L2 — current Controller settings
├── Achievements                             L1 nested XMB item
│   ├── Player Card                           L2
│   ├── Provider Credentials                  L2
│   ├── Local Windows                         L2
│   └── Update Achievements                   L2 — Sync and Auto-Match sections
└── System                                   L1 nested XMB item
    ├── About                                 L2
    ├── Logs                                  L2
    ├── Backup & Restore                      L2
    ├── Setup Wizard                          L2
    └── Credits                               L2
```

## Navigation model

1. The Settings category root contains only the Android Settings row and the five nested L1 section rows.
2. Selecting a nested L1 row opens a settings flyout/list containing its L2 rows.
3. Selecting an L2 row opens the associated existing settings overlay screen.
4. Back from an L2 screen returns to the owning L1 flyout.
5. Back from an L1 flyout returns to the main XMB.
6. Controller and touch interactions must match the established Music flyout behavior.
7. The settings overlay must continue to block input to the XMB behind it.

## Implementation phases

### 1. Inventory current settings routes

- Enumerate all current Settings XMB item IDs.
- Identify direct callers that assign `activeSettingsScreen`.
- Identify the current Hidden Games/App Visibility route and Display entry.
- Record existing screen/view-model responsibilities before changing them.

### 2. Add settings navigation state

- Add a settings navigation type/state to `XMBViewModel`.
- Represent the Settings root and each nested L1 section explicitly.
- Reuse the existing XMB item model and flyout rendering path where practical.
- Define stable IDs for section rows and L2 rows.
- Keep direct screen routes available during migration so existing context-menu and setup flows do not break.

### 3. Replace the flat Settings root

- Preserve Android Settings unchanged.
- Replace the flat list of Library, Emulators, Themes, Display, and other rows with the five nested L1 sections.
- Preserve the existing Settings category ordering and visual conventions.
- Ensure the root list remains concise and controller-friendly.

### 4. Implement L1 flyout contents

Create the following section item lists:

- Library: Platforms, Collections, Artwork, Hidden Games.
- Emulators: Installed, Custom Emulators, RetroArch.
- Interface: Categories, Themes, Display, Controller.
- Achievements: Player Card, Provider Credentials, Local Windows, Update Achievements.
- System: About, Logs, Backup & Restore, Setup Wizard, Credits.

For the first pass, L2 entries may route into existing combined screens with an initial subsection/focus target where needed.

### 5. Reuse and adapt existing screens

- Library Platforms opens the current Library Manager.
- Collections opens the current Collections screen.
- Artwork opens Artwork settings.
- Emulators L2 entries reuse the existing emulator screen, with focus/section entry points for Installed, Custom Emulators, and RetroArch.
- Achievements L2 entries reuse the existing Achievements screen, with focus/section entry points for Player Card, credentials, Local Windows, and update actions.
- Interface entries reuse the existing Categories, Themes, Display, and Controller screens.
- System entries reuse About, Logs, Backup & Restore, Setup Wizard, and Credits screens.

Splitting combined screens into separate composables is intentionally deferred until the hierarchy and navigation behavior are stable.

### 6. Move Hidden Games

- Remove Hidden Games/App Visibility from Display settings.
- Add it under Library > Hidden Games.
- Preserve the existing screen and behavior.
- Confirm hidden-game state remains independent of the navigation change.

### 7. Update integrations

Audit and update all routes that open settings directly, including:

- Library Manager context-menu actions.
- Artwork and icon-display links.
- Achievement/player-card links.
- Setup wizard and first-run prompts.
- Any Display or Hidden Games links.
- Documentation and user-facing labels containing old paths.

Direct links should open the correct L2 screen and, where supported, select the relevant owning section on return.

## Testing plan

### Unit tests

- Settings root contains Android Settings plus the five nested sections in the expected order.
- Each L1 section exposes the expected L2 IDs and labels.
- Every L2 ID maps to the correct existing screen route.
- Back navigation moves from L2 screen to L1 flyout and then to the XMB.
- Hidden Games is absent from Display and present under Library.
- Legacy direct-entry routes continue to resolve.

### UI/manual checks

- Controller Up/Down stays inside the active Settings list.
- Select opens an L1 flyout or L2 screen correctly.
- Back never skips a hierarchy level.
- Touch selection and controller selection behave consistently.
- Flyout layout matches Music XMB behavior.
- Settings remain visually readable over the themed background.
- Existing setup prompts, context menus, and achievement actions still work.

## Files likely to change

- `feature/feature-xmb/.../XMBViewModel.kt`
- `feature/feature-xmb/.../XMBItemList.kt` or the shared flyout renderer, if required
- `feature/feature-settings/.../SettingsNavHost.kt`
- Existing settings screens/view models that need subsection entry points
- Hidden Games/App Visibility screen and its callers
- Feature XMB and settings tests
- Relevant documentation

Prefer editing existing files and preserving current route IDs where possible. Do not create separate screens for every L2 item until the navigation layer is proven.

## Risks and mitigations

- **Back-stack regressions:** centralize settings navigation transitions and test every level.
- **Focus escaping into the XMB:** use the existing explicit settings focus registry and flyout focus behavior.
- **Broken deep links:** retain compatibility routes while migrating callers.
- **Overly large first change:** separate navigation restructuring from later screen decomposition.
- **Hidden settings becoming inaccessible:** verify Hidden Games through the Library path before removing the old Display entry.

## Done when

- Settings root shows the requested five nested sections plus Android Settings.
- Each section opens a working L2 flyout.
- All requested L2 items reach their existing functionality.
- Hidden Games is available only under Library in the Settings UI.
- Controller, touch, and back navigation work through both hierarchy levels.
- Relevant tests and project verification tasks pass.
