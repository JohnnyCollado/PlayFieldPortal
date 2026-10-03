package com.playfieldportal.feature.launcher.kb

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.data.kb.EmulatorKbDownloader
import com.playfieldportal.core.data.kb.KbDownload
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbDecode
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbDecoder
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbValidator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

/** How an update check ended. [message] is shown to the user as is. */
@Serializable
enum class KbUpdateResult(val message: String) {
    Installed("Updated"),
    NotConfigured("Updates are not set up in this build"),
    Offline("Couldn't reach the update server"),
    TooLarge("The update file is too large"),
    SignatureInvalid("The update's signature is not valid"),
    NeedsAppUpdate("This update needs a newer version of the app"),
    OlderThanInstalled("The update is older than the one already installed"),
    InvalidContent("The update file has invalid content"),
    StorageFailed("The update could not be saved"),

    /** An automatic check that was not due or is switched off. Never persisted as a status. */
    Skipped("No check was needed"),
}

/**
 * The last recorded check. [version], [label] and the counts describe the official file that is
 * installed, so a failed check keeps them and only changes [result].
 */
@Serializable
data class KbUpdateStatus(
    val result: KbUpdateResult,
    val version: Long = 0,
    val label: String = "",
    val emulatorCount: Int = 0,
    val platformCount: Int = 0,
)

/**
 * Checks for, verifies, gates and installs the official emulator knowledge file (AD-4, AD-7, AD-8).
 *
 * The pipeline of [check]: throttle and toggle, download body then signature, verify the signature
 * **before** parsing, decode, schema and `minAppVersion` gates, validate all or nothing, the
 * anti-rollback gate, install, refresh, record. Every failure leaves the installed official file
 * untouched and records why.
 *
 * The highest version ever accepted lives in `official/state.json`, which [EmulatorKnowledgeStore]
 * leaves alone on a reset: a reset must not let an older file back in. An equal version is allowed so
 * a reset can re-fetch.
 */
