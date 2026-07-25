# Systematic @Preview Coverage Plan

Adding `@Preview` support to all Composable components will significantly improve developer productivity and UI consistency. This plan outlines a phased approach to achieve high preview coverage using modern Compose techniques.

## User Review Required

> [!IMPORTANT]
> Some Composables are currently coupled with `hiltViewModel()` or complex internal states. To make them previewable, I will need to refactor them to follow the **State Hoisting** pattern (passing state as parameters instead of resolving it inside the Composable).

## Proposed Changes

### Phase 1: Core UI & Multipreview Infrastructure
Establish the standard for how previews should look across the app.

#### [NEW] [PfpPreviews.kt](file:///D:/NEXTJJEN/repos/PlayFieldPortal/core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/theme/PfpPreviews.kt)
Create custom "Multipreview" annotations to reduce boilerplate:
- `@DevicePreviews`: Shows common device sizes (Phone, Tablet, Desktop).
- `@ThemePreviews`: Shows the component in different XMB color schemes (Blue, Red, Dark, etc.).
- `@CombinedPreviews`: A combination of both.

#### [MODIFY] [PreviewData.kt](file:///D:/NEXTJJEN/repos/PlayFieldPortal/core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/preview/PreviewData.kt)
Move/Extend `PreviewData` to the `core-ui` module so all features can access standard mock data (e.g., `XMBItem`, `Category`, `User`).

### Phase 2: Component Previews (Atom Level)
Add previews to small, reusable components in `core-ui`.
- `XmbTouchButton.kt`
- `PspContextMenu.kt`
- `PortalIcon.kt`
- `BoneGlyph.kt`

### Phase 3: Screen & Feature Previews
Add previews to full screens. This will involve refactoring "Screen" composables into:
1. **ScreenContainer**: (coupled with ViewModel)
2. **ScreenContent**: (pure UI, accepts State and Event Lambdas) — **This is what we preview.**

Modules to cover:
- `:feature:feature-settings` (LibraryManager, Themes, etc.)
- `:feature:feature-appbar` (App Drawer)
- `:feature:feature-xmb` (Main Shell, Game Details)
- `:feature:feature-social` (QR Login, Friends)

## Verification Plan

### Manual Verification
- Open the **Design** tab in Android Studio for each modified file.
- Verify that the previews render correctly without errors.
- Check that interactive components (like buttons) look correct in both Light/Dark or different theme variations.
