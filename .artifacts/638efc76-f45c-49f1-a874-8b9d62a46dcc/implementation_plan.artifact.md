# Fix Render Issue in LibraryManagerScreen Preview

The Compose Preview for `LibraryManagerScreen.kt` is failing with a `ClassNotFoundException: androidx.compose.ui.tooling.ComposeViewAdapter`. This indicates that the `androidx.compose.ui:ui-tooling` dependency is missing from the `:feature:feature-settings` module.

## User Review Required

> [!IMPORTANT]
> The fix involves adding `debugImplementation(libs.compose.ui.tooling)` to the `:feature:feature-settings` module. While other modules might also be missing this dependency, I will focus on fixing the reported issue in the current module to adhere to the "minimal change" rule.

## Proposed Changes

### feature-settings

#### [MODIFY] [build.gradle.kts](file:///D:/NEXTJJEN/repos/PlayFieldPortal/feature/feature-settings/build.gradle.kts)

Add `debugImplementation(libs.compose.ui.tooling)` to the dependencies block.

## Verification Plan

### Automated Tests
- Run `render_compose_preview` for `LibraryManagerScreenPreview` in `LibraryManagerScreen.kt`.

### Manual Verification
- None required as the automated tool verifies the rendering.
