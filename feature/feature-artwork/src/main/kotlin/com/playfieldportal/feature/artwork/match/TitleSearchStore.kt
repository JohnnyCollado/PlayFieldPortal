package com.playfieldportal.feature.artwork.match

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A title search's answer as kept between Studio opens, and the moment it stops counting. */
data class StoredTitleSearch(
    val candidates: List<GameCandidate>,
    val expiresAtMillis: Long,
)

/**
 * Title searches kept between Artwork Studio opens (AD-21), behind [CachingMatchEvidence]'s memory.
 *
 * [provider], [query] and [scope] arrive already normalized by the caller. What is worth keeping,
 * and for how long, is the caller's decision; a store only keeps what it is given and gives it back.
 * A store that cannot read or write says nothing and throws nothing: losing a kept search only
 * costs one request.
 */
interface TitleSearchStore {
    suspend fun read(provider: MatchProvider, query: String, scope: String): StoredTitleSearch?

    suspend fun write(provider: MatchProvider, query: String, scope: String, search: StoredTitleSearch)

    /** Keeps nothing. */
    object None : TitleSearchStore {
        override suspend fun read(provider: MatchProvider, query: String, scope: String): StoredTitleSearch? = null

        override suspend fun write(provider: MatchProvider, query: String, scope: String, search: StoredTitleSearch) = Unit
    }
}

/**
 * [TitleSearchStore] as one small JSON file per search in [directory], which belongs in the app's
 * cache: every entry can be asked again, so the system may clear it whenever it likes.
 *
 * A file is named by a hash of its key and also records the key, so a hash collision reads as a
 * miss rather than as another search's answer. An expired, unreadable or foreign file reads as a
 * miss and is deleted. Only the newest [maxEntries] files are kept.
 */
class FileTitleSearchStore(
    private val directory: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : TitleSearchStore {

    override suspend fun read(provider: MatchProvider, query: String, scope: String): StoredTitleSearch? =
        withContext(Dispatchers.IO) {
            val file = fileFor(provider, query, scope)
            if (!file.exists()) return@withContext null
            val entry = try {
                JSON.decodeFromString(Entry.serializer(), file.readText())
            } catch (e: Exception) {
                // IOException from the read, or a serialization error from a truncated or old file.
                Timber.d("Kept title search unreadable, dropping it: ${e.message}")
                file.delete()
                return@withContext null
            }
            if (entry.provider != provider.name || entry.query != query || entry.scope != scope ||
                entry.expiresAtMillis <= now()
            ) {
                file.delete()
                return@withContext null
            }
            StoredTitleSearch(entry.candidates.map { it.toCandidate(provider) }, entry.expiresAtMillis)
        }

    override suspend fun write(provider: MatchProvider, query: String, scope: String, search: StoredTitleSearch) {
        withContext(Dispatchers.IO) {
            val entry = Entry(
                provider = provider.name,
                query = query,
                scope = scope,
                expiresAtMillis = search.expiresAtMillis,
                candidates = search.candidates.map(StoredCandidate::of),
            )
            try {
                directory.mkdirs()
                val file = fileFor(provider, query, scope)
                // Written beside the target and renamed over it, so a reader never sees half a file.
                val partial = File(directory, "${file.name}.tmp")
                partial.writeText(JSON.encodeToString(Entry.serializer(), entry))
                if (!partial.renameTo(file)) {
                    file.delete()
                    partial.renameTo(file)
                }
                prune()
            } catch (e: IOException) {
                Timber.d("Title search not kept: ${e.message}")
            }
        }
    }

    private fun prune() {
        val files = directory.listFiles { f -> f.name.endsWith(SUFFIX) } ?: return
        if (files.size <= maxEntries) return
        files.sortedByDescending { it.lastModified() }.drop(maxEntries).forEach { it.delete() }
    }

    private fun fileFor(provider: MatchProvider, query: String, scope: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest("${provider.name}\n$scope\n$query".toByteArray())
        return File(directory, digest.joinToString("") { "%02x".format(it) } + SUFFIX)
    }

    @Serializable
    private data class Entry(
        val provider: String,
        val query: String,
        val scope: String,
        val expiresAtMillis: Long,
        val candidates: List<StoredCandidate>,
    )

    // A serializable copy of GameCandidate, so the match model stays free of serialization.
    @Serializable
    private data class StoredCandidate(
        val providerGameId: String,
        val title: String,
        val platformName: String? = null,
        val releaseYear: Int? = null,
        val thumbUrl: String? = null,
        val gameArtCount: Int? = null,
    ) {
        fun toCandidate(provider: MatchProvider) = GameCandidate(
            provider = provider,
            providerGameId = providerGameId,
            title = title,
            platformName = platformName,
            releaseYear = releaseYear,
            thumbUrl = thumbUrl,
            gameArtCount = gameArtCount,
        )

        companion object {
            fun of(candidate: GameCandidate) = StoredCandidate(
                providerGameId = candidate.providerGameId,
                title = candidate.title,
                platformName = candidate.platformName,
                releaseYear = candidate.releaseYear,
                thumbUrl = candidate.thumbUrl,
                gameArtCount = candidate.gameArtCount,
            )
        }
    }

    private companion object {
        const val SUFFIX = ".json"
        const val DEFAULT_MAX_ENTRIES = 500
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
