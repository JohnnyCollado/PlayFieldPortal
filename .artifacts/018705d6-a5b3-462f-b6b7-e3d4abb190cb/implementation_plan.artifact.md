# Implementation Plan - Fix PC Import Regressions

Resolve the build failure and runtime issues in the PC game import flow introduced by the previous refactoring.

## Proposed Changes

### feature-settings

#### [MODIFY] [LibraryManagerScreen.kt](file:///D:/NEXTJJEN/repos/PlayFieldPortal/feature/feature-settings/src/main/kotlin/com/playfieldportal/feature/settings/ui/LibraryManagerScreen.kt)
- Fix the type mismatch in `LibraryManagerScreenPreview`: change `onPickPcImportFolder = {}` to `onPickPcImportFolder = { _ -> }`.

#### [MODIFY] [LibraryManagerViewModel.kt](file:///D:/NEXTJJEN/repos/PlayFieldPortal/feature/feature-settings/src/main/kotlin/com/playfieldportal/feature/settings/viewmodel/LibraryManagerViewModel.kt)
- Update `LibraryManagerUiState` to include `pcImportLabel`.
- In `uiState` combine, derive `pcImportLabel` from the tree URI (using `substringAfterLast('%3A')` or similar as a fallback for non-raw paths) so the UI doesn't show "Not set" when a folder IS picked.
- Use `pcImportLabel` in the UI and use `pcImport != null` to drive the "Use Default Folders" visibility.

#### [MODIFY] [PcGameScanner.kt](file:///D:/NEXTJJEN/repos/PlayFieldPortal/feature/feature-settings/src/main/kotlin/com/playfieldportal/feature/settings/pc/PcGameScanner.kt)
- Generalize the scan report messages to say "in the import folder(s)" instead of hardcoding `<windows>/import`, which is confusing when a custom folder is picked.

## Verification Plan

### Automated Verification
- Run `gradle_build(":feature:feature-settings:assembleDebug")` to ensure the preview and module compile.
- Run `gradle_build(":app:assembleLiteDebug")` to verify the whole app builds.

### Manual Verification
1.  Open **Settings > Library Manager > Windows Memory Card > Import PC Games**.
2.  Pick a folder (e.g. from Downloads).
3.  Verify the row updates to show the folder name instead of "Not set".
4.  Verify the "Use Default Folders" row appears.
5.  Perform a scan and verify the message is correct.
