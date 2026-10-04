package com.playfieldportal.core.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Xenia-based Xbox 360 emulators whose data folder PFP can read achievements from. Each keeps
 * its own profiles, so each gets its own grant; [packages] is every install id it ships under.
 */
enum class Xbox360Emulator(val label: String, val packages: List<String>, internal val prefKey: String) {
    // Publishes its data (profiles, saves, content) through its own documents provider, so the
    // system folder picker lists it as a location of its own.
    X360_MOBILE("X360 Mobile", listOf("emu.x360mobile.com"), "x360mobile_data_tree_uri"),

    // Keeps everything in Android/data/xendroid.compose/files/compose.
    XENDROID("XenDroid", listOf("xendroid.compose", "xendroid.compose.debug"), "xendroid_data_tree_uri");

    /**
     * Where the folder picker should open, or null to leave it at its default. XenDroid's folder
     * is deep under Android/data, so the picker starts there; X360 Mobile is its own picker root.
     */
    val pickerStartUri: Uri?
        get() = when (this) {
            X360_MOBILE -> null
            XENDROID -> DocumentsContract.buildDocumentUri(
                RomRootRepository.EXTERNAL_STORAGE_AUTHORITY,
                "primary:Android/data/xendroid.compose",
            )
        }
}

/**
 * Holds the user's grants to the Xbox 360 emulators' data folders — the SAF permissions that power
 * Xbox 360 achievement tracking, as [Ps3DataLibrary] does for ARMSX3. One grant per
 * [Xbox360Emulator]; both may be set, and achievements earned in either count.
 *
 * The reader is tolerant about which level the user picks (the emulator's root, `content/`, or a
 * profile folder itself) — see `X360AchievementDiscovery`. Read-only: PFP only ever takes a read
 * grant and never writes into the emulator's folder.
 */
@Singleton
class Xbox360DataLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** The granted tree URI for [emulator], or null when the user hasn't set one. */
    fun treeUriFlow(emulator: Xbox360Emulator): Flow<String?> =
        context.pfpDataStore.data.map { it[key(emulator)] }

    /** Snapshot of [treeUriFlow]. */
    suspend fun treeUri(emulator: Xbox360Emulator): String? =
        context.pfpDataStore.data.first()[key(emulator)]

    /** Every granted folder, X360 Mobile first. Empty means Xbox 360 achievements are not configured. */
    suspend fun grantedTreeUris(): List<String> {
        val prefs = context.pfpDataStore.data.first()
        return Xbox360Emulator.entries.mapNotNull { prefs[key(it)] }
    }

    /** Persists a read grant on [treeUri] and stores it as [emulator]'s data folder. */
    suspend fun setFolder(emulator: Xbox360Emulator, treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { Timber.w(it, "Could not persist %s data folder grant", emulator.label) }
        context.pfpDataStore.edit { it[key(emulator)] = treeUri.toString() }
        Timber.i("%s data folder set to: %s", emulator.label, treeUri)
    }

    /** Forgets [emulator]'s folder (its achievements then go dark until re-set). */
    suspend fun clear(emulator: Xbox360Emulator) {
        context.pfpDataStore.edit { it.remove(key(emulator)) }
    }

    private fun key(emulator: Xbox360Emulator) = stringPreferencesKey(emulator.prefKey)

    companion object {
        /**
         * How a granted folder reads on screen. Both emulators can grant through their own
         * documents provider, whose folder ids are not shared-storage `volume:path` ids: XenDroid's
         * are absolute paths, X360 Mobile's are opaque (`v:root`). Only shared storage maps
         * `volume:path` onto `/storage/...` ([RomRootRepository.rawPathOfTree]); an opaque root
         * reads as "<emulator> data". Pure, so it is testable on the JVM.
         */
        fun folderLabel(treeUri: String, emulator: Xbox360Emulator): String {
            RomRootRepository.rawPathOfTree(treeUri)?.let { return it }
            val fallback = "${emulator.label} data"
            val docId = RomRootRepository.decodedTreeDocId(treeUri) ?: return fallback
            // An opaque provider root: only a subfolder below it carries a name worth showing.
            val sub = docId.substringAfter(':', missingDelimiterValue = docId).trim('/')
                .substringAfter('/', missingDelimiterValue = "")
            return if (sub.isEmpty()) fallback else "${emulator.label} · ${sub.substringAfterLast('/')}"
        }
    }
}