@Singleton
class EmulatorKbUpdater internal constructor(
    private val context: Context,
    private val downloader: EmulatorKbDownloader,
    private val store: EmulatorKnowledgeStore,
    private val refresher: EmulatorKnowledgeRefresher,
    private val verifier: KbSignatureVerifier,
    private val clock: () -> Long,
    private val appVersion: () -> Int,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        downloader: EmulatorKbDownloader,
        store: EmulatorKnowledgeStore,
        refresher: EmulatorKnowledgeRefresher,
    ) : this(
        context, downloader, store, refresher, KbSignatureVerifier(), System::currentTimeMillis,
        { context.appVersionCode() },
    )

    private val mutex = Mutex()

    /** Whether this build can verify updates at all (a signing key is pinned). */
    val isConfigured: Boolean get() = verifier.hasPinnedKeys

    /** Whether startup checks run. Defaults to on. */
    val autoUpdateEnabled: Flow<Boolean> = context.pfpDataStore.data.map { it[KEY_AUTO_UPDATE] ?: true }

    /** Epoch millis of the last check that reached the server, 0 when none has. */
    val lastCheckAt: Flow<Long> = context.pfpDataStore.data.map { it[KEY_LAST_CHECK_AT] ?: 0L }

    /** The last recorded outcome, or null before the first check. */
    val status: Flow<KbUpdateStatus?> = context.pfpDataStore.data.map { decodeStatus(it[KEY_LAST_RESULT]) }

    suspend fun setAutoUpdate(enabled: Boolean) {
        context.pfpDataStore.edit { it[KEY_AUTO_UPDATE] = enabled }
    }

    /**
     * Runs one update check. An automatic check ([manual] false) returns [KbUpdateResult.Skipped],
     * touching nothing, when the toggle is off or the last check was under 24 h ago; a manual one
     * bypasses both. Cancellation propagates; every other failure is a result.
     */
    suspend fun check(manual: Boolean): KbUpdateResult = mutex.withLock {
        if (!manual && !isDue()) return KbUpdateResult.Skipped
        if (!isConfigured) return finish(KbUpdateResult.NotConfigured, reachedServer = false)

        val body = when (val r = downloader.fetch(EmulatorKbDownloader.MANIFEST_URL, EmulatorKbDownloader.MAX_MANIFEST_BYTES)) {
            is KbDownload.Bytes -> r.bytes
            is KbDownload.Failure -> return finish(downloadFailure(r))
        }
        val signature = when (val r = downloader.fetch(EmulatorKbDownloader.SIGNATURE_URL, EmulatorKbDownloader.MAX_SIGNATURE_BYTES)) {
            is KbDownload.Bytes -> String(r.bytes, Charsets.UTF_8)
            is KbDownload.Failure -> return finish(downloadFailure(r))
        }
        if (!verifier.verify(body, signature)) return finish(KbUpdateResult.SignatureInvalid)

        val text = String(body, Charsets.UTF_8)
        val doc = when (val decoded = EmulatorKbDecoder.decode(text)) {
            is EmulatorKbDecode.Decoded -> decoded.document
            // A newer schema may add top-level keys this decoder refuses; it is still "needs app update".
            is EmulatorKbDecode.Rejected ->
                return finish(if (declaredSchema(text) > SUPPORTED_SCHEMA) KbUpdateResult.NeedsAppUpdate else KbUpdateResult.InvalidContent)
        }
        if (doc.schemaVersion > SUPPORTED_SCHEMA || doc.minAppVersion > appVersion()) {
            return finish(KbUpdateResult.NeedsAppUpdate)
        }
        val validation = EmulatorKbValidator.validate(doc, KnownPlatformIds.ALL, context.packageName)
        if (validation.refusedEmulators.isNotEmpty() || validation.refusedPlatforms.isNotEmpty()) {
            Timber.w("Refusing the official update: %d entries refused", validation.refusedEmulators.size + validation.refusedPlatforms.size)
            return finish(KbUpdateResult.InvalidContent)
        }
        // The floor is the higher of the last accepted file and the built-in KB. Equal to the former is
        // allowed so a reset can re-fetch, but a file no newer than the built-in would change nothing.
        if (doc.version < readHighestVersion() || doc.version <= store.builtInVersion()) {
            return finish(KbUpdateResult.OlderThanInstalled)
        }

        // Record the high-water mark first: a crash after the install must not leave a lower replay acceptable.
        if (!writeHighestVersion(doc.version)) return finish(KbUpdateResult.StorageFailed)
        if (!store.installOfficial(body, doc)) return finish(KbUpdateResult.StorageFailed)
        try {
            refresher.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Refreshing emulators after the knowledge update failed")
        }
        finish(
            KbUpdateResult.Installed,
            installed = KbUpdateStatus(
                KbUpdateResult.Installed, doc.version, doc.label,
                validation.emulators.size, validation.platforms.size,
            ),
        )
    }

    private suspend fun isDue(): Boolean {
        val prefs = context.pfpDataStore.data.first()
        if (prefs[KEY_AUTO_UPDATE] == false) return false
        val elapsed = clock() - (prefs[KEY_LAST_CHECK_AT] ?: 0L)
        // A last check in the future (clock set back) counts as due rather than blocking for days.
        return elapsed !in 0 until CHECK_INTERVAL_MS
    }

    private fun downloadFailure(failure: KbDownload.Failure) =
        if (failure.reason == "too large") KbUpdateResult.TooLarge else KbUpdateResult.Offline

    /** Records [result]; a failure keeps the installed file's fields from the previous status. */
    private suspend fun finish(
        result: KbUpdateResult,
        installed: KbUpdateStatus? = null,
        reachedServer: Boolean = true,
    ): KbUpdateResult {
        context.pfpDataStore.edit { prefs ->
            val status = installed ?: decodeStatus(prefs[KEY_LAST_RESULT])?.copy(result = result) ?: KbUpdateStatus(result)
            prefs[KEY_LAST_RESULT] = STATUS_JSON.encodeToString(KbUpdateStatus.serializer(), status)
            if (reachedServer) prefs[KEY_LAST_CHECK_AT] = clock()
        }
        return result
    }

    private val stateFile get() = File(context.filesDir, "emulator_kb/official/$STATE_FILE")

    private suspend fun readHighestVersion(): Long = withContext(Dispatchers.IO) {
        val file = stateFile
        if (!file.isFile || file.length() > MAX_STATE_BYTES) return@withContext 0L
        try {
            STATE_JSON.decodeFromString<State>(file.readText()).highestVersion
        } catch (e: IOException) {
            Timber.w(e, "Failed to read the knowledge update state")
            0L
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "The knowledge update state is unreadable")
            0L
        }
    }

    /** Stores the new high-water mark (the version gate already ensured it is not lower). Temp file, then rename. False when it could not be written. */
    private suspend fun writeHighestVersion(version: Long): Boolean = withContext(Dispatchers.IO) {
        val target = stateFile
        val temp = File(target.parentFile, "$STATE_FILE.tmp")
        try {
            target.parentFile?.mkdirs()
            temp.writeText(STATE_JSON.encodeToString(State.serializer(), State(version)))
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (e: IOException) {
            Timber.e(e, "Failed to record the highest accepted knowledge version")
            false
        } finally {
            temp.delete()
        }
    }

    private fun declaredSchema(text: String): Int = try {
        ((Json.parseToJsonElement(text) as? JsonObject)?.get("schemaVersion") as? JsonPrimitive)?.intOrNull ?: 0
    } catch (_: IllegalArgumentException) {
        0
    }

    private fun decodeStatus(text: String?): KbUpdateStatus? = text?.let {
        try {
            STATUS_JSON.decodeFromString(KbUpdateStatus.serializer(), it)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    @Serializable
    private class State(val highestVersion: Long = 0)

    companion object {
        // Not added to BackupManager: backup is disabled and the KB stays out of it (AD-13).
        val KEY_AUTO_UPDATE = booleanPreferencesKey("emulator_kb_auto_update")
        val KEY_LAST_CHECK_AT = longPreferencesKey("emulator_kb_last_check_at")
        val KEY_LAST_RESULT = stringPreferencesKey("emulator_kb_last_result")

        const val SUPPORTED_SCHEMA = EmulatorKbDecoder.SCHEMA_VERSION
        private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val STATE_FILE = "state.json"
        private const val MAX_STATE_BYTES = 4096L
        private val STATE_JSON = Json { ignoreUnknownKeys = true }
        private val STATUS_JSON = Json { ignoreUnknownKeys = true }
    }
}
