package com.playfieldportal.core.domain.model

data class PFPTheme(
    val id: String,
    val name: String,
    val author: String? = null,
    val version: String = "1.0",
    val waveColor: Long,                // ARGB
    val waveOpacity: Float = 0.7f,
    val waveSpeed: Float = 1.0f,
    val waveAmplitude: Float = 1.0f,
    val accentColor: Long,
    val textColor: Long,
    val backgroundUri: String? = null,  // path to background image
    val fontKey: String = "system_default",
    val hasBootAnimation: Boolean = false,
    val bootAnimationUri: String? = null,
    val soundPackUri: String? = null,
    val packagePath: String? = null,    // path to .xmbtheme file
    val isBuiltIn: Boolean = false,
)

// Sound events themes can provide
enum class ThemeSoundEvent {
    NAVIGATE_HORIZONTAL,
    NAVIGATE_VERTICAL,
    SELECT,
    BACK,
    CATEGORY_CHANGE,
    BOOT,
}
