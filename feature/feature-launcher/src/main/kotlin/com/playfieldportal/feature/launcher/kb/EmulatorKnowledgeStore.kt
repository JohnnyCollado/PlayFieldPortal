package com.playfieldportal.feature.launcher.kb

import android.content.Context
import com.playfieldportal.core.domain.model.emulatorkb.EffectiveKb
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbDecode
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbDecoder
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbDocument
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbEmulator
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbLayer
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbMerge
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbPlatform
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbValidation
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbValidator
import com.playfieldportal.core.domain.model.emulatorkb.UserKbLayer
import com.playfieldportal.feature.launcher.ProfileIoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One imported user file as the index records it. [unreadable] files contribute nothing but stay listed. */
data class KbUserFile(
    val id: String,
    val displayName: String,
    val unreadable: Boolean,
    val emulatorCount: Int,
)

/**
 * Owns the emulator knowledge layers and publishes the merged result (AD-4, AD-5, AD-13).
 *
 * Layout under `filesDir/emulator_kb/`: `official/emulators.json`, `user/index.json` and
 * `user/<id>.json`. Deliberately outside the backup roots, so a restore cannot become an import that
 * skips review.
 *
 * - **Every layer is re-validated on every load.** Bytes on disk are not trusted because they were
 *   valid when written.
 * - **Built-in and official are all or nothing** (a refusal there is a publishing bug); **user files
 *   are per entry**. A broken official file is ignored and the built-in layer stands. A broken user
 *   file stays in the index, marked unreadable, so the user can remove it.
 * - **Writes are temp file then rename**, so a crash leaves the previous file intact; a stale temp
 *   file is never read.
 *
 * Loads lazily, once, on [io]. [effective] starts empty and is filled by the first [current] or
 * mutator call. Nothing here triggers detection.
 */
