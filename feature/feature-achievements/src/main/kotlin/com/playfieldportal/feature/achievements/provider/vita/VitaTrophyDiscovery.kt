package com.playfieldportal.feature.achievements.provider.vita

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.playfieldportal.core.data.repository.Vita3KLibrary
import com.playfieldportal.core.data.saf.SafChild
import com.playfieldportal.core.data.saf.querySafChildren
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads PS Vita trophy sets from Vita3K's granted `ux0` folder ([Vita3KLibrary]).
 *
 * A game (`ux0/app/<TITLE_ID>`) declares its trophy set id (NPCOMMID) under `sce_sys/trophy/`; the
 * set's definitions/icons live in `ux0/user/00/trophy/conf/<NPCOMMID>` (`TROP.SFM` + `TROP*.PNG`)
 * and the player's unlock state in `.../data/<NPCOMMID>/TROPUSR.DAT`. This joins those into one
 * trophy list. Read-only and grant-scoped; empty when no `ux0` folder is set.
 */
@Singleton
class VitaTrophyDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vita3KLibrary: Vita3KLibrary,
) {
    data class VitaTrophy(
        val id: Int,
        val name: String,
        val detail: String,
        val grade: TropUsrParser.Grade,
        val hidden: Boolean,
        val unlocked: Boolean,
        val unlockedAtEpochSec: Long?,
        val iconUri: String?,
    )

    data class VitaTrophySet(
        val npCommId: String,
        val titleName: String?,
        val trophies: List<VitaTrophy>,
    )

    /** The trophy set id (NPCOMMID) the installed [titleId] declares, or null. */
    suspend fun trophySetIdFor(titleId: String): String? = withContext(Dispatchers.IO) {
        val (tree, rootDoc) = grantRoot() ?: return@withContext null
        val trophyDir = resolveDir(tree, rootDoc, listOf("app", titleId, "sce_sys", "trophy"))
            ?: return@withContext null
        context.contentResolver.querySafChildren(tree, trophyDir)
            .firstOrNull { it.isDirectory && it.name.startsWith("NPWR", ignoreCase = true) }
            ?.name
    }

    /** Definitions + icons + unlock state for [npCommId], or null when the set can't be read. */
    suspend fun loadSet(npCommId: String): VitaTrophySet? = withContext(Dispatchers.IO) {
        val (tree, rootDoc) = grantRoot() ?: return@withContext null
        val confDir = resolveDir(tree, rootDoc, listOf("user", "00", "trophy", "conf", npCommId))
            ?: return@withContext null
        val confChildren = context.contentResolver.querySafChildren(tree, confDir)
        val sfmBytes = confChildren.fileUri("TROP.SFM")?.let { readBytes(it, SMALL_FILE_MAX) }
            ?: return@withContext null
        val set = TropSfm.parse(sfmBytes)

        // Unlock state is best-effort — a set with no TROPUSR.DAT yet simply tracks at 0%.
        val progressById = resolveDir(tree, rootDoc, listOf("user", "00", "trophy", "data", npCommId))
            ?.let { dataDir -> context.contentResolver.querySafChildren(tree, dataDir).fileUri("TROPUSR.DAT") }
            ?.let { readBytes(it, SMALL_FILE_MAX) }
            ?.let { TropUsrParser.parse(it) }
            ?.trophies?.associateBy { it.id }
            .orEmpty()

        val iconByFile = confChildren.filter { !it.isDirectory }.associate { it.name.uppercase() to it.uri }
        val trophies = set.trophies.map { def ->
            val progress = progressById[def.id]
            VitaTrophy(
                id = def.id,
                name = def.name,
                detail = def.detail,
                grade = def.grade,
                hidden = def.hidden,
                unlocked = progress?.unlocked ?: false,
                unlockedAtEpochSec = progress?.unlockedAtEpochSec,
                iconUri = iconByFile["TROP%03d.PNG".format(def.id)]?.toString(),
            )
        }
        VitaTrophySet(npCommId = set.npCommId ?: npCommId, titleName = set.titleName, trophies = trophies)
    }

    // Returns (tree, ux0DocId). The grant may be ux0 itself or a parent that contains it, so resolve
    // the folder that actually holds app/ and user/.
    private suspend fun grantRoot(): Pair<Uri, String>? {
        val tree = vita3KLibrary.ux0TreeUri()?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        val rootDoc = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        val ux0 = if (childDir(tree, rootDoc, "app") != null) {
            rootDoc
        } else {
            childDir(tree, rootDoc, "ux0")?.takeIf { childDir(tree, it, "app") != null }
        } ?: return null
        return tree to ux0
    }

    private fun childDir(tree: Uri, parentDocId: String, name: String): String? =
        context.contentResolver.querySafChildren(tree, parentDocId)
            .firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) }
            ?.documentId

    private fun resolveDir(tree: Uri, startDocId: String, segments: List<String>): String? {
        var docId = startDocId
        for (segment in segments) {
            docId = context.contentResolver.querySafChildren(tree, docId)
                .firstOrNull { it.isDirectory && it.name.equals(segment, ignoreCase = true) }
                ?.documentId ?: return null
        }
        return docId
    }

    private fun List<SafChild>.fileUri(name: String): Uri? =
        firstOrNull { !it.isDirectory && it.name.equals(name, ignoreCase = true) }?.uri

    private fun readBytes(uri: Uri, maxBytes: Int): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            var remaining = maxBytes
            while (remaining > 0) {
                val read = input.read(buf, 0, minOf(buf.size, remaining))
                if (read == -1) break
                out.write(buf, 0, read)
                remaining -= read
            }
            out.toByteArray()
        }
    }.getOrNull()

    private companion object {
        const val SMALL_FILE_MAX = 256 * 1024
    }
}
