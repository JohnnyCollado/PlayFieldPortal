package com.playfieldportal.feature.xmb.ui.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.Photo
import com.playfieldportal.core.domain.repository.PhotoRepository
import com.playfieldportal.themekit.MotionLimits
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

// Same key Display settings and XMBViewModel use — the XMB re-renders the background reactively.
private val KEY_CUSTOM_WALLPAPER = stringPreferencesKey("display_custom_wallpaper")
// Must match DisplaySettingsViewModel — written as a pair with the poster when an animated
// source is applied, cleared when a still replaces it.
private val KEY_MOTION_WALLPAPER = stringPreferencesKey("display_motion_wallpaper")

// Header bytes enough to walk a WebP's top-level chunks in practice (metadata chunks can push
// ANMF deep; the walk simply fails closed to "still" if the cap is hit).
private const val WEBP_HEADER_BYTES = 256 * 1024

// Zoom limits and the step applied by Zoom In / Zoom Out.
private const val ZOOM_MIN = 1f
private const val ZOOM_MAX = 8f
private const val ZOOM_STEP = 1.5f
// Fraction of the (zoomed) view panned per D-pad/stick step.
private const val PAN_STEP_PX = 160f
// Longest edge of the saved wallpaper file — plenty for any launcher background.
private const val WALLPAPER_MAX_DIM = 2560

// A row in the viewer's Options menu.
enum class PhotoViewerAction(val label: String) {
    SET_WALLPAPER("Set as Launcher Wallpaper"),
    ROTATE_LEFT("Rotate Left"),
    ROTATE_RIGHT("Rotate Right"),
    ZOOM_IN("Zoom In"),
    ZOOM_OUT("Zoom Out"),
    RESET_ZOOM("Reset Zoom"),
    INFO("View Information"),
    LOCATION("Open File Location"),
    REMOVE("Remove From Library"),
}

data class PhotoViewerUiState(
    val photos: List<Photo> = emptyList(),
    val index: Int = 0,
    val isLoading: Boolean = true,
    // Minimal PSP-style UI: everything hidden until A toggles it.
    val controlsVisible: Boolean = false,
    val showOptions: Boolean = false,
    val optionsIndex: Int = 0,
    // Per-photo view transform; reset when the photo changes.
    val zoom: Float = ZOOM_MIN,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val rotationDegrees: Int = 0,
    val infoVisible: Boolean = false,
    val confirmRemove: Boolean = false,
    // Wallpaper flow: fullscreen preview first, then apply on confirm.
    val wallpaperPreviewVisible: Boolean = false,
    val applyingWallpaper: Boolean = false,
    val actionMessage: String? = null,
    val closed: Boolean = false,
) {
    val photo: Photo? get() = photos.getOrNull(index)
    val zoomed: Boolean get() = zoom > ZOOM_MIN
}

