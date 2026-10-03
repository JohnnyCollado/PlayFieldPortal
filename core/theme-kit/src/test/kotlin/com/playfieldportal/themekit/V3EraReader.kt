package com.playfieldportal.themekit

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * FROZEN. A copy of the v3-era `.pfptheme` reader as it behaved before Theme Format v4:
 * today's manifest field set, today's icon (52) and sysicon (40) gating key lists, and the same
 * `ignoreUnknownKeys` decoding. It exists so later tasks can prove that a v4 (or future) bundle
 * still yields its v3 subset on a pre-v4 launcher.
 *
 * Do NOT update this file when the production format grows, and do not derive anything here from
 * production code ([IconSlots], [PfpThemeManifest], ...): a reader that tracks the code it is
 * checking cannot catch a regression in it. Test sources only.
 */
internal object V3EraReader {

    @Serializable
    data class Layout(
        val barTopFraction: Float = 0.11f,
        val contentTopPaddingDp: Float = 20f,
        val categoryIconSelectedDp: Float = 72f,
        val categoryIconDp: Float = 56f,
        val itemIconDp: Float = 62f,
        val itemIconSlotDp: Float = 74f,
        val itemTextSelectedSp: Float = 22f,
        val itemTextSp: Float = 18f,
        val itemTextStartGapDp: Float = 14f,
        val leftAnchorExtraDp: Float = 6f,
        val previousItemRiseRows: Float = 0.5f,
    )

    @Serializable
    data class Source(val type: String, val file: String? = null, val firmware: String? = null)

    @Serializable
    data class Manifest(
        val manifest: String = "pfptheme",
        val schemaVersion: Int = 3,
        val name: String,
        val accentColor: String,
        val iconColor: String = "auto",
        val textColor: String = "auto",
        val waveStyle: String = "animated",
        val layout: Layout? = null,
        val source: Source? = null,
        val created: String? = null,
    )

    /** What the v3-era reader surfaced; icon maps are key -> extension (payloads are opaque). */
    class Result(
        val manifest: Manifest,
        val wallpaper: ByteArray?,
        val preview: ByteArray?,
        val icons: Map<String, String>,
        val sysicons: Map<String, String>,
        val motionExtension: String?,
    )

    val ICON_KEYS: Set<String> = setOf(
        "catbar_games", "catbar_music", "catbar_video", "catbar_photos", "catbar_settings",
        "catbar_network", "catbar_appstore", "catbar_social", "catbar_favorites", "catbar_achievements",
        "item_add", "item_missing", "item_memcard_games", "item_memcard_music", "item_memcard_video",
        "item_memcard_photos", "item_settings", "item_video_folder", "item_video_library",
        "item_video_recent", "item_video_favorites", "item_video_collections", "item_video_apps",
        "item_video_file", "item_photo_folder", "item_photo_file", "item_photo_albums", "item_photo_apps",
        "item_camera", "item_music_track", "item_playlist", "item_music_apps", "item_social_add",
        "item_social_account", "item_social_friends", "item_social_voice", "item_social_voice_invite",
        "item_social_voice_mute", "item_social_voice_settings", "item_social_voice_leave",
        "item_social_activity", "item_social_discord_settings", "item_social_signout",
        "item_shiba_connect", "item_shiba_track", "item_shiba_untracked",
        "status_battery_full", "status_battery_high", "status_battery_medium", "status_battery_low",
        "status_battery_charging", "status_bluetooth",
    )

    val SYSICON_IDS: Set<String> = setOf(
        "allgames", "android", "atari2600", "atari5200", "atari7800", "atarilynx", "c64", "dreamcast",
        "gamegear", "gb", "gba", "gbc", "gc", "mame", "mastersystem", "megadrive", "n3ds", "n64", "nds",
        "neogeo", "nes", "ngp", "pcengine", "ps2", "ps3", "psp", "psvita", "psx", "saturn", "sega32x",
        "segacd", "snes", "switch", "virtualboy", "wii", "wiiu", "windows", "wonderswan",
        "wonderswancolor", "x360",
    )

    private val ICON_EXTENSIONS = setOf("png", "gif")
    private val MOTION_EXTENSIONS = setOf("mp4", "webm", "gif")
    private val json = Json { ignoreUnknownKeys = true }

    /** Null when there is no decodable pfptheme manifest, the v3-era "not a theme" answer. */
    fun read(bytes: ByteArray): Result? {
        var manifest: Manifest? = null
        var wallpaper: ByteArray? = null
        var preview: ByteArray? = null
        val icons = linkedMapOf<String, String>()
        val sysicons = linkedMapOf<String, String>()
        var motion: String? = null

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                val data = zip.readBytes()
                when {
                    name == "manifest.json" -> manifest = runCatching {
                        json.decodeFromString(Manifest.serializer(), data.decodeToString())
                    }.getOrNull()
                    name == "wallpaper.png" -> wallpaper = data
                    name == "preview.png" -> preview = data
                    name.startsWith("icons/") -> {
                        val file = name.removePrefix("icons/")
                        val key = file.substringBeforeLast('.')
                        val ext = file.substringAfterLast('.', "").lowercase()
                        if (ext in ICON_EXTENSIONS && key in ICON_KEYS) icons[key] = ext
                    }
                    name.startsWith("sysicons/") -> {
                        val file = name.removePrefix("sysicons/")
                        val id = file.substringBeforeLast('.')
                        val ext = file.substringAfterLast('.', "").lowercase()
                        if (ext in ICON_EXTENSIONS && id in SYSICON_IDS) sysicons[id] = ext
                    }
                    name.startsWith("motion.") -> {
                        val ext = name.removePrefix("motion.").lowercase()
                        if (ext in MOTION_EXTENSIONS) motion = ext
                    }
                }
            }
        }
        val m = manifest ?: return null
        if (m.manifest != "pfptheme") return null
        return Result(m, wallpaper, preview, icons, sysicons, motion)
    }
}
