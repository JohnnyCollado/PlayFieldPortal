package com.playfieldportal.core.data.repository

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.datastore.preferences.core.edit
import com.playfieldportal.core.data.datastore.pfpDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device's lock screen image. Android lets an app set the lock screen wallpaper on its own
 * (WallpaperManager, FLAG_LOCK) with the install-time SET_WALLPAPER permission. The home screen is
 * never touched — the launcher draws its own background.
 *
 * A still only: the platform's lock wallpaper is a bitmap, and an animated one would mean a live
 * wallpaper service the user must pick in the system chooser, applying to home and lock alike.
 * Animated backgrounds contribute their poster instead.
 *
 * The image is center-cropped to the screen's shape, kept as a JPEG in its own [DIR] (so backups
 * carry it and wallpaper housekeeping never sweeps it), and remembered with who set it
 * ([Source]): Settings shows it, and a theme reset undoes only a lock screen a theme set.
 */
@Singleton
class LockScreenImage(
    private val context: Context,
    private val system: System,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, AndroidSystem(context))

    enum class Source(val key: String) { USER("user"), THEME("theme") }

    data class State(val path: String? = null, val source: Source? = null)

    sealed interface Result {
        data object Set : Result
        data class Failed(val reason: String) : Result
    }

    data class Crop(val left: Int, val top: Int, val width: Int, val height: Int)

    /** The platform seam: the real one is [AndroidSystem]; tests record calls instead. */
    interface System {
        val screenWidth: Int
        val screenHeight: Int
        fun setLock(bitmap: Bitmap): Boolean
        fun clearLock(): Boolean
    }

    val state: Flow<State> = context.pfpDataStore.data.map { prefs ->
        val path = prefs[ThemePrefKeys.LOCKSCREEN_IMAGE]
        val source = path?.let {
            Source.entries.firstOrNull { s -> s.key == prefs[ThemePrefKeys.LOCKSCREEN_SOURCE] } ?: Source.USER
        }
        State(path, source)
    }.distinctUntilChanged()

    /** Sets encoded image [bytes] as the lock screen. Nothing changes unless the system accepts it. */
    suspend fun set(bytes: ByteArray, source: Source): Result = withContext(Dispatchers.IO) {
        val target = maxOf(system.screenWidth, system.screenHeight).takeIf { it > 0 } ?: MAX_EDGE
        val decoded = SafeMedia.decodeBitmapCapped(bytes, targetDimension = target)
            ?: return@withContext Result.Failed(UNREADABLE)
        val crop = centerCrop(decoded.width, decoded.height, system.screenWidth, system.screenHeight)
        val cropped = runCatching {
            Bitmap.createBitmap(decoded, crop.left, crop.top, crop.width, crop.height)
        }.getOrNull() ?: return@withContext Result.Failed(UNREADABLE)
        if (!runCatching { system.setLock(cropped) }.getOrDefault(false)) {
            return@withContext Result.Failed("This device did not accept a lock screen image")
        }

        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val file = File(dir, "lockscreen_${java.lang.System.currentTimeMillis()}.jpg")
        runCatching { file.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
            .onFailure { Timber.w(it, "Couldn't keep the lock screen image") }
        dir.listFiles()?.filter { it != file }?.forEach { it.delete() }
        context.pfpDataStore.edit {
            it[ThemePrefKeys.LOCKSCREEN_IMAGE] = file.absolutePath
            it[ThemePrefKeys.LOCKSCREEN_SOURCE] = source.key
        }
        Result.Set
    }

    /** [set] from a file on disk (the launcher's own wallpaper poster, a photo, a theme's image). */
    suspend fun setFromFile(path: String, source: Source): Result = withContext(Dispatchers.IO) {
        val bytes = runCatching { File(path).readBytes() }.getOrNull()
            ?: return@withContext Result.Failed(UNREADABLE)
        set(bytes, source)
    }

    /** Puts the system lock screen back to its default and forgets the image. */
    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        runCatching { system.clearLock() }.onFailure { Timber.w(it, "Couldn't reset the lock screen") }
        File(context.filesDir, DIR).listFiles()?.forEach { it.delete() }
        context.pfpDataStore.edit {
            it.remove(ThemePrefKeys.LOCKSCREEN_IMAGE)
            it.remove(ThemePrefKeys.LOCKSCREEN_SOURCE)
        }
    }

    /** A theme reset: undo the lock screen only when a theme set it, never the user's own. */
    suspend fun clearIfFromTheme() {
        if (context.pfpDataStore.data.first()[ThemePrefKeys.LOCKSCREEN_SOURCE] == Source.THEME.key) clear()
    }

    private class AndroidSystem(private val context: Context) : System {
        private val size: Pair<Int, Int> by lazy {
            val wm = context.getSystemService(WindowManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
                wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm?.defaultDisplay?.getRealMetrics(metrics)
                metrics.widthPixels to metrics.heightPixels
            }
        }
        override val screenWidth: Int get() = size.first
        override val screenHeight: Int get() = size.second

        override fun setLock(bitmap: Bitmap): Boolean =
            WallpaperManager.getInstance(context).setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK) != 0

        override fun clearLock(): Boolean {
            WallpaperManager.getInstance(context).clear(WallpaperManager.FLAG_LOCK)
            return true
        }
    }

    companion object {
        /** Its own folder under filesDir: carried by backups, never swept with the wallpaper folder. */
        const val DIR = "lockscreen"
        private const val MAX_EDGE = 2560
        private const val UNREADABLE = "That image could not be read"

        /** The centred region of a [srcW]×[srcH] image with the screen's shape; the whole image when the screen is unknown. */
        fun centerCrop(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Crop {
            if (dstW <= 0 || dstH <= 0 || srcW <= 0 || srcH <= 0) return Crop(0, 0, srcW, srcH)
            return if (srcW.toLong() * dstH > srcH.toLong() * dstW) {
                val w = (srcH.toLong() * dstW / dstH).toInt().coerceIn(1, srcW)
                Crop((srcW - w) / 2, 0, w, srcH)
            } else {
                val h = (srcW.toLong() * dstH / dstW).toInt().coerceIn(1, srcH)
                Crop(0, (srcH - h) / 2, srcW, h)
            }
        }
    }
}
