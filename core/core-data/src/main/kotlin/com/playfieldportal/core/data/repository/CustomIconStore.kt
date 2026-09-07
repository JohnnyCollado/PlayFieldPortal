package com.playfieldportal.core.data.repository

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.ui.icons.CustomIcon
import com.playfieldportal.core.ui.icons.CustomIconLimits
import com.playfieldportal.core.ui.icons.GifFrameProbe
import com.playfieldportal.themekit.CustomizableIcons
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.asImageBitmap

/**
 * The user's per-slot custom icon storage — the user tier of the icon render precedence
 * (`user pick > theme icon > built-in`). The directory is the source of truth, matching how
 * PfpThemeStore handles the extracted `theme-icons/`:
 *
 * ```
 * filesDir/custom-icons/<slotKey>.<png|jpg|webp|gif>
 * ```
 *
 * One file per slot, named by the slot key — deliberately NOT the wallpaper's unique-filename
 * rule. Stills are decoded here rather than by Coil, so no path-keyed cache is involved for
 * them; animated GIFs ARE read by Coil at render time, so [import] evicts the replaced file's
 * path from the image cache via [CustomIconCacheEvictor]. Skip that and the old animation
 * keeps playing — the single easiest bug to ship in this feature.
 *
 * Keys come from [CustomizableIcons] (theme slots plus console slots) and are used verbatim
 * as file names, which makes `isValidKey` load-bearing: it is what stops a crafted key
 * escaping the directory.
 */
