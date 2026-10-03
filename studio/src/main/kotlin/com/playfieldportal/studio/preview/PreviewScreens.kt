package com.playfieldportal.studio.preview

import androidx.compose.runtime.Composable
import com.playfieldportal.studio.preview.screens.AchievementsScreenPreview
import com.playfieldportal.studio.preview.screens.AppDrawerScreenPreview
import com.playfieldportal.studio.preview.screens.AppPickerScreenPreview
import com.playfieldportal.studio.preview.screens.ArtworkStudioScreenPreview
import com.playfieldportal.studio.preview.screens.GameAchievementsScreenPreview
import com.playfieldportal.studio.preview.screens.GameDetailScreenPreview
import com.playfieldportal.studio.preview.screens.SettingsScreenPreview

/**
 * What the preview shows: the XMB, or one of the launcher's other screens drawn with the theme's
 * settings (the "Open" menu). The screens are still frames of sample content at the launcher's
 * 832x468 dp baseline, each replicated from its launcher source like the XMB is.
 */
enum class PreviewScreen(val label: String) {
    XMB("XMB"),
    APP_DRAWER("App Drawer"),
    SETTINGS("Settings"),
    APP_PICKER("App Picker"),
    ARTWORK_STUDIO("Artwork Studio"),
    GAME_DETAIL("Game Detail Screen"),
    ACHIEVEMENTS("Achievement Screen"),
    GAME_ACHIEVEMENTS("Per-Game Achievement Screen"),
}

/** The opened [screen] at design size, filling the frame (XMB is drawn by XmbFrame itself). */
@Composable
fun ScreenPreview(screen: PreviewScreen, model: XmbPreviewModel) {
    when (screen) {
        PreviewScreen.XMB -> Unit
        PreviewScreen.APP_DRAWER -> AppDrawerScreenPreview(model)
        PreviewScreen.SETTINGS -> SettingsScreenPreview(model)
        PreviewScreen.APP_PICKER -> AppPickerScreenPreview(model)
        PreviewScreen.ARTWORK_STUDIO -> ArtworkStudioScreenPreview(model)
        PreviewScreen.GAME_DETAIL -> GameDetailScreenPreview(model)
        PreviewScreen.ACHIEVEMENTS -> AchievementsScreenPreview(model)
        PreviewScreen.GAME_ACHIEVEMENTS -> GameAchievementsScreenPreview(model)
    }
}
