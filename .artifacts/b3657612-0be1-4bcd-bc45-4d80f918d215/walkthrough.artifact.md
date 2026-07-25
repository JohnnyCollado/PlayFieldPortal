# Walkthrough: Systematic @Preview Coverage

I have implemented a comprehensive `@Preview` strategy across the project, including infrastructure for Multipreviews and high-coverage previews for core components and major screens.

## Infrastructure

### [PfpPreviews.kt](file:///D:/NEXTJJEN/repos/PlayFieldPortal/core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/preview/PfpPreviews.kt)
Created custom Multipreview annotations to test components across different configurations with a single tag:
- `@DevicePreviews`: Previews on Phone (Landscape), Tablet, and Desktop (XMB Baseline).
- `@FontScalePreviews`: Previews across 4 different font scales.
- `@CombinedPreviews`: Combines both for a quick comprehensive check.

### [PfpPreviewWrapper.kt](file:///D:/NEXTJJEN/repos/PlayFieldPortal/core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/preview/PfpPreviewWrapper.kt)
Created a `PfpPreview` wrapper that automatically applies `PFPTheme` and a standard `Surface` to previews, ensuring they render with the correct background and typography.

---

## Component Previews

Added previews to core UI components in the `:core:core-ui` module:
- **[XmbTouchButton.kt](file:///D:/NEXTJJEN/repos/PlayFieldPortal/core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/components/XmbTouchButton.kt)**: Previews for standard touch buttons, back buttons, and header pills.
- **[PspContextMenu.kt](file:///D:/NEXTJJEN/repos/PlayFieldPortal/core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/components/PspContextMenu.kt)**: Preview for the canonical PSP-style right-edge context menu.
- **[BoneGlyph.kt](file:///D:/NEXTJJEN/repos/PlayFieldPortal/core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/achievement/BoneGlyph.kt)**: Preview for the prestige bone glyph with different tints.

---

## Feature Screen Previews

Refactored major screens to support **State Hoisting**, allowing them to be previewed without a running ViewModel or database:

- **[LibraryManagerScreen.kt](file:///D:/NEXTJJEN/repos/PlayFieldPortal/feature/feature-settings/src/main/kotlin/com/playfieldportal/feature/settings/ui/LibraryManagerScreen.kt)**: Extracted `LibraryManagerContent` and added a preview using mock console/ROM data.
- **[ThemesSettingsScreen.kt](file:///D:/NEXTJJEN/repos/PlayFieldPortal/feature/feature-settings/src/main/kotlin/com/playfieldportal/feature/settings/ui/ThemesSettingsScreen.kt)**: Extracted `ThemesSettingsContent` and added previews for the theme management UI.
- **[AppDrawerScreen.kt](file:///D:/NEXTJJEN/repos/PlayFieldPortal/feature/feature-appbar/src/main/kotlin/com/playfieldportal/feature/appbar/AppDrawerScreen.kt)**: Extracted `AppDrawerContent` and added a preview with mock installed apps.
- **[AboutSettingsScreen.kt](file:///D:/NEXTJJEN/repos/PlayFieldPortal/feature/feature-settings/src/main/kotlin/com/playfieldportal/feature/settings/ui/AboutSettingsScreen.kt)**: Added a simple preview for the About screen.

---

## Main UI & XMB

### [XMBShell.kt](file:///D:/NEXTJJEN/repos/PlayFieldPortal/feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/XMBShell.kt)
Updated the existing XMB previews to use the new `@DevicePreviews` system and added a **Red Theme** variation to verify theme-switching logic visually.

## How to use
You can now see these previews in Android Studio by opening any of the mentioned files and clicking the **Design** tab in the top right.