@Singleton
class EmulatorKnowledgeStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @ProfileIoDispatcher private val io: CoroutineDispatcher,
) {
    private class UserEntry(val id: String, val name: String, val layer: EmulatorKbLayer?)

    private val mutex = Mutex()
    private var loaded = false
    private var builtIn = EmulatorKbLayer(emptyList(), emptyList(), 0)
    private var official: EmulatorKbLayer? = null
    private var users = emptyList<UserEntry>()

    private val _effective = MutableStateFlow(EffectiveKb(emptyList(), emptyList(), officialApplied = false))
    val effective: StateFlow<EffectiveKb> = _effective.asStateFlow()

    private val _userFiles = MutableStateFlow<List<KbUserFile>>(emptyList())
    val userFiles: StateFlow<List<KbUserFile>> = _userFiles.asStateFlow()

    private val kbDir get() = File(context.filesDir, "emulator_kb")
    private val officialFile get() = File(kbDir, "official/$OFFICIAL_FILE")
    private val userDir get() = File(kbDir, "user")
    private val indexFile get() = File(userDir, INDEX_FILE)

    /** The built-in layer's version, loading the store first if nobody has yet. */
    suspend fun builtInVersion(): Long = locked { builtIn.version }

    /** The effective KB, loading it first if nobody has yet. */
    suspend fun current(): EffectiveKb = locked { _effective.value }

    /**
     * Stores [bytes] (the exact, signature-checked file) as the official layer. Returns false, writing
     * nothing, when [doc] has any refused entry: official files are all or nothing (AD-4).
     */
    suspend fun installOfficial(bytes: ByteArray, doc: EmulatorKbDocument): Boolean = locked {
        val result = validate(doc)
        if (result.refusedEmulators.isNotEmpty() || result.refusedPlatforms.isNotEmpty()) {
            Timber.w("Refusing official knowledge file: %s", describeRefusals(result))
            return@locked false
        }
        try {
            writeAtomically(officialFile, bytes)
        } catch (e: IOException) {
            Timber.e(e, "Failed to store the official knowledge file")
            return@locked false
        }
        official = EmulatorKbLayer(result.emulators, result.platforms, doc.version)
        publish()
        true
    }

    /**
     * Stores the valid entries of [doc] as a new user layer, applied after every existing one, and
     * returns its file id. The file is re-serialized from the validated entries, never the original
     * bytes. Throws [IOException] when it cannot be written or 32 files are already imported.
     */
    suspend fun addUserFile(displayName: String, doc: EmulatorKbDocument): String = locked {
        if (users.size >= MAX_USER_FILES) throw IOException("At most $MAX_USER_FILES knowledge files can be imported; remove one first")
        val result = validate(doc)
        result.refusedEmulators.forEach { Timber.w("User knowledge entry %s refused: %s", it.id, it.reason) }
        val id = UUID.randomUUID().toString()
        val name = displayName.take(MAX_NAME_CHARS)
        val file = userFile(id)
        writeAtomically(file, serialize(result.emulators, result.platforms).toByteArray())
        val updated = users + UserEntry(id, name, EmulatorKbLayer(result.emulators, result.platforms, 0))
        try {
            writeIndex(updated)
        } catch (e: IOException) {
            file.delete()
            throw e
        }
        users = updated
        publish()
        id
    }

    /** Removes a user file and its layer. False when [id] is not in the index. */
    suspend fun removeUserFile(id: String): Boolean = locked {
        if (users.none { it.id == id }) return@locked false
        val updated = users.filter { it.id != id }
        writeIndex(updated)
        users = updated
        userFile(id).delete()
        File(userDir, "$id.json.$TEMP_EXT").delete()
        publish()
        true
    }

    /** Deletes the official file and every user file, leaving the built-in layer alone. */
    suspend fun resetToBuiltIn() {
        locked {
            officialFile.delete()
            File(officialFile.parentFile, "$OFFICIAL_FILE.$TEMP_EXT").delete()
            userDir.deleteRecursively()
            official = null
            users = emptyList()
            publish()
        }
    }

    private suspend fun <T> locked(block: () -> T): T = withContext(io) {
        mutex.withLock {
            if (!loaded) {
                load()
                loaded = true
            }
            block()
        }
    }

    private fun load() {
        builtIn = loadBuiltIn()
        official = loadOfficial()
        users = loadIndex().map { entry ->
            val layer = readUserLayer(entry.id)
            if (layer == null) Timber.w("User knowledge file %s (%s) is unreadable", entry.id, entry.name)
            UserEntry(entry.id, entry.name, layer)
        }
        publish()
    }

    private fun publish() {
        _effective.value = EmulatorKbMerge.merge(builtIn, official, users.mapNotNull { u ->
            u.layer?.let { UserKbLayer(u.id, it) }
        })
        _userFiles.value = users.map {
            KbUserFile(it.id, it.name, unreadable = it.layer == null, emulatorCount = it.layer?.emulators?.size ?: 0)
        }
    }

    private fun validate(doc: EmulatorKbDocument): EmulatorKbValidation =
        EmulatorKbValidator.validate(doc, KnownPlatformIds.ALL, context.packageName)

    /** Decodes and validates [text]; null (with a warning) when it cannot be decoded at all. */
    private fun decodeAndValidate(text: String, what: String): Pair<EmulatorKbDocument, EmulatorKbValidation>? =
        when (val decoded = EmulatorKbDecoder.decode(text)) {
            is EmulatorKbDecode.Rejected -> {
                Timber.w("The %s knowledge file %s", what, decoded.reason)
                null
            }
            is EmulatorKbDecode.Decoded -> decoded.document to validate(decoded.document)
        }

    private fun loadBuiltIn(): EmulatorKbLayer {
        val empty = EmulatorKbLayer(emptyList(), emptyList(), 0)
        return try {
            val text = context.assets.open(BUILT_IN_ASSET).bufferedReader().use { it.readText() }
            val (doc, result) = decodeAndValidate(text, "built-in") ?: return empty
            if (result.refusedEmulators.isNotEmpty() || result.refusedPlatforms.isNotEmpty()) {
                Timber.e("The built-in knowledge file has refused entries: %s", describeRefusals(result))
                return empty
            }
            EmulatorKbLayer(result.emulators, result.platforms, doc.version)
        } catch (e: IOException) {
            Timber.e(e, "Failed to read the built-in knowledge file")
            empty
        }
    }

    private fun loadOfficial(): EmulatorKbLayer? {
        val text = readCapped(officialFile) ?: return null
        val (doc, result) = decodeAndValidate(text, "official") ?: return null
        if (result.refusedEmulators.isNotEmpty() || result.refusedPlatforms.isNotEmpty()) {
            Timber.w("Ignoring the official knowledge file: %s", describeRefusals(result))
            return null
        }
        return EmulatorKbLayer(result.emulators, result.platforms, doc.version)
    }

    private fun readUserLayer(id: String): EmulatorKbLayer? {
        val text = readCapped(userFile(id)) ?: return null
        val (_, result) = decodeAndValidate(text, "user") ?: return null
        result.refusedEmulators.forEach { Timber.w("User knowledge entry %s refused: %s", it.id, it.reason) }
        return EmulatorKbLayer(result.emulators, result.platforms, 0)
    }

    private fun loadIndex(): List<IndexEntry> {
        val text = readCapped(indexFile) ?: return emptyList()
        return try {
            // An id names a file, so a tampered index must not be able to point outside the user folder.
            INDEX_JSON.decodeFromString<UserIndex>(text).files.filter { FILE_ID.matches(it.id) }
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "The user knowledge index is unreadable")
            emptyList()
        }
    }

    private fun writeIndex(entries: List<UserEntry>) {
        val index = UserIndex(entries.map { IndexEntry(it.id, it.name) })
        writeAtomically(indexFile, INDEX_JSON.encodeToString(UserIndex.serializer(), index).toByteArray())
    }

    private fun userFile(id: String) = File(userDir, "$id.json")

    /** The file's text, or null when it is absent, too large or unreadable. */
    private fun readCapped(file: File): String? = try {
        when {
            !file.isFile -> null
            file.length() > MAX_FILE_BYTES -> {
                Timber.w("Knowledge file %s is too large to read", file.name)
                null
            }
            else -> file.readText()
        }
    } catch (e: IOException) {
        Timber.w(e, "Failed to read knowledge file %s", file.name)
        null
    }

    private fun serialize(emulators: List<EmulatorKbEmulator>, platforms: List<EmulatorKbPlatform>): String {
        val doc = buildJsonObject {
            put("format", EmulatorKbDecoder.FORMAT)
            put("schemaVersion", EmulatorKbDecoder.SCHEMA_VERSION)
            put("version", 0)
            put("label", "")
            put("emulators", JsonArray(emulators.map { WRITE_JSON.encodeToJsonElement(EmulatorKbEmulator.serializer(), it) }))
            put("platforms", JsonArray(platforms.map { WRITE_JSON.encodeToJsonElement(EmulatorKbPlatform.serializer(), it) }))
        }
        return doc.toString()
    }

    /** Temp file, then rename, so readers see the old file or the new one and never a partial write. */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        val dir = target.parentFile ?: throw IOException("No parent for ${target.name}")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create ${dir.name}")
        val temp = File(dir, "${target.name}.$TEMP_EXT")
        try {
            temp.writeBytes(bytes)
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    private fun describeRefusals(result: EmulatorKbValidation): String =
        (result.refusedEmulators + result.refusedPlatforms).joinToString("; ") { "${it.id ?: "#${it.index}"} ${it.reason}" }

    @Serializable
    private class IndexEntry(val id: String, val name: String)

    @Serializable
    private class UserIndex(val files: List<IndexEntry> = emptyList())

    private companion object {
        const val BUILT_IN_ASSET = "emulator_kb/emulators.json"
        const val OFFICIAL_FILE = "emulators.json"
        const val INDEX_FILE = "index.json"
        const val TEMP_EXT = "tmp"
        const val MAX_NAME_CHARS = 128
        const val MAX_USER_FILES = 32
        const val MAX_FILE_BYTES = 4L * EmulatorKbDecoder.MAX_CHARS
        val FILE_ID = Regex("[A-Za-z0-9-]{1,64}")
        val INDEX_JSON = Json { ignoreUnknownKeys = true }

        // Defaults are omitted from the output; the decoder restores them.
        val WRITE_JSON = Json { encodeDefaults = false }
    }
}
