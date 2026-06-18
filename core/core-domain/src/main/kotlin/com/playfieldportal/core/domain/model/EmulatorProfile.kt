package com.playfieldportal.core.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class EmulatorProfile(
    val id: String,
    val name: String,
    val packageName: String,
    val activityClass: String? = null,
    val intentType: IntentType,
    val supportedPlatformIds: List<String>,
    val intentExtras: Map<String, String> = emptyMap(),     // key → value template
    val coreMap: Map<String, String> = emptyMap(),          // platformId → core path
    val mimeType: String? = null,
    val useFileUri: Boolean = true,
    val useSafUri: Boolean = false,
    val customCommand: String? = null,                       // overrides all if set
    val minVersionCode: Int? = null,
    val notes: String? = null,
    val isCustom: Boolean = false,                           // user-created profile
)

@Serializable
enum class IntentType {
    ACTION_VIEW,
    COMPONENT,
    SHORTCUT,
    CUSTOM_COMMAND,
}

// Template variables resolved at launch time
object LaunchTemplate {
    const val ROM_PATH    = "{rom_path}"
    const val ROM_NAME    = "{rom_name}"
    const val ROM_DIR     = "{rom_dir}"
    const val CORE_PATH   = "{core_path}"
    const val CONFIG_PATH = "{config_path}"
    const val PACKAGE     = "{package}"
    const val PLATFORM    = "{platform}"
}
