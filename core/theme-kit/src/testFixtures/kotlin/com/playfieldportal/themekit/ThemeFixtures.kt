package com.playfieldportal.themekit

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Golden `.pfptheme` bundles, one per historical schema, for compatibility tests in theme-kit,
 * core-data and studio.
 *
 * Deliberately built from raw JSON strings and [ZipOutputStream], never from [PfpThemeManifest] or
 * [PfpThemeCodec.write]: a fixture that went through today's data classes would drift with them
 * and stop proving anything. The shapes are the manifests as they were at `f6078e30` (v1) and
 * `db0eb13b` (v2), the current reader's v3, a v4 file per the Theme Format v4 spec, and a
 * "future" file no shipped reader has seen.
 *
 * Entry payloads are small opaque byte arrays (the codec never decodes images or video); each is
 * distinct so a test can tell which entry a reader surfaced.
 */
object ThemeFixtures {

    /** 2026-01-01T00:00Z: one fixed instant for every fixture entry. */
    private const val FIXED_ENTRY_TIME = 1_767_225_600_000L

    val WALLPAPER = "golden-wallpaper-png".toByteArray()
    val PREVIEW = "golden-preview-png".toByteArray()
    val ICON_PNG = "golden-icon-png".toByteArray()
    val ICON_GIF = "golden-icon-gif".toByteArray()
    val SYSICON_PNG = "golden-sysicon-png".toByteArray()
    val MOTION_MP4 = "golden-motion-mp4".toByteArray()
    val SOUND_OGG = "golden-sound-ogg".toByteArray()
    val AMBIENCE_MP3 = "golden-ambience-mp3".toByteArray()
    val BOOT_MP4 = "golden-boot-mp4".toByteArray()
    val FUTURE_BLOB = "golden-future-blob".toByteArray()