@HiltViewModel
class PhotoViewerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoRepository: PhotoRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PhotoViewerUiState())
    val uiState: StateFlow<PhotoViewerUiState> = _uiState.asStateFlow()

    /**
     * Loads the sibling list the photo was opened from (null libraryId = All Photos). With
     * [openWallpaperPreview] the viewer opens straight into the wallpaper preview — used by the
     * list row's "Set as Launcher Wallpaper" — still requiring an explicit Apply.
     */
    fun load(photoId: String, libraryId: String?, openWallpaperPreview: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { PhotoViewerUiState(isLoading = true) }
            val photos = if (libraryId != null) {
                photoRepository.getPhotosForLibrary(libraryId)
            } else {
                photoRepository.getLibraries().flatMap { photoRepository.getPhotosForLibrary(it.id) }
            }.sortedBy { it.displayName.lowercase() }
            val index = photos.indexOfFirst { it.id == photoId }.coerceAtLeast(0)
            _uiState.update {
                it.copy(
                    photos = photos,
                    index = index,
                    isLoading = false,
                    wallpaperPreviewVisible = openWallpaperPreview && photos.isNotEmpty(),
                )
            }
        }
    }

    // ── Controller input ──────────────────────────────────────────────────────
    // A = toggle controls / confirm · B = back · Y = options · L1/R1 = previous/next photo ·
    // D-pad / left stick = pan when zoomed, previous/next when not.
    fun handleGamepadAction(action: GamepadAction) {
        val s = _uiState.value
        when {
            s.applyingWallpaper -> Unit   // brief; swallow input so a double-tap can't re-apply
            s.wallpaperPreviewVisible -> when (action) {
                GamepadAction.SELECT -> applyWallpaper()
                GamepadAction.BACK   -> _uiState.update { it.copy(wallpaperPreviewVisible = false) }
                else -> Unit
            }
            s.confirmRemove -> when (action) {
                GamepadAction.SELECT -> confirmRemove()
                GamepadAction.BACK   -> _uiState.update { it.copy(confirmRemove = false) }
                else -> Unit
            }
            s.infoVisible -> if (action == GamepadAction.SELECT || action == GamepadAction.BACK) {
                _uiState.update { it.copy(infoVisible = false) }
            }
            s.showOptions -> {
                val count = PhotoViewerAction.entries.size
                when (action) {
                    GamepadAction.NAVIGATE_UP   -> _uiState.update { it.copy(optionsIndex = (it.optionsIndex - 1 + count) % count) }
                    GamepadAction.NAVIGATE_DOWN -> _uiState.update { it.copy(optionsIndex = (it.optionsIndex + 1) % count) }
                    GamepadAction.SELECT        -> activate(PhotoViewerAction.entries[s.optionsIndex.coerceIn(0, count - 1)])
                    GamepadAction.BACK,
                    GamepadAction.OPEN_CONTEXT_MENU      -> _uiState.update { it.copy(showOptions = false) }
                    else -> Unit
                }
            }
            else -> when (action) {
                GamepadAction.SELECT        -> _uiState.update { it.copy(controlsVisible = !it.controlsVisible) }
                GamepadAction.BACK          -> _uiState.update { it.copy(closed = true) }
                GamepadAction.OPEN_CONTEXT_MENU    -> openOptions()
                GamepadAction.PREV_CATEGORY -> step(-1)
                GamepadAction.NEXT_CATEGORY -> step(+1)
                GamepadAction.NAVIGATE_LEFT  -> if (s.zoomed) pan(+PAN_STEP_PX, 0f) else step(-1)
                GamepadAction.NAVIGATE_RIGHT -> if (s.zoomed) pan(-PAN_STEP_PX, 0f) else step(+1)
                GamepadAction.NAVIGATE_UP    -> if (s.zoomed) pan(0f, +PAN_STEP_PX)
                GamepadAction.NAVIGATE_DOWN  -> if (s.zoomed) pan(0f, -PAN_STEP_PX)
                else -> Unit
            }
        }
    }

    fun toggleControls() = _uiState.update { it.copy(controlsVisible = !it.controlsVisible) }
    fun openOptions() = _uiState.update { it.copy(showOptions = true, optionsIndex = 0) }
    fun closeOptions() = _uiState.update { it.copy(showOptions = false) }
    fun onClosedHandled() = _uiState.update { it.copy(closed = false) }
    fun dismissMessage() = _uiState.update { it.copy(actionMessage = null) }

    /** Moves to the previous/next photo, resetting the per-photo view transform. */
    fun step(direction: Int) {
        _uiState.update {
            val next = (it.index + direction).coerceIn(0, (it.photos.size - 1).coerceAtLeast(0))
            if (next == it.index) it
            else it.copy(index = next, zoom = ZOOM_MIN, panX = 0f, panY = 0f, rotationDegrees = 0)
        }
    }

    fun activate(action: PhotoViewerAction) {
        _uiState.update { it.copy(showOptions = false) }
        when (action) {
            PhotoViewerAction.SET_WALLPAPER -> _uiState.update { it.copy(wallpaperPreviewVisible = true) }
            PhotoViewerAction.ROTATE_LEFT   -> _uiState.update { it.copy(rotationDegrees = (it.rotationDegrees + 270) % 360) }
            PhotoViewerAction.ROTATE_RIGHT  -> _uiState.update { it.copy(rotationDegrees = (it.rotationDegrees + 90) % 360) }
            PhotoViewerAction.ZOOM_IN       -> zoomBy(ZOOM_STEP)
            PhotoViewerAction.ZOOM_OUT      -> zoomBy(1f / ZOOM_STEP)
            PhotoViewerAction.RESET_ZOOM    -> _uiState.update { it.copy(zoom = ZOOM_MIN, panX = 0f, panY = 0f) }
            PhotoViewerAction.INFO          -> _uiState.update { it.copy(infoVisible = true) }
            PhotoViewerAction.LOCATION      -> {
                val p = _uiState.value.photo
                showMessage(p?.relativePath?.let { "In: $it" } ?: p?.displayName ?: "")
            }
            PhotoViewerAction.REMOVE        -> _uiState.update { it.copy(confirmRemove = true) }
        }
    }

    private fun zoomBy(factor: Float) {
        _uiState.update {
            val zoom = (it.zoom * factor).coerceIn(ZOOM_MIN, ZOOM_MAX)
            if (zoom <= ZOOM_MIN) it.copy(zoom = ZOOM_MIN, panX = 0f, panY = 0f)
            else it.copy(zoom = zoom, panX = clampPan(it.panX, zoom), panY = clampPan(it.panY, zoom))
        }
    }

    /** Touch pinch/drag support — same clamping as the D-pad path. */
    fun onGesture(zoomChange: Float, panChangeX: Float, panChangeY: Float) {
        _uiState.update {
            val zoom = (it.zoom * zoomChange).coerceIn(ZOOM_MIN, ZOOM_MAX)
            if (zoom <= ZOOM_MIN) it.copy(zoom = ZOOM_MIN, panX = 0f, panY = 0f)
            else it.copy(
                zoom = zoom,
                panX = clampPan(it.panX + panChangeX, zoom),
                panY = clampPan(it.panY + panChangeY, zoom),
            )
        }
    }

    private fun pan(dx: Float, dy: Float) {
        _uiState.update {
            it.copy(panX = clampPan(it.panX + dx, it.zoom), panY = clampPan(it.panY + dy, it.zoom))
        }
    }

    // Rough pan bound: half the zoomed overflow of a ~1080p-class viewport. Exact fit-size math
    // isn't worth the complexity — this keeps the image from being flung entirely off screen.
    private fun clampPan(value: Float, zoom: Float): Float {
        val limit = (zoom - 1f) * 1200f
        return value.coerceIn(-limit, limit)
    }

    // ── Wallpaper ─────────────────────────────────────────────────────────────
    // Copies the photo (with the viewer's rotation applied) into app-internal storage and points
    // the existing display_custom_wallpaper preference at it — the same mechanism as Display
    // settings, so the XMB background updates reactively and the original file is never needed
    // again. Unique filenames keep Coil's path-keyed cache from showing a stale wallpaper.
    //
    // Animated sources (GIF / animated WebP) are NOT flattened: without a user rotation they
    // route through the same motion-wallpaper contract the Display-settings importer uses —
    // file copied verbatim for looping playback plus a poster still for the freeze paths, both
    // prefs keys written as a pair. A user-applied rotation is WYSIWYG (the preview showed a
    // rotated still), so it deliberately flattens to that rotated still.
    private fun applyWallpaper() {
        val photo = _uiState.value.photo ?: return
        val rotation = _uiState.value.rotationDegrees
        _uiState.update { it.copy(applyingWallpaper = true) }
        viewModelScope.launch {
            val imported = withContext(Dispatchers.IO) { importWallpaper(photo, rotation) }
            if (imported == null) {
                _uiState.update {
                    it.copy(applyingWallpaper = false, actionMessage = "Could not set wallpaper — the image couldn't be read")
                }
                return@launch
            }
            val (poster, motion) = imported
            // Both keys together, matching Display settings: motion is never set without its
            // poster (the freeze/failure fallback), and a still import clears any previous
            // motion file so no orphaned video/GIF survives the replacement.
            context.pfpDataStore.edit {
                it[KEY_CUSTOM_WALLPAPER] = poster.absolutePath
                if (motion != null) it[KEY_MOTION_WALLPAPER] = motion.absolutePath
                else it.remove(KEY_MOTION_WALLPAPER)
            }
            val keepNames = listOfNotNull(poster, motion).map { it.name }.toSet()
            withContext(Dispatchers.IO) {
                poster.parentFile?.listFiles()?.forEach { if (it.name !in keepNames) it.delete() }
            }
            _uiState.update {
                it.copy(applyingWallpaper = false, wallpaperPreviewVisible = false, actionMessage = "Wallpaper applied")
            }
        }
    }

    /** (posterFile, motionFile?) — motionFile non-null only for an animated source. */
    private fun importWallpaper(photo: Photo, rotationDegrees: Int): Pair<File, File?>? = runCatching {
        val uri = Uri.parse(photo.uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        // Animated container with no user rotation → motion path (see applyWallpaper's comment).
        // bounds.outMimeType covers photos whose scan didn't record a MIME type. WebP needs an
        // animation check ([isAnimatedWebpHeader]): a STILL WebP must keep the still path (and
        // its downsampling) — routing it through the motion gate would reject photos above the
        // 1080p wallpaper cap that the old path handled fine.
        val mime = photo.mimeType ?: bounds.outMimeType
        val animated = when (mime) {
            "image/gif" -> true
            "image/webp" -> readHeader(context, uri)?.let(::isAnimatedWebpHeader) == true
            else -> false
        }
        if (animated && rotationDegrees % 360 == 0) {
            return@runCatching importAnimatedWallpaper(photo, mime!!)
        }

        val opts = BitmapFactory.Options().apply {
            var sample = 1
            var longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / 2 >= WALLPAPER_MAX_DIM) { longest /= 2; sample *= 2 }
            inSampleSize = sample
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return@runCatching null

        val bitmap = if (rotationDegrees % 360 != 0) {
            val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
                .also { if (it != decoded) decoded.recycle() }
        } else decoded

        val dir = File(context.filesDir, "wallpaper").apply { mkdirs() }
        val dest = File(dir, "wallpaper_${System.currentTimeMillis()}.jpg")
        FileOutputStream(dest).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out) }
        bitmap.recycle()
        dest.takeIf { it.length() > 0 }?.let { Pair(it, null) }
    }.getOrElse { Timber.w(it, "Wallpaper import failed for ${photo.uri}"); null }

    /**
     * GIF / animated WebP: copy the file verbatim — its animation IS the wallpaper — and extract
     * a poster still for the freeze paths. Same pair-write + import-gate contract as the
     * Display-settings motion importer ([MotionLimits]), so a wallpaper set from either
     * entry point behaves identically behind the XMB. Null on any gate rejection or decode
     * failure; the caller's generic message covers all of them.
     */
    private fun importAnimatedWallpaper(photo: Photo, mime: String): Pair<File, File?>? {
        val dir = File(context.filesDir, "wallpaper").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val motionDest = File(dir, "wallpaper_$stamp.${mime.substringAfter('/').lowercase()}")
        val posterDest = File(dir, "wallpaper_$stamp.jpg")

        // Size pre-check straight off the descriptor when the provider reports one: a huge pick
        // is rejected without transferring a byte.
        val knownSize = runCatching {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(photo.uri), "r")?.use { it.length }
        }.getOrNull()?.takeIf { it > 0 }
        if (knownSize != null && knownSize > MotionLimits.MAX_BYTES) return null

        val copied = runCatching {
            context.contentResolver.openInputStream(Uri.parse(photo.uri))?.use { input ->
                motionDest.outputStream().use { out -> input.copyTo(out) }
            } != null
        }.getOrDefault(false)
        if (!copied) {
            runCatching { motionDest.delete() }
            return null
        }

        // The import gate, on the copied bytes (authoritative size even when the provider hid it).
        val probe = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(motionDest.absolutePath, bounds)
            MotionLimits.Probe(
                mime = mime,
                width = bounds.outWidth,
                height = bounds.outHeight,
                durationMs = 0L,
                bytes = motionDest.length(),
            )
        }.getOrNull()
        val rejection = probe?.let { MotionLimits.validate(it) }
        if (probe == null || rejection != null) {
            runCatching { motionDest.delete() }
            return null
        }

        // Poster: first frame through ImageDecoder (API 29+ = minSdk), scaled to ~1080p — the
        // exact recipe the Display-settings importer uses, so the pairs are interchangeable.
        val poster = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(motionDest)) { decoder, info, _ ->
                decoder.setTargetSampleSize(
                    maxOf(1, maxOf(info.size.width, info.size.height) / MotionLimits.MAX_HEIGHT),
                )
            }
        }.getOrNull()
        if (poster == null) {
            runCatching { motionDest.delete() }
            return null
        }
        val posterOk = runCatching {
            FileOutputStream(posterDest).use { poster.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            true
        }.getOrDefault(false)
        poster.recycle()
        if (!posterOk) {
            runCatching { motionDest.delete() }
            return null
        }
        return Pair(posterDest, motionDest)
    }

    fun confirmWallpaper() = applyWallpaper()
    fun cancelWallpaperPreview() = _uiState.update { it.copy(wallpaperPreviewVisible = false) }

    // ── Remove ────────────────────────────────────────────────────────────────

    fun requestRemove() = _uiState.update { it.copy(confirmRemove = true) }
    fun cancelRemove() = _uiState.update { it.copy(confirmRemove = false) }

    fun confirmRemove() {
        val s = _uiState.value
        val photo = s.photo ?: return
        viewModelScope.launch {
            photoRepository.removePhoto(photo.id)
            val remaining = s.photos.filterNot { it.id == photo.id }
            if (remaining.isEmpty()) {
                _uiState.update { it.copy(confirmRemove = false, closed = true) }
            } else {
                _uiState.update {
                    it.copy(
                        confirmRemove = false,
                        photos = remaining,
                        index = s.index.coerceAtMost(remaining.size - 1),
                        zoom = ZOOM_MIN, panX = 0f, panY = 0f, rotationDegrees = 0,
                    )
                }
            }
        }
    }

    private fun showMessage(msg: String) = _uiState.update { it.copy(actionMessage = msg) }
}

