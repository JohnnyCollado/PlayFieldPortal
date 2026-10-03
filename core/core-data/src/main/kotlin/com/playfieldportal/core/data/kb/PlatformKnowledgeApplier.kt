package com.playfieldportal.core.data.kb

import android.content.Context
import androidx.room.withTransaction
import com.playfieldportal.core.data.database.PFPDatabase
import com.playfieldportal.core.data.database.seeder.PlatformSeeder
import com.playfieldportal.core.domain.model.emulatorkb.PlatformExtensionPlanner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

/** One console that gained file types, and which ones. */
data class PlatformGain(val platformId: String, val added: List<String>)

/**
 * Applies the knowledge base's platform extensions to Room (AD-9). Additive only, and a row is
 * written only while the user has not customized it; [PlatformExtensionPlanner] makes both calls.
 *
 * A platform and its Memory Card (one per platform) are planned and written in one Room
 * transaction. The last KB-applied set per platform lives in `emulator_kb/platform_applied.json`
 * and is saved only after that transaction commits, so a Room failure leaves it unchanged. If the
 * save itself fails after the commit, the written rows already equal their target, so the next run
 * is a no-op for them; only the guard's memory of the applied set is lost.
 *
 * No rescan is triggered: new extensions take effect on the next scan.
 */
@Singleton
class PlatformKnowledgeApplier internal constructor(
    private val db: PFPDatabase,
    private val appliedFile: File,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        db: PFPDatabase,
    ) : this(db, File(context.filesDir, "emulator_kb/$APPLIED_FILE"))

    private val mutex = Mutex()

    /**
     * Applies [extensionsByPlatform] (platform id to KB extensions). Unknown platform ids are skipped.
     * Returns the consoles that gained extensions on this run, empty when nothing was written.
     */
    suspend fun apply(extensionsByPlatform: Map<String, List<String>>): List<PlatformGain> = mutex.withLock {
        val applied = readApplied().toMutableMap()
        val before = applied.toMap()
        val gains = db.withTransaction {
            val platformDao = db.platformDao()
            val cardDao = db.memoryCardDao()
            val result = mutableListOf<PlatformGain>()
            for ((id, kbList) in extensionsByPlatform) {
                val platform = platformDao.getById(id) ?: continue
                val card = cardDao.getById(id)
                val plan = PlatformExtensionPlanner.plan(
                    seedDefault = split(SEED_EXTENSIONS[id].orEmpty()),
                    lastApplied = applied[id],
                    kbList = kbList,
                    platformCurrent = split(platform.romExtensions),
                    cards = listOfNotNull(card).map { split(it.supportedExtensions) },
                )
                plan.platform?.let { platformDao.setRomExtensions(id, it.joinToString(",")) }
                plan.cards.firstOrNull()?.let { cardDao.setSupportedExtensions(id, it.joinToString(",")) }
                applied[id] = plan.lastApplied
                if (plan.added.isNotEmpty()) result += PlatformGain(id, plan.added)
            }
            result
        }
        if (applied != before) writeApplied(applied)
        gains
    }

    private suspend fun readApplied(): Map<String, List<String>> = withContext(Dispatchers.IO) {
        try {
            if (!appliedFile.isFile) return@withContext emptyMap()
            JSON.decodeFromString(APPLIED_SERIALIZER, appliedFile.readText())
        } catch (e: IOException) {
            Timber.w(e, "Failed to read the applied platform knowledge")
            emptyMap()
        } catch (e: IllegalArgumentException) {
            // A corrupt record only means the guard has no memory; rows still must equal the seed.
            Timber.w(e, "The applied platform knowledge is unreadable")
            emptyMap()
        }
    }

    /** Temp file, then rename, so a crash leaves the previous record intact. */
    private suspend fun writeApplied(applied: Map<String, List<String>>) = withContext(Dispatchers.IO) {
        val dir = appliedFile.parentFile ?: return@withContext
        val temp = File(dir, "${appliedFile.name}.tmp")
        try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create ${dir.name}")
            temp.writeText(JSON.encodeToString(APPLIED_SERIALIZER, applied))
            try {
                Files.move(temp.toPath(), appliedFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), appliedFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            Timber.e(e, "Failed to save the applied platform knowledge")
        } finally {
            temp.delete()
        }
    }

    private fun split(csv: String): List<String> = csv.split(",").filter { it.isNotBlank() }

    private companion object {
        const val APPLIED_FILE = "platform_applied.json"
        val JSON = Json { ignoreUnknownKeys = true }
        val APPLIED_SERIALIZER = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
        val SEED_EXTENSIONS: Map<String, String> =
            PlatformSeeder.DEFAULT_PLATFORMS.associate { it.id to it.romExtensions }
    }
}
