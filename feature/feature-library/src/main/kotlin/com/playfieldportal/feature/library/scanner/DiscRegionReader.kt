package com.playfieldportal.feature.library.scanner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameRegion
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Detects the TV format / region of a disc-based game by reading the disc image content — never
 * the filename. Shared by [RomScanner] (fresh-scan enrichment) and [DiscSetReconciler]
 * (reconciliation over existing rows) so every scan path detects region identically.
 *
 * Raw-path games read from disk; SAF games from their document URI. For .cue / .gdi sheets the
 * first data-track file (the .bin) is resolved and read instead of the sheet itself. A read or
 * detection failure is a soft failure — the game keeps region null (Unknown), which only ever
 * falls back to merging, never mis-splits a set.
 *
 * Supported: psx, ps2, psp, gc, wii, saturn, dreamcast, segacd, x360, ps3. PC Engine and
 * cartridge platforms return null (no reliable embedded region marker / no multi-disc).
 */
@Singleton
class DiscRegionReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun read(game: Game): GameRegion? = when (game.platformId) {
        "psx" -> headDetect(game, 256 * 1024, DiscRegionDetectors::detectPsx)
        "ps2" -> headDetect(game, 256 * 1024, DiscRegionDetectors::detectPs2)
        "psp" -> headDetect(game, 64 * 1024, DiscRegionDetectors::detectPsp)
        "gc", "wii" -> headDetect(game, 4 * 1024, DiscRegionDetectors::detectBootBin)
        "saturn", "dreamcast", "segacd" -> headDetect(game, 4 * 1024, DiscRegionDetectors::detectIpBin)
        "x360" -> headDetect(game, 2 * 1024 * 1024, DiscRegionDetectors::detectX360)
        "ps3" -> detectPs3(game)
        else -> null
    }

    private fun headDetect(
        game: Game,
        maxBytes: Int,
        detect: (ByteArray) -> GameRegion?,
    ): GameRegion? {
        val head = readImageHead(game, maxBytes) ?: return null
        return runCatching { detect(head) }.getOrNull()
    }

    private fun detectPs3(game: Game): GameRegion? {
        // PS3 games are folder-based (PS3_GAME/) or ISOs. Folder: read PARAM.SFO straight from
        // disk. SAF folder trees are out of scope here — region stays null (safe fallback).
        val path = game.romPath ?: return null
        if (!game.romUri.isNullOrBlank()) return null
        val dir = File(path)
        if (!dir.isDirectory) return headDetect(game, 256 * 1024) { DiscRegionDetectors.detectPs3Sfo(it) }
        val candidates = listOf(
            File(dir, "PARAM.SFO"),
            File(dir, "PS3_GAME/PARAM.SFO"),
        )
        for (sfo in candidates) {
            if (!sfo.isFile) continue
            return runCatching {
                sfo.inputStream().use { DiscRegionDetectors.detectPs3Sfo(it.readBytes()) }
            }.getOrNull()
        }
        return null
    }

    // ── image head reading ───────────────────────────────────────────────────

    private fun readImageHead(game: Game, maxBytes: Int): ByteArray? {
        return try {
            if (game.romUri.isNullOrBlank()) {
                val file = game.romPath?.let { File(it) } ?: return null
                val image = resolveRawImageFile(file) ?: return null
                if (!image.isFile) return null
                image.inputStream().use { readAtMost(it, maxBytes) }
            } else {
                val imageUri = resolveSafImageUri(game) ?: return null
                context.contentResolver.openInputStream(imageUri)?.use { readAtMost(it, maxBytes) }
            }
        } catch (e: Exception) {
            Timber.w(e, "Region read failed for ${game.romPath}")
            null
        }
    }

    /** Reads at most [maxBytes] (a disc image can be gigabytes — never read it whole). */
    private fun readAtMost(input: InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArray(maxBytes)
        var total = 0
        while (total < maxBytes) {
            val n = input.read(buffer, total, maxBytes - total)
            if (n < 0) break
            total += n
        }
        return buffer.copyOf(total)
    }

    /** Resolves a raw-path game to the file whose bytes hold the disc's boot data. */
    private fun resolveRawImageFile(file: File): File? {
        return when (file.extension.lowercase()) {
            "cue" -> {
                val names = runCatching { cueSheetReferences(file.readLines()) }.getOrNull() ?: return null
                names.firstOrNull()?.let { File(file.parentFile, it) }
            }
            "gdi" -> {
                val lines = runCatching { file.readLines() }.getOrNull() ?: return null
                gdiSheetTrackNames(lines).firstOrNull()?.let { File(file.parentFile, it) }
            }
            "m3u" -> null  // playlist — the discs themselves carry the region
            else -> file
        }
    }

    /** Resolves a SAF game to the document URI whose bytes hold the disc's boot data. */
    private fun resolveSafImageUri(game: Game): Uri? {
        val sheetUri = runCatching { Uri.parse(game.romUri) }.getOrNull() ?: return null
        val name = sheetUri.lastPathSegment?.substringAfterLast('/') ?: return null
        return when (name.substringAfterLast('.', "").lowercase()) {
            "cue" -> {
                val lines = context.contentResolver.openInputStream(sheetUri)
                    ?.bufferedReader()?.readLines() ?: return null
                cueSheetReferences(lines).firstOrNull()?.let { siblingDocumentUri(sheetUri, it) }
            }
            "gdi" -> {
                val lines = context.contentResolver.openInputStream(sheetUri)
                    ?.bufferedReader()?.readLines() ?: return null
                gdiSheetTrackNames(lines).firstOrNull()?.let { siblingDocumentUri(sheetUri, it) }
            }
            "m3u" -> null
            else -> sheetUri
        }
    }

    /**
     * Builds the document URI for a sibling file next to a sheet: document ids are
     * "volume:dirs/name", so the sibling's id is the sheet's parent plus the sibling name.
     */
    private fun siblingDocumentUri(sheetUri: Uri, siblingName: String): Uri? {
        val authority = sheetUri.authority ?: return null
        val docId = runCatching { DocumentsContract.getDocumentId(sheetUri) }.getOrNull() ?: return null
        val parent = docId.substringBeforeLast('/')
        return DocumentsContract.buildDocumentUri(authority, "$parent/$siblingName")
    }
}
