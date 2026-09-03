package com.playfieldportal.feature.launcher

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.playfieldportal.core.data.database.dao.MemoryCardDao
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether a raw filesystem path belongs to a configured ROM source.
 *
 * Separate from [RomUriMinter] and free of Android types so the rule itself is unit-testable — the
 * rule is the security-relevant part, and the FileProvider call around it is not.
 */
object RomSourceAdmission {

    /**
     * True when [path] is a file strictly inside one of [sources].
     *
     * Canonical paths are compared, so `..` segments and symlinks cannot walk out of a source, and
     * the comparison appends a separator so `/roms_evil/x` does not match the source `/roms`.
     */
    fun isAdmissible(path: String, sources: List<String>): Boolean {
        if (path.isBlank()) return false
        val target = runCatching { File(path).canonicalPath }.getOrNull() ?: return false

        return sources.any { source ->
            if (source.isBlank()) return@any false
            val root = runCatching { File(source).canonicalPath }.getOrNull() ?: return@any false
            target != root && target.startsWith(root + File.separator)
        }
    }
}

/**
 * Mints the `content://` URI an emulator receives for a ROM.
 *
 * The FileProvider is configured with a `root-path` of `/storage/`, because `<external-path>` does
 * not cover removable volumes and a launch from an SD card fails outright without it. That root is
 * far wider than any caller needs: every launch resolves a file belonging to a configured Memory
 * Card, never an arbitrary path. Nothing enforced the narrower rule, and the package that receives
 * `grantUriPermission(...)` comes from an emulator profile — which a restored backup can supply.
 *
 * This module is the enforcement point. The provider stays broad because XML cannot express
 * "somewhere under /storage, except other apps' data directories"; the rule lives here instead,
 * where it can consult what the user actually configured. Callers get a URI or nothing.
 */
@Singleton
class RomUriMinter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val memoryCardDao: MemoryCardDao,
) {

    /**
     * A `content://` URI for [romPath], or null when the path is not inside a configured ROM
     * source. Returning null rather than throwing keeps the caller's existing failure path.
     */
    suspend fun mint(romPath: String): Uri? {
        val sources = configuredSources()
        if (!RomSourceAdmission.isAdmissible(romPath, sources)) {
            Timber.w(
                "Refused to mint a ROM URI outside the configured sources (%d source(s) known)",
                sources.size,
            )
            return null
        }
        return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(romPath))
        }.onFailure { Timber.w(it, "FileProvider refused a ROM path inside a configured source") }
            .getOrNull()
    }

    /**
     * The raw directories the user configured for their Memory Cards.
     *
     * SAF-backed cards are absent on purpose: those launches already carry a `content://` URI
     * granted by the picker and never reach the FileProvider at all.
     */
    private suspend fun configuredSources(): List<String> =
        memoryCardDao.getAll().mapNotNull { it.romDirectory?.takeIf { dir -> dir.isNotBlank() } }
}
