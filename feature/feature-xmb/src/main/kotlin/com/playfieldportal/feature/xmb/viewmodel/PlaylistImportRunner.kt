package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.data.music.AudioFileFilter
import com.playfieldportal.core.data.video.VideoFileFilter
import com.playfieldportal.core.domain.model.NotificationDetail
import com.playfieldportal.core.domain.model.ResultItem
import com.playfieldportal.core.domain.model.ResultOutcome
import com.playfieldportal.core.domain.model.ResultsLabels
import com.playfieldportal.core.domain.playlist.PlaylistCandidate
import com.playfieldportal.core.domain.playlist.PlaylistEntry
import com.playfieldportal.core.domain.playlist.PlaylistEntryOutcome
import com.playfieldportal.core.domain.playlist.PlaylistFileParser
import com.playfieldportal.core.domain.playlist.PlaylistImportNaming
import com.playfieldportal.core.domain.playlist.PlaylistKind
import com.playfieldportal.core.domain.playlist.PlaylistMatcher
import com.playfieldportal.core.domain.playlist.PlaylistSkipReason
import com.playfieldportal.core.domain.repository.MusicRepository
import com.playfieldportal.core.domain.repository.VideoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject

/** A picked document, already read by [PlaylistDocumentReader] (or the reason it could not be). */
sealed interface PickedPlaylistFile {
    val name: String

    class Content(override val name: String, val bytes: ByteArray) : PickedPlaylistFile

    data class Unreadable(override val name: String, val reason: String) : PickedPlaylistFile
}

/**
 * What importing one file did. [playlistId] / [playlistName] are set only when a playlist was
 * created; [error] is set when the file could not be imported at all (outcomes are then empty).
 */
data class PlaylistImportReport(
    val kind: PlaylistKind,
    val fileName: String,
    val playlistName: String?,
    val playlistId: Long?,
    val outcomes: List<PlaylistEntryOutcome>,
    /** Display titles of the library items by id, for the "found" rows. */
    val titlesById: Map<String, String> = emptyMap(),
    val dropped: Int = 0,
    val error: String? = null,
) {
    val title: String
        get() = when {
            error != null -> "Couldn't import \"$fileName\""
            playlistId != null -> "Imported \"$playlistName\""
            else -> "Nothing imported from \"$fileName\""
        }

    /** The §3.6 Results sheet payload: failures first, then skips, then the found items. */
    fun toResults(): NotificationDetail.Results {
        if (error != null) return NotificationDetail.results(emptyList(), summary = error)
        val items = outcomes.map { outcome ->
            when (outcome) {
                is PlaylistEntryOutcome.Matched -> ResultItem(
                    primary = titlesById[outcome.id] ?: entryLabel(outcome.entry),
                    outcome = ResultOutcome.DONE,
                )
                is PlaylistEntryOutcome.Skipped -> ResultItem(
                    primary = entryLabel(outcome.entry),
                    outcome = ResultOutcome.SKIPPED,
                    reason = when (outcome.reason) {
                        PlaylistSkipReason.WEB_LINK -> "not a local file"
                        PlaylistSkipReason.WRONG_TYPE -> "wrong media type"
                        PlaylistSkipReason.DUPLICATE -> "already in the playlist"
                    },
                )
                is PlaylistEntryOutcome.NotFound -> ResultItem(
                    primary = entryLabel(outcome.entry),
                    outcome = ResultOutcome.FAILED,
                    reason = if (outcome.ambiguous) "more than one match" else "not in your library",
                )
            }
        }
        val found = items.count { it.outcome == ResultOutcome.DONE }
        val missing = items.count { it.outcome == ResultOutcome.FAILED }
        val skipped = items.count { it.outcome == ResultOutcome.SKIPPED }
        val summary = buildList {
            add("$found found")
            if (missing > 0) add("$missing not in your library")
            if (skipped > 0) add("$skipped skipped")
            if (dropped > 0) add("$dropped past the ${PlaylistFileParser.MAX_ENTRIES} entry limit ignored")
        }.joinToString(" · ")
        return NotificationDetail.results(items, summary = summary, labels = LABELS)
    }

    private fun entryLabel(entry: PlaylistEntry): String {
        val artist = entry.artist?.takeIf { it.isNotBlank() }
        val title = entry.title?.takeIf { it.isNotBlank() }
        return if (artist != null && title != null) "$artist – $title" else entry.location
    }

    private companion object {
        val LABELS = ResultsLabels(done = "Found", failed = "Not in your library", skipped = "Skipped")
    }
}