@Singleton
class CustomIconStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cacheEvictor: CustomIconCacheEvictor,
) {

    /** Import outcome: [ok] with a null message on success; a user-facing reason on failure. */
    data class ImportResult(val ok: Boolean, val message: String? = null)

    private val dir = File(context.filesDir, CUSTOM_ICONS_DIR)

    /** Extension → MIME for the stored suffixes (jpg normalizes jpeg's report). */
    private val mimeForExtension = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "heif" to "image/heif",
        "gif" to "image/gif",
    )

    /**
     * Imports [uri] as [slotKey]'s icon: reject unknown keys and unaccepted MIME up front,
     * size pre-check via the SAF descriptor, copy capped, validate via
     * [CustomIconLimits], then swap the file in. The slot holds ONE file — a re-import with
     * a different extension removes the old one first, prefs/stamp last.
     */
    suspend fun import(slotKey: String, uri: Uri, mime: String?): ImportResult = withContext(Dispatchers.IO) {
        if (!CustomizableIcons.isValidKey(slotKey)) {
            return@withContext ImportResult(false, "Not a customizable icon slot")
        }
        val ext = mimeToExtension(mime)
        if (ext == null) {
            return@withContext ImportResult(false, CustomIconLimits.MSG_UNSUPPORTED_FORMAT)
        }

        // Size pre-check straight off the descriptor when the provider reports one: a 200 MB
        // pick is rejected without transferring a byte (same gate as the motion wallpaper).
        val knownSize = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it > 0 }
        if (knownSize != null && knownSize > CustomIconLimits.MAX_BYTES) {
            return@withContext ImportResult(false, CustomIconLimits.MSG_TOO_LARGE_BYTES)
        }

        dir.mkdirs()
        val staged = File(dir, "staging_${System.currentTimeMillis()}.$ext")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                // Capped read: the descriptor can lie; this is the real backstop.
                val bytes = with(SafeMedia) { input.readCapped(CustomIconLimits.MAX_BYTES) }
                if (bytes == null) {
                    null
                } else {
                    staged.writeBytes(bytes)
                    bytes.size.toLong()
                }
            }
        }.getOrNull()
        if (copied == null) {
            runCatching { staged.delete() }
            return@withContext ImportResult(false, CustomIconLimits.MSG_UNDECODABLE)
        }

        val rejection = validateImported(staged, ext, copied)
        if (rejection != null) {
            runCatching { staged.delete() }
            return@withContext ImportResult(false, rejection)
        }

        // Commit: remove any existing file for this slot under a DIFFERENT extension, then
        // move the staged file into place, then evict (a GIF read by Coil must not keep
        // serving old bytes), then bump the stamp so observers reload.
        for ((candidateExt, _) in mimeForExtension) {
            if (candidateExt != ext) File(dir, "$slotKey.$candidateExt").delete()
        }
        val dest = File(dir, "$slotKey.$ext")
        if (!staged.renameTo(dest)) {
            // Some filesystems refuse cross-handle renames; fall back to a copy.
            staged.copyTo(dest, overwrite = true)
            staged.delete()
        }
        cacheEvictor.evict(dest.absolutePath)
        context.pfpDataStore.edit { prefs -> prefs[KEY_CUSTOM_ICONS_STAMP] = System.currentTimeMillis() }
        ImportResult(true)
    }

    /**
     * Clears one slot: the file first, then a stamp bump so observers reload — nothing
     * references the file while it's being removed, and no bump without a real removal.
     *
     * Returns whether a pick was actually removed. The caller needs this to tell the user
     * WHY nothing changed: this tier holds only user picks, so clearing a slot the user
     * never picked is a no-op, and a cleared slot can still show the applied theme's icon
     * underneath. Silence there reads as a broken button.
     */
    suspend fun clear(slotKey: String): Boolean = withContext(Dispatchers.IO) {
        if (!CustomizableIcons.isValidKey(slotKey)) return@withContext false
        val removed = mimeForExtension.keys.any { ext -> File(dir, "$slotKey.$ext").delete() }
        if (removed) {
            context.pfpDataStore.edit { prefs -> prefs[KEY_CUSTOM_ICONS_STAMP] = System.currentTimeMillis() }
        }
        removed
    }

    /**
     * Clears every user pick and the stamp. The directory itself is removed.
     *
     * Returns whether there was anything to clear, for the same reason [clear] does — with no
     * picks stored this is a no-op, and the user deserves to be told that rather than left
     * pressing a button that appears dead.
     */
    suspend fun clearAll(): Boolean = withContext(Dispatchers.IO) {
        val had = dir.listFiles { f -> f.isFile }.orEmpty().isNotEmpty()
        context.pfpDataStore.edit { prefs -> prefs.remove(KEY_CUSTOM_ICONS_STAMP) }
        dir.deleteRecursively()
        had
    }

    /**
     * Loads every stored pick as slot key → [CustomIcon], scanning the directory on IO.
     * GIFs that genuinely carry multiple frames load as [CustomIcon.Animated]; everything
     * else — stills AND single-frame GIFs — as [CustomIcon.Still], so no decoder is ever
     * started for them. Invalid slot keys and extensions are skipped, never crashed on.
     */
    suspend fun load(): Map<String, CustomIcon> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.isFile }.orEmpty().mapNotNull { file ->
            val key = file.nameWithoutExtension
            if (!CustomizableIcons.isValidKey(key)) return@mapNotNull null
            val ext = file.extension.lowercase()
            if (ext !in mimeForExtension) return@mapNotNull null
            // Bounds-checked decode: the dir is ours, but the picked file isn't — a 20k×20k
            // "icon" must never reach a pixel allocation (same rule as theme-icons).
            val bitmap = SafeMedia.decodeFileCapped(
                file.absolutePath,
                maxDimension = DECODE_MAX_DIMENSION,
                targetDimension = DECODE_TARGET_DIMENSION,
            ) ?: return@mapNotNull null
            val firstFrame = bitmap.asImageBitmap()
            // Single-frame GIFs load as Still — no decoder is ever started for them.
            val icon = if (ext == "gif" && GifFrameProbe.countFrames(file) > 1) {
                CustomIcon.Animated(path = file.absolutePath, firstFrame = firstFrame)
            } else {
                CustomIcon.Still(firstFrame)
            }
            key to icon
        }.toMap()
    }

    /**
     * Runs the import gate on the staged file. Stills are never rejected for size — the
     * decode step downscales; GIFs are rejected (re-encoding is out of scope). Undecodable
     * files are rejected for both kinds.
     */
    private fun validateImported(file: File, ext: String, bytes: Long): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val probe = CustomIconLimits.Probe(
            mime = mimeForExtension[ext],
            width = bounds.outWidth,
            height = bounds.outHeight,
            // Frame count/duration are not cheaply probeable pre-decode on the JVM; the
            // validate() checks for them are nullable and simply skip. The dimension and
            // byte caps do the guarding.
            frameCount = null,
            durationMs = null,
            bytes = bytes,
        )
        return CustomIconLimits.validate(probe)
    }

    private fun mimeToExtension(mime: String?): String? = when (mime?.lowercase()) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
        "image/heif" -> "heif"
        "image/gif" -> "gif"
        else -> null
    }

    companion object {
        /** User picks live here, one file per slot, named `<slotKey>.<ext>`. */
        const val CUSTOM_ICONS_DIR = "custom-icons"

        /**
         * Present ⇒ user picks exist under [CUSTOM_ICONS_DIR]; the value only bumps so
         * observers reload. Copies PfpThemeStore.KEY_THEME_ICONS_STAMP's contract. Mirrored
         * by feature-backup's long-key list.
         */
        val KEY_CUSTOM_ICONS_STAMP = longPreferencesKey("custom_icons_stamp")

        // Stills downscale toward 512 (the cap GIFs must fit under); the hard ceiling matches
        // SafeMedia's theme-image rule.
        private const val DECODE_MAX_DIMENSION = 8192
        private const val DECODE_TARGET_DIMENSION = 512
    }
}
