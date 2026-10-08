package com.playfieldportal.core.data.repository

import android.content.Context
import androidx.compose.ui.graphics.asImageBitmap
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.core.ui.icons.CustomIcon
import com.playfieldportal.core.ui.icons.GifFrameProbe
import com.playfieldportal.core.ui.icons.UserCategoryIconKeys
import com.playfieldportal.themekit.CustomizableIcons
import com.playfieldportal.themekit.PtfIcons
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The two tiers of themeable assets on disk, and the one rule between them: **the user's own pick
 * beats the applied theme's, which beats the built-in.**
 *
 * ```
 * icons  filesDir/custom-icons/<key>.<ext>   (user)  over  filesDir/theme-icons/<key>.<ext>   (theme)
 * media  filesDir/ui-media/<slot>.<ext>      (user)  over  filesDir/theme-media/<slot>.<ext>  (theme)
 * ```
 *
 * Everything that asks "which file wins", "what does the theme supply", "what does the user's own
 * choice hide" or "empty this tier" asks here, so both tiers are always read with the same file
 * rules: one extension set and one key gate per kind, and one icon decode. The stores still own
 * writing their tier (imports, a theme apply) and the stamps that tell observers to reload.
 */
@Singleton
class ThemeTiers internal constructor(private val filesDir: File) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(context.filesDir)

    enum class Tier { USER, THEME }

    // ── Icons ────────────────────────────────────────────────────────────────

    fun iconDir(tier: Tier): File = File(
        filesDir,
        when (tier) {
            Tier.USER -> CustomIconStore.CUSTOM_ICONS_DIR
            Tier.THEME -> PfpThemeStore.THEME_ICONS_DIR
        },
    )

    /** [key]'s stored icon in [tier], under any accepted extension, or null. */
    fun iconFile(tier: Tier, key: String): File? {
        if (!isIconKey(key)) return null
        val dir = iconDir(tier)
        return ICON_EXTENSIONS.asSequence().map { File(dir, "$key.$it") }.firstOrNull { it.isFile }
    }

    /** The file that draws for [key]: the user's, else the theme's, else null (the built-in). */
    fun resolveIconFile(key: String): File? = iconFile(Tier.USER, key) ?: iconFile(Tier.THEME, key)

    /** The keys [tier] holds an icon for. */
    fun iconKeys(tier: Tier): Set<String> = iconFiles(tier).mapTo(HashSet()) { it.nameWithoutExtension }

    /** Theme icons the user's own picks hide: keys both tiers hold. */
    fun shadowedIcons(): Set<String> = iconKeys(Tier.THEME) intersect iconKeys(Tier.USER)

    /**
     * Every icon in [tier] as key → [CustomIcon], decoded on IO. GIFs that genuinely carry several
     * frames load as [CustomIcon.Animated]; stills and single-frame GIFs as [CustomIcon.Still], so
     * no decoder is ever started for them. Stray files and unregistered keys are skipped.
     */
    suspend fun loadIcons(tier: Tier): Map<String, CustomIcon> = withContext(Dispatchers.IO) {
        iconFiles(tier).mapNotNull { file ->
            // Bounds-checked decode: the dir is ours, but neither a picked file nor a bundle author
            // is — a 20k×20k "icon" must never reach a pixel allocation.
            val bitmap = SafeMedia.decodeFileCapped(
                file.absolutePath,
                maxDimension = ICON_DECODE_MAX_DIMENSION,
                targetDimension = ICON_DECODE_TARGET_DIMENSION,
            ) ?: return@mapNotNull null
            val firstFrame = bitmap.asImageBitmap()
            val icon = if (file.extension.equals("gif", ignoreCase = true) && GifFrameProbe.countFrames(file) > 1) {
                CustomIcon.Animated(path = file.absolutePath, firstFrame = firstFrame)
            } else {
                CustomIcon.Still(firstFrame)
            }
            file.nameWithoutExtension to icon
        }.toMap()
    }

    /**
     * The extra PSP body images the applied theme carries, by record: `theme-icons/ptficons/<group>_<index>.png`
     * (written by a theme apply, wiped with the theme tier's icons). Names outside the canonical
     * body format, and other extensions, are ignored. Ordered by group, then index.
     */
    fun ptfIconFiles(): Map<PtfIcons.SlotRef, File> {
        val dir = File(iconDir(Tier.THEME), PTF_ICONS_SUBDIR)
        // Numeric (group, index) order: by file name "2_10" would sort before "2_5".
        return dir.listFiles { f -> f.isFile }.orEmpty()
            .filter { it.extension == PTF_ICON_EXTENSION }
            .mapNotNull { file -> PtfIcons.refForStem(file.nameWithoutExtension)?.let { it to file } }
            .sortedWith(compareBy({ it.first.group }, { it.first.index }))
            .toMap()
    }

    /** [ptfIconFiles] decoded on IO with the same bounded decode as [loadIcons]; always stills. */
    suspend fun loadPtfIcons(): Map<PtfIcons.SlotRef, CustomIcon> = withContext(Dispatchers.IO) {
        ptfIconFiles().mapNotNull { (ref, file) ->
            val bitmap = SafeMedia.decodeFileCapped(
                file.absolutePath,
                maxDimension = ICON_DECODE_MAX_DIMENSION,
                targetDimension = ICON_DECODE_TARGET_DIMENSION,
            ) ?: return@mapNotNull null
            ref to CustomIcon.Still(bitmap.asImageBitmap())
        }.toMap()
    }

    /** Empties [tier]'s icons (and, for the theme tier, its `ptficons/` extras). True when there was anything to remove. */
    fun clearIcons(tier: Tier): Boolean {
        val dir = iconDir(tier)
        val had = dir.listFiles { f -> f.isFile }.orEmpty().isNotEmpty()
        dir.deleteRecursively()
        return had
    }

    private fun iconFiles(tier: Tier): List<File> =
        iconDir(tier).listFiles { f -> f.isFile }.orEmpty()
            .filter { it.extension.lowercase() in ICON_EXTENSIONS && isIconKey(it.nameWithoutExtension) }

    // ── Media ────────────────────────────────────────────────────────────────

    fun mediaDir(tier: Tier): File = File(
        filesDir,
        when (tier) {
            Tier.USER -> UiMediaStore.UI_MEDIA_DIR
            Tier.THEME -> PfpThemeStore.THEME_MEDIA_DIR
        },
    )

    /** [key]'s stored file in [tier] (a [UiMediaSlot] key, the same keys ThemeMediaSlots uses), or null. */
    fun mediaFile(tier: Tier, key: String): File? {
        if (!UiMediaSlot.isValidKey(key)) return null
        val dir = mediaDir(tier)
        return MEDIA_EXTENSIONS.asSequence().map { File(dir, "$key.$it") }.firstOrNull { it.isFile }
    }

    /** The file that plays for [slot]: the user's, else the theme's, else null (the built-in). */
    fun resolveMedia(slot: UiMediaSlot): File? = mediaFile(Tier.USER, slot.key) ?: mediaFile(Tier.THEME, slot.key)

    /** The slots [tier] holds a file for. */
    fun mediaSlots(tier: Tier): Set<UiMediaSlot> =
        mediaDir(tier).listFiles { f -> f.isFile }.orEmpty()
            .filter { it.extension.lowercase() in MEDIA_EXTENSIONS }
            .mapNotNullTo(HashSet()) { UiMediaSlot.fromKey(it.nameWithoutExtension) }

    /** Theme media the user's own assignments hide: slots both tiers hold. */
    fun shadowedMedia(): Set<UiMediaSlot> = mediaSlots(Tier.THEME) intersect mediaSlots(Tier.USER)

    /** Empties [tier]'s media. True when there was anything to remove. */
    fun clearMedia(tier: Tier): Boolean {
        val dir = mediaDir(tier)
        val had = dir.listFiles { f -> f.isFile }.orEmpty().isNotEmpty()
        dir.deleteRecursively()
        return had
    }

    companion object {
        /** Folder inside the theme icon dir holding the theme's extra PSP body images. */
        const val PTF_ICONS_SUBDIR = "ptficons"
        private const val PTF_ICON_EXTENSION = "png"

        /**
         * Icon files either tier stores. The user tier writes the extension of the picked file's
         * validated MIME; a theme writes png and gif.
         */
        val ICON_EXTENSIONS: Set<String> = linkedSetOf("png", "jpg", "webp", "bmp", "heif", "gif")

        /** Media files either tier stores: the containers UiMediaLimits accepts. */
        val MEDIA_EXTENSIONS: Set<String> = linkedSetOf("mp3", "wav", "ogg", "m4a", "mp4", "webm")

        // Stills downscale toward 512 (the cap GIFs must fit under); the hard ceiling matches
        // SafeMedia's theme-image rule. The same for both tiers.
        private const val ICON_DECODE_MAX_DIMENSION = 8192
        private const val ICON_DECODE_TARGET_DIMENSION = 512

        /** A key either tier may store an icon under: a themeable slot, or a user category image. */
        fun isIconKey(key: String): Boolean =
            CustomizableIcons.isValidKey(key) || UserCategoryIconKeys.isValidKey(key)
    }
}