/**
 * Playlist file import: parse, match against the library, name, write, report. Writes a playlist
 * only when something matched, so a file of misses creates nothing. One report per file; a file
 * that fails never stops the rest of the batch.
 */
class PlaylistImportRunner @Inject constructor(
    private val musicRepository: MusicRepository,
    private val videoRepository: VideoRepository,
) {
    suspend fun run(kind: PlaylistKind, files: List<PickedPlaylistFile>): List<PlaylistImportReport> {
        val takenNames = existingNames(kind).toMutableList()
        // Loaded once for the batch, and only if a file gets as far as matching.
        var library: List<PlaylistCandidate>? = null
        var titles: Map<String, String> = emptyMap()
        return files.map { file ->
            try {
                when (file) {
                    is PickedPlaylistFile.Unreadable -> errorReport(kind, file.name, file.reason)
                    is PickedPlaylistFile.Content -> {
                        if (!isPlaylistFile(file.name)) return@map errorReport(kind, file.name, "Not a playlist file")
                        if (library == null) {
                            val (candidates, byId) = loadLibrary(kind)
                            library = candidates
                            titles = byId
                        }
                        importOne(kind, file, library, titles, takenNames)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Playlist import failed for ${file.name}")
                errorReport(kind, file.name, e.message ?: "Unexpected error")
            }
        }
    }

    private suspend fun importOne(
        kind: PlaylistKind,
        file: PickedPlaylistFile.Content,
        library: List<PlaylistCandidate>,
        titles: Map<String, String>,
        takenNames: MutableList<String>,
    ): PlaylistImportReport {
        val parsed = PlaylistFileParser.parse(file.name, file.bytes)
        parsed.rejected?.let { return errorReport(kind, file.name, it) }

        val (own, other) = when (kind) {
            PlaylistKind.MUSIC -> AudioFileFilter.AUDIO_EXTENSIONS to VideoFileFilter.VIDEO_EXTENSIONS
            PlaylistKind.VIDEO -> VideoFileFilter.VIDEO_EXTENSIONS to AudioFileFilter.AUDIO_EXTENSIONS
        }
        val match = PlaylistMatcher.match(kind, parsed.entries, library, own, other)

        var name: String? = null
        var id: Long? = null
        if (match.matchedIds.isNotEmpty()) {
            name = PlaylistImportNaming.uniqueName(PlaylistImportNaming.baseName(parsed.name, file.name), takenNames)
            id = when (kind) {
                PlaylistKind.MUSIC -> musicRepository.importPlaylist(name, match.matchedIds)
                PlaylistKind.VIDEO -> videoRepository.importPlaylist(name, match.matchedIds)
            }
            takenNames += name
        }
        return PlaylistImportReport(
            kind = kind, fileName = file.name, playlistName = name, playlistId = id,
            outcomes = match.outcomes, titlesById = titles, dropped = parsed.dropped,
        )
    }

    private fun errorReport(kind: PlaylistKind, fileName: String, reason: String) = PlaylistImportReport(
        kind = kind, fileName = fileName, playlistName = null, playlistId = null,
        outcomes = emptyList(), error = reason,
    )

    private suspend fun existingNames(kind: PlaylistKind): List<String> = when (kind) {
        PlaylistKind.MUSIC -> musicRepository.observePlaylists().first().map { it.name }
        PlaylistKind.VIDEO -> videoRepository.observePlaylists().first().map { it.name }
    }

    private suspend fun loadLibrary(kind: PlaylistKind): Pair<List<PlaylistCandidate>, Map<String, String>> =
        when (kind) {
            PlaylistKind.MUSIC -> {
                val tracks = musicRepository.observeAllTracks().first()
                tracks.map {
                    PlaylistCandidate(it.id, it.relativePath, it.displayName, it.title, it.artist, it.durationMs)
                } to tracks.associate { it.id to it.displayTitle }
            }
            PlaylistKind.VIDEO -> {
                val videos = videoRepository.observeAllVideos().first()
                // A video's title is a user override, not a tag, so only the file name takes part.
                videos.map { PlaylistCandidate(it.id, it.relativePath, it.displayName, durationMs = it.durationMs) } to
                    videos.associate { it.id to it.displayTitle }
            }
        }

    private fun isPlaylistFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in PLAYLIST_EXTENSIONS

    private companion object {
        val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8", "pls", "xspf")
    }
}
