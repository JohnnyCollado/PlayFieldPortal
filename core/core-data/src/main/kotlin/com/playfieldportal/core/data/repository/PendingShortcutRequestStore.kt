package com.playfieldportal.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A legacy INSTALL_SHORTCUT request waiting for the user's Add / Ignore.
 *
 * The decision used to be made from Add / Ignore buttons on an Android shade notification. PFP's
 * notifications are launcher-only now, so the request waits here and the launcher asks in its own
 * modal the next time it is on screen. Another app can write to neither, which is what keeps the
 * confirmation a deliberate user choice.
 */
@Serializable
data class PendingShortcutRequest(
    val id: String,
    val name: String,
    val intentUri: String,
    val hostPackage: String? = null,
    val hostLabel: String,
    val requestedAt: Long,
) {
    companion object {
        /**
         * The queue id for [intentUri]: its SHA-256, hex. A resend of the same shortcut lands on the
         * same id; a DIFFERENT intent cannot, which `String.hashCode()` could not promise — a crafted
         * collision could have replaced the request the user was reviewing.
         */
        fun idFor(intentUri: String): String =
            MessageDigest.getInstance("SHA-256").digest(intentUri.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/** The queue's rules, apart from storage: oldest first, one entry per id, at most [MAX]. */
object PendingShortcutQueue {
    const val MAX = 10

    /** Adds [request] at the end; the same id replaces its old entry; past [MAX] the oldest go. */
    fun enqueue(queue: List<PendingShortcutRequest>, request: PendingShortcutRequest): List<PendingShortcutRequest> =
        (queue.filterNot { it.id == request.id } + request).takeLast(MAX)

    fun remove(queue: List<PendingShortcutRequest>, id: String): List<PendingShortcutRequest> =
        queue.filterNot { it.id == id }

    /**
     * Removes [shown] and returns it only when the queued entry under its id is still exactly the
     * request the user saw; otherwise nothing is taken and a replacement stays queued for its own
     * review. This is what binds Add to what was on screen.
     */
    fun take(
        queue: List<PendingShortcutRequest>,
        shown: PendingShortcutRequest,
    ): Pair<List<PendingShortcutRequest>, PendingShortcutRequest?> =
        if (queue.firstOrNull { it.id == shown.id } == shown) remove(queue, shown.id) to shown
        else queue to null
}

@Singleton
class PendingShortcutRequestStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PendingShortcutRequest.serializer())

    /** Oldest first. */
    val requests: Flow<List<PendingShortcutRequest>> = context.pfpDataStore.data.map { decode(it[KEY]) }

    suspend fun all(): List<PendingShortcutRequest> = requests.first()

    suspend fun get(id: String): PendingShortcutRequest? = all().firstOrNull { it.id == id }

    suspend fun enqueue(request: PendingShortcutRequest) = update { PendingShortcutQueue.enqueue(it, request) }

    suspend fun remove(id: String) = update { PendingShortcutQueue.remove(it, id) }

    /** [PendingShortcutQueue.take] in one transaction: no enqueue can land between check and removal. */
    suspend fun take(shown: PendingShortcutRequest): PendingShortcutRequest? {
        var taken: PendingShortcutRequest? = null
        update { queue -> PendingShortcutQueue.take(queue, shown).also { taken = it.second }.first }
        return taken
    }

    private suspend fun update(change: (List<PendingShortcutRequest>) -> List<PendingShortcutRequest>) {
        context.pfpDataStore.edit { prefs ->
            prefs[KEY] = json.encodeToString(serializer, change(decode(prefs[KEY])))
        }
    }

    private fun decode(raw: String?): List<PendingShortcutRequest> =
        raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    private companion object {
        val KEY = stringPreferencesKey("pending_shortcut_requests")
    }
}
