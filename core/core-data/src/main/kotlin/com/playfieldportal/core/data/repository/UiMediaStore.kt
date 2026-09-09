package com.playfieldportal.core.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.domain.model.UiMediaKind
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.core.ui.media.UiMediaPaths
import com.playfieldportal.themekit.UiMediaLimits
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The user's per-slot UI-media storage — custom menu sounds, boot video/audio, and GameBoot
 * video/audio. Structure copied from [CustomIconStore], which already solves the four problems
 * this store inherits:
 *
 *  • **Staged import** — the picked file lands as `staging_<millis>.<ext>` and only `renameTo`s
 *    into place after the gate passes, so a rejected pick structurally cannot disturb a working
 *    assignment (the design doc's "failed replacement rule").
 *  • **The descriptor can lie** — the SAF descriptor's length is only a cheap pre-check; the
 *    capped read is the real backstop against a provider that reports 2 KB and delivers 2 GB.
 *  • **Path-escape guard** — slot keys are used verbatim as file names, so [UiMediaSlot.isValidKey]
 *    is what stops a crafted key leaving `ui-media/`.
 *  • **Stamp-bump-to-invalidate** — `ui_media_stamp` bumps on every commit/clear; MenuSoundPlayer
 *    observes it to reload its samples. Without it a replaced sound keeps playing the old sample
 *    for the process's lifetime.
 *
 * The directory is the source of truth — `filesDir/ui-media/<slotKey>.<ext>`, one file per slot,
 * extension derived from the VALIDATED MIME so the suffix names the container by construction.
 * No DataStore key per slot: the file's presence IS the assignment (matches `custom-icons`,
 * deliberately unlike the wallpaper, which needs a prefs path because two files form a pair).
 *     * The user's original picked file is streamed into our own private storage and the content URI
     * is discarded — a URI grant can be revoked at any time, and a `SoundPool` sample (loaded once,
     * held for the app's lifetime) or a boot-time ExoPlayer must never depend on one.
 */
@Singleton
class UiMediaStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : UiMediaPaths {

    /** Import outcome: [ok] with a null message on success; a user-facing reason on failure. */
    data class ImportResult(val ok: Boolean, val message: String? = null)

    private val dir = File(context.filesDir, UI_MEDIA_DIR)

    // ── UiMediaPaths (the core-ui seam) ──────────────────────────────────────

    /** Absolute path of the user's file for [slot], or null when the slot is on the default. */
    override fun pathFor(slot: UiMediaSlot): String? {
        if (!UiMediaSlot.isValidKey(slot.key)) return null
        return dir.listFiles { f -> f.isFile }
            ?.firstOrNull { it.nameWithoutExtension == slot.key }
            ?.absolutePath
    }

    /** Bumps on every import/clear so observers (MenuSoundPlayer, overlays) reload. */
    override val stamp: Flow<Long> = context.pfpDataStore.data.map { prefs ->
        prefs[KEY_UI_MEDIA_STAMP] ?: 0L
    }

    override val menuSoundsEnabled: Flow<Boolean> = context.pfpDataStore.data.map { prefs ->
        prefs[KEY_MENU_SOUNDS_ENABLED] ?: true
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    /**
     * Every assigned slot → its file's absolute path. Cheap: one directory listing. Powers the
     * settings screens' value rows.
     */
    fun assignments(): Map<UiMediaSlot, String> {
        val files = dir.listFiles { f -> f.isFile } ?: return emptyMap()
        val out = HashMap<UiMediaSlot, String>()
        for (file in files) {
            val slot = UiMediaSlot.fromKey(file.nameWithoutExtension) ?: continue
            if (file.extension.lowercase() !in storedExtensions) continue
            out[slot] = file.absolutePath
        }
        return out
    }

    // ── Mutations ────────────────────────────────────────────────────────────

    /**
     * Imports [uri] as [slot]'s media: validate the MIME against the slot's caps up front, size
     * pre-check via the SAF descriptor, stream into a capped staging file, probe the COPY, then
     * swap the file in.
     * On any rejection the staged file is deleted and the previous assignment keeps playing.
     */
    suspend fun import(slot: UiMediaSlot, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        if (!UiMediaSlot.isValidKey(slot.key)) {
            return@withContext ImportResult(false, "Not a customizable media slot")
        }
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val ext = mime?.let { UiMediaLimits.extensionForMime(it) }
        if (mime == null || ext == null) {
            return@withContext ImportResult(
                false,
                if (slot.kind == UiMediaKind.VIDEO) UiMediaLimits.MSG_UNSUPPORTED_FORMAT_VIDEO
                else UiMediaLimits.MSG_UNSUPPORTED_FORMAT_AUDIO,
            )
        }
        val spec = slot.limits

        // Size pre-check straight off the descriptor when the provider reports one: a 2 GB pick
        // is rejected without transferring a byte (same gate as the motion wallpaper).
        val knownSize = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it > 0 }
        if (knownSize != null && knownSize > spec.maxBytes) {
            return@withContext ImportResult(false, UiMediaLimits.tooLarge(spec))
        }

        dir.mkdirs()
        val staged = File(dir, "staging_${System.currentTimeMillis()}.$ext")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                // Stream to the staged file: the descriptor can lie, but a crafted pick must
                // never make us allocate the entire untrusted audio file on the heap.
                staged.outputStream().use { output ->
                    with(SafeMedia) { input.copyCappedTo(output, spec.maxBytes) }
                }
            }
        }.getOrNull()
        if (copied == null) {
            runCatching { staged.delete() }
            return@withContext ImportResult(false, UiMediaLimits.MSG_UNDECODABLE)
        }

        val rejection = validateImported(staged, spec)
        if (rejection != null) {
            runCatching { staged.delete() }
            return@withContext ImportResult(false, rejection)
        }

        // Commit: remove any existing file for this slot under a DIFFERENT extension, then move
        // the staged file into place, then bump the stamp so observers reload.
        for (candidateExt in storedExtensions) {
            if (candidateExt != ext) File(dir, "${slot.key}.$candidateExt").delete()
        }
        val dest = File(dir, "${slot.key}.$ext")
        if (!staged.renameTo(dest)) {
            // Some filesystems refuse cross-handle renames; fall back to a copy.
            staged.copyTo(dest, overwrite = true)
            staged.delete()
        }
        // Recorded here rather than by the caller: a slot with a file but no name would render
        // "Custom sound" forever, and there is no second place that knows the source Uri.
        recordDisplayName(slot, uri)
        context.pfpDataStore.edit { prefs -> prefs[KEY_UI_MEDIA_STAMP] = System.currentTimeMillis() }
        ImportResult(true)
    }

    /**
     * Clears one slot: the file first, then a stamp bump so observers reload — nothing
     * references the file while it's being removed, and no bump without a real removal.
     * Returns whether a pick was actually removed, so the UI can tell "reset" from "nothing
     * was set" apart instead of leaving a silent button.
     */
    suspend fun clear(slot: UiMediaSlot): Boolean = withContext(Dispatchers.IO) {
        if (!UiMediaSlot.isValidKey(slot.key)) return@withContext false
        val removed = storedExtensions.any { ext -> File(dir, "${slot.key}.$ext").delete() }
        if (removed) {
            context.pfpDataStore.edit { prefs ->
                // The name goes with the file — a stale one would label the PFP default.
                prefs.remove(displayNameKey(slot))
                prefs[KEY_UI_MEDIA_STAMP] = System.currentTimeMillis()
            }
        }
        removed
    }

    /**
     * Clears every slot of [kind] — "Reset Sound to Defaults" passes [UiMediaKind.SOUND]; the
     * Boot/GameBoot screens reset their own kinds. Never touches other kinds' files. Note Boot
     * Sound ([UiMediaSlot.BOOT_AUDIO]) is AUDIO_TRACK, not SOUND: callers that own it as one of
     * their rows clear it alongside its [UiMediaKind.SOUND] siblings themselves.
     */
    suspend fun clearAll(kind: UiMediaKind): Boolean = withContext(Dispatchers.IO) {
        var removedAny = false
        for (slot in UiMediaSlot.ofKind(kind)) {
            if (clear(slot)) removedAny = true
        }
        removedAny
    }

    /**
     * Sweeps everything in `ui-media/` (and its display-name prefs) that no longer belongs to a
     * live [UiMediaSlot]: files left by slots REMOVED from the enum, files from a restored OLD
     * backup archive, and staging files abandoned by a crashed import. They are inert —
     * `assignments()` already drops unknown keys — but they leak disk and prefs forever.
     *
     * Same contract as [clear]: returns whether anything was removed, and bumps the stamp only on
     * a real removal, so observers reload exactly once. Call it once at startup and after a
     * backup restore; cheap when there is nothing to do (one directory listing).
     */
    suspend fun pruneOrphans(): Boolean = withContext(Dispatchers.IO) {
        var removedAny = false

        // 1. Files whose name is not a live slot key. nameWithoutExtension is the same parse
        //    assignments() uses, so the two can never disagree about what an orphan is. A valid
        //    key under an odd extension is deliberately kept — only the enum's verdict decides.
        val files = dir.listFiles { f -> f.isFile }
        for (file in files.orEmpty()) {
            if (!UiMediaSlot.isValidKey(file.nameWithoutExtension) && file.delete()) {
                removedAny = true
            }
        }

        // 2. Display-name prefs for keys that no longer exist — matched by prefix, the same
        //    format displayNameKey writes, so a slot removed later is swept without an edit here.
        val prefs = context.pfpDataStore.data.first()
        val staleNameKeys = prefs.asMap().keys
            .filterIsInstance<androidx.datastore.preferences.core.Preferences.Key<String>>()
            .filter {
                it.name.startsWith(DISPLAY_NAME_PREFIX) &&
                    !UiMediaSlot.isValidKey(it.name.removePrefix(DISPLAY_NAME_PREFIX))
            }
        if (staleNameKeys.isNotEmpty()) {
            context.pfpDataStore.edit { p -> staleNameKeys.forEach { p.remove(it) } }
            removedAny = true
        }

        if (removedAny) {
            context.pfpDataStore.edit { it[KEY_UI_MEDIA_STAMP] = System.currentTimeMillis() }
        }
        removedAny
    }

    /**
     * The display name recorded for [slot]'s current assignment, or null when the slot is on
     * the default. The name was captured at import time from the (untrusted) provider and is
     * length-clamped there; the settings screens fall back to "Custom sound"/"Custom video"
     * when it is null.
     */
    suspend fun displayNameFor(slot: UiMediaSlot): String? {
        val prefs = context.pfpDataStore.data.first()
        return prefs[displayNameKey(slot)]
    }

    /**
     * Queries and stores the picked file's display name beside its copy. Cosmetic — the file is
     * ours now — but the Audio screen shows it, and a raw `content://…` string must never reach
     * a label. Falls back to "Custom sound"/"Custom video" when the provider reports nothing.
     */
    suspend fun recordDisplayName(slot: UiMediaSlot, uri: Uri) {
        val fallback = if (slot.kind == UiMediaKind.VIDEO) "Custom video" else "Custom sound"
        val name = MediaDisplayNames.queryDisplayName(context, uri) ?: fallback
        context.pfpDataStore.edit { prefs -> prefs[displayNameKey(slot)] = name }
    }

    /**
     * Runs the import gate on the staged copy. Duration is MANDATORY for every kind —
     * [UiMediaLimits.Probe.durationMs] is null when MediaMetadataRetriever cannot read a length,
     * and that is a rejection, not a pass. Some devices return null for otherwise-playable
     * short/VBR MP3s and some WAVs, so a null read first falls back to [MediaDurationFallback]'s
     * container-header math; only a file neither can time is rejected.
     */
    private fun validateImported(file: File, spec: UiMediaLimits.Spec): String? = runCatching {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(file.absolutePath)
            val durationRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                ?: mimeGuessFor(file)
            UiMediaLimits.validate(
                spec,
                UiMediaLimits.Probe(
                    mime = mime,
                    durationMs = durationRaw?.toLongOrNull()
                        ?: MediaDurationFallback.durationMs(file, mime),
                    bytes = file.length(),
                ),
            )
        }
    }.getOrDefault(UiMediaLimits.MSG_UNDECODABLE)

    /**
     * Some containers (WAV notably) report no MIME metadata; the extension we stored them under
     * was derived from the picker's MIME, so it is authoritative here.
     */
    private fun mimeGuessFor(file: File): String? =
        UiMediaLimits.mimeForExtension(file.extension.lowercase())

    companion object {
        /** User UI media lives here, one file per slot, named `<slotKey>.<ext>`. */
        const val UI_MEDIA_DIR = "ui-media"

        /** The extension set [extensionForMime] can produce — the only suffixes this store writes. */
        val storedExtensions = setOf("mp3", "wav", "ogg", "m4a", "mp4", "webm")

        /**
         * Present ⇒ user UI media exists under [UI_MEDIA_DIR]; the value only bumps so observers
         * reload. Mirrors `custom_icons_stamp`'s contract. Backed up by feature-backup.
         */
        val KEY_UI_MEDIA_STAMP = longPreferencesKey("ui_media_stamp")

        /** The Menu Sounds enable pref — observed by [UiMediaPaths.menuSoundsEnabled]. */
        val KEY_MENU_SOUNDS_ENABLED = booleanPreferencesKey("sound_menu_enabled")

        /**
         * Where [slot]'s cosmetic display name is stored. Public so the settings screens can read
         * the whole set out of one prefs snapshot instead of a suspend call per row.
         */
        fun displayNameKey(slot: UiMediaSlot) = stringPreferencesKey("ui_media_name_${slot.key}")

        /** The prefix [displayNameKey] writes — [pruneOrphans] sweeps orphaned suffixes. */
        private const val DISPLAY_NAME_PREFIX = "ui_media_name_"
    }
}

/** Hilt binding: core-ui sees [UiMediaPaths]; the implementation lives here. */
@Module
@InstallIn(SingletonComponent::class)
abstract class UiMediaModule {
    @Binds
    @Singleton
    abstract fun bindUiMediaPaths(impl: UiMediaStore): UiMediaPaths
}