    /** Zip of [entries] in the order given (manifest first, as every writer does). */
    fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in entries) {
                // Fixed time: identical fixtures must be byte-identical, whatever the clock says.
                zip.putNextEntry(ZipEntry(name).apply { time = FIXED_ENTRY_TIME })
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Schema 1 (`f6078e30`): wallpaper + preview, no icons, no textColor. PTF-import provenance. */
    fun v1(): ByteArray = zip(
        "manifest.json" to """
            {
              "manifest": "pfptheme",
              "schemaVersion": 1,
              "name": "Golden V1",
              "accentColor": "#3A6FD8",
              "iconColor": "auto",
              "waveStyle": "reduced",
              "layout": { "barTopFraction": 0.2 },
              "source": { "type": "ptf-import", "file": "classic.ptf", "firmware": "6.60" },
              "created": "2026-07-06"
            }
        """.trimIndent().toByteArray(),
        "wallpaper.png" to WALLPAPER,
        "preview.png" to PREVIEW,
    )

    /** Schema 2 (`db0eb13b`): adds `icons/<key>.png` custom slots. */
    fun v2(): ByteArray = zip(
        "manifest.json" to """
            {
              "manifest": "pfptheme",
              "schemaVersion": 2,
              "name": "Golden V2",
              "accentColor": "#FF72B1",
              "iconColor": "#FFFFFF",
              "waveStyle": "static",
              "source": { "type": "user-created" },
              "created": "2026-07-07"
            }
        """.trimIndent().toByteArray(),
        "wallpaper.png" to WALLPAPER,
        "preview.png" to PREVIEW,
        "icons/catbar_games.png" to ICON_PNG,
        "icons/item_add.png" to ICON_PNG,
    )

    /** Schema 3 (current): gif icon, sysicon, motion, textColor, full layout. */
    fun v3(): ByteArray = zip(
        "manifest.json" to """
            {
              "manifest": "pfptheme",
              "schemaVersion": 3,
              "name": "Golden V3",
              "accentColor": "#2EC4B6",
              "iconColor": "auto",
              "textColor": "#EEEEEE",
              "waveStyle": "animated",
              "layout": {
                "barTopFraction": 0.15,
                "contentTopPaddingDp": 24.0,
                "categoryIconSelectedDp": 70.0,
                "categoryIconDp": 54.0,
                "itemIconDp": 60.0,
                "itemIconSlotDp": 72.0,
                "itemTextSelectedSp": 21.0,
                "itemTextSp": 17.0,
                "itemTextStartGapDp": 12.0,
                "leftAnchorExtraDp": 4.0,
                "previousItemRiseRows": 0.4
              },
              "source": { "type": "user-created" },
              "created": "2026-08-01"
            }
        """.trimIndent().toByteArray(),
        "wallpaper.png" to WALLPAPER,
        "preview.png" to PREVIEW,
        "icons/catbar_games.png" to ICON_PNG,
        "icons/status_bluetooth.gif" to ICON_GIF,
        "sysicons/psx.png" to SYSICON_PNG,
        "motion.mp4" to MOTION_MP4,
    )

    /**
     * Schema 4 per the format spec: new manifest fields plus sounds / ambience / boot entries and a
     * v4-only icon key (`status_wifi`). The v4 reader understands every addition; the frozen
     * v3-era reader snapshot is what pins how an older build treats them.
     */
    fun v4(): ByteArray = zip(
        "manifest.json" to """
            {
              "manifest": "pfptheme",
              "schemaVersion": 4,
              "name": "Golden V4",
              "author": "Jane",
              "description": "Neon over rain",
              "accentColor": "#3A6FD8",
              "iconColor": "auto",
              "textColor": "auto",
              "textColorExact": false,
              "waveStyle": "static",
              "waveStyleV4": "reduced_static",
              "legibility": { "text": "auto", "icon": "contour_auto", "solidUnfocusedIcons": false },
              "motionCrop": { "x": 0.1, "y": 0.0, "w": 0.8, "h": 1.0 },
              "source": { "type": "user-created" },
              "created": "2026-07-06",
              "updated": "2026-10-02"
            }
        """.trimIndent().toByteArray(),
        "wallpaper.png" to WALLPAPER,
        "preview.png" to PREVIEW,
        "icons/catbar_games.png" to ICON_PNG,
        "icons/status_wifi.png" to ICON_PNG,
        "sysicons/psx.png" to SYSICON_PNG,
        "motion.mp4" to MOTION_MP4,
        "sounds/sound_scroll.ogg" to SOUND_OGG,
        "ambience.mp3" to AMBIENCE_MP3,
        "boot.mp4" to BOOT_MP4,
    )

    /**
     * A file from a version nobody has shipped: schema 99, unknown manifest keys (one a nested
     * object), an unknown `waveStyle` value, unknown legibility enum values, and unknown zip
     * entries (`extras/thing.bin`, `icons/item_from_the_future.png`, ...).
     */
    fun future(): ByteArray = zip(
        "manifest.json" to """
            {
              "manifest": "pfptheme",
              "schemaVersion": 99,
              "name": "Golden Future",
              "accentColor": "#112233",
              "iconColor": "auto",
              "waveStyle": "wobbly",
              "legibility": { "text": "hologram", "icon": "contour_auto" },
              "source": { "type": "user-created" },
              "created": "2031-01-01",
              "someFutureField": { "nested": { "a": 1, "list": [1, 2, 3] }, "flag": true },
              "anotherFutureKey": "kept"
            }
        """.trimIndent().toByteArray(),
        "wallpaper.png" to WALLPAPER,
        "icons/catbar_games.png" to ICON_PNG,
        "icons/item_from_the_future.png" to ICON_PNG,
        "sysicons/psx.png" to SYSICON_PNG,
        "sysicons/future_console.png" to SYSICON_PNG,
        "extras/thing.bin" to FUTURE_BLOB,
        "readme.txt" to FUTURE_BLOB,
    )
}