/**
 * Reads up to [WEBP_HEADER_BYTES] of the source document. Private to this file so the
 * animation sniff and its cap stay beside the importer that consumes them.
 */
private fun readHeader(context: Context, uri: Uri): ByteArray? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val head = ByteArray(WEBP_HEADER_BYTES)
        var read = 0
        while (read < head.size) {
            val n = input.read(head, read, head.size - read)
            if (n < 0) break
            read += n
        }
        head.copyOf(read)
    }
}.getOrNull()

/**
 * Walks a WebP container's top-level chunks looking for ANMF (aNiMated FraMe) — the marker that
 * the file is an animated WebP rather than a still one. File layout: "RIFF" + u32 LE size +
 * "WEBP", then a flat chunk sequence (fourcc + u32 LE payload size, padded to even). Returns
 * false for a still WebP, any other format, or an unreadable header — everything the still path
 * already handles.
 */
private fun isAnimatedWebpHeader(header: ByteArray): Boolean {
    if (header.size < 12 || !header.regionMatchesAscii(0, "RIFF") || !header.regionMatchesAscii(8, "WEBP")) return false
    var off = 12
    while (off + 8 <= header.size) {
        when {
            header.regionMatchesAscii(off, "ANMF") -> return true
            else -> {
                val size = (header[off + 4].toInt() and 0xFF) or
                    ((header[off + 5].toInt() and 0xFF) shl 8) or
                    ((header[off + 6].toInt() and 0xFF) shl 16) or
                    ((header[off + 7].toInt() and 0xFF) shl 24)
                off += 8 + size + (size and 1)
            }
        }
    }
    return false
}

private fun ByteArray.regionMatchesAscii(offset: Int, prefix: String): Boolean {
    if (offset < 0 || offset + prefix.length > size) return false
    for (i in prefix.indices) {
        if (this[offset + i].toInt() != prefix[i].code) return false
    }
    return true
}
