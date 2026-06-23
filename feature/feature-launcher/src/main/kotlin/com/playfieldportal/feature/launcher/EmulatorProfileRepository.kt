package com.playfieldportal.feature.launcher

import android.content.Context
import android.content.pm.PackageManager
import com.playfieldportal.core.domain.model.EmulatorProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EmulatorProfileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _profiles = MutableStateFlow<List<EmulatorProfile>>(emptyList())
    val profiles: Flow<List<EmulatorProfile>> = _profiles.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun initialize() {
        val bundled  = loadBundledProfiles()
        val persisted = loadPersistedProfiles()
        _profiles.value = mergeProfiles(bundled, persisted)
        Timber.i("Emulator profiles loaded: ${bundled.size} bundled, ${persisted.size} persisted")
    }

    // Returns every profile saved to local storage (custom + auto-generated).
    // Used by EmulatorAutoConfigService to check existing entries.
    fun getAllPersistedProfiles(): List<EmulatorProfile> = loadPersistedProfiles()

    fun getInstalledProfiles(): List<EmulatorProfile> {
        val pm = context.packageManager
        return _profiles.value.filter { profile ->
            try { pm.getPackageInfo(profile.packageName, 0); true }
            catch (_: PackageManager.NameNotFoundException) { false }
        }
    }

    fun getProfilesForPlatform(platformId: String): List<EmulatorProfile> =
        getInstalledProfiles().filter { it.supportsPlatform(platformId) }

    fun getInstalledVersionCode(packageName: String): Long {
        return try {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                info.longVersionCode
            else
                @Suppress("DEPRECATION") info.versionCode.toLong()
        } catch (_: Exception) { -1L }
    }

    // Saves a user-created profile.
    suspend fun saveCustomProfile(profile: EmulatorProfile) =
        savePersistedProfile(profile.copy(isCustom = true))

    // Saves any profile that should be persisted locally (custom or auto-generated).
    // Marks auto-generated edits with userModified when the caller is the settings editor.
    suspend fun savePersistedProfile(profile: EmulatorProfile) {
        val current = loadPersistedProfiles().toMutableList()
        val idx = current.indexOfFirst { it.id == profile.id }
        if (idx >= 0) current[idx] = profile else current.add(profile)
        _profiles.value = mergeProfiles(loadBundledProfiles(), current)
        persistProfiles(current)
    }

    suspend fun deleteCustomProfile(id: String) {
        val current = loadPersistedProfiles().filter { it.id != id }
        _profiles.value = mergeProfiles(loadBundledProfiles(), current)
        persistProfiles(current)
    }

    /**
     * Clears all persisted (auto-generated + custom) emulator profiles and reloads bundled
     * defaults. Does not touch the game library, ROM paths, artwork, saves, or metadata.
     */
    suspend fun resetPersistedProfiles() {
        try {
            val file = java.io.File(context.filesDir, "emulator_profiles/custom_profiles.json")
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete persisted profiles during reset")
        }
        _profiles.value = loadBundledProfiles()
        Timber.i("Emulator profiles reset to bundled defaults")
    }

    // Merges bundled (read-only) with persisted, deduping by id (persisted wins).
    private fun mergeProfiles(
        bundled: List<EmulatorProfile>,
        persisted: List<EmulatorProfile>,
    ): List<EmulatorProfile> {
        val persistedIds = persisted.map { it.id }.toSet()
        return bundled.filter { it.id !in persistedIds } + persisted
    }

    private fun loadBundledProfiles(): List<EmulatorProfile> {
        return try {
            val jsonStr = context.assets
                .open("emulator_profiles/bundled_profiles.json")
                .bufferedReader()
                .readText()
            json.decodeFromString<List<EmulatorProfile>>(jsonStr)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load bundled emulator profiles")
            emptyList()
        }
    }

    private fun loadPersistedProfiles(): List<EmulatorProfile> {
        return try {
            val file = java.io.File(context.filesDir, "emulator_profiles/custom_profiles.json")
            if (!file.exists()) return emptyList()
            json.decodeFromString<List<EmulatorProfile>>(file.readText())
        } catch (e: Exception) {
            Timber.e(e, "Failed to load persisted emulator profiles")
            emptyList()
        }
    }

    private fun persistProfiles(profiles: List<EmulatorProfile>) {
        try {
            val dir  = java.io.File(context.filesDir, "emulator_profiles")
            dir.mkdirs()
            val file = java.io.File(dir, "custom_profiles.json")
            file.writeText(json.encodeToString(ListSerializer(EmulatorProfile.serializer()), profiles))
        } catch (e: Exception) {
            Timber.e(e, "Failed to persist emulator profiles")
        }
    }

    private fun EmulatorProfile.supportsPlatform(platformId: String): Boolean {
        val aliases = platformAliases(platformId)
        return supportedPlatformIds.any { it in aliases }
    }

    private fun platformAliases(platformId: String): Set<String> = when (platformId) {
        "psx"          -> setOf("psx", "ps1")
        "ps1"          -> setOf("ps1", "psx")
        "n3ds"         -> setOf("n3ds", "3ds")
        "3ds"          -> setOf("3ds", "n3ds")
        "gc"           -> setOf("gc", "gamecube")
        "gamecube"     -> setOf("gamecube", "gc")
        "nds"          -> setOf("nds", "ds")
        "ds"           -> setOf("ds", "nds")
        "pcengine"     -> setOf("pcengine", "pce", "tgfx16")
        "pce"          -> setOf("pce", "pcengine", "tgfx16")
        "tgfx16"       -> setOf("tgfx16", "pce", "pcengine")
        "mastersystem" -> setOf("mastersystem", "sms")
        "sms"          -> setOf("sms", "mastersystem")
        "genesis"      -> setOf("genesis", "megadrive", "md")
        "megadrive"    -> setOf("megadrive", "genesis", "md")
        "md"           -> setOf("md", "genesis", "megadrive")
        "dreamcast"    -> setOf("dreamcast", "dc")
        "dc"           -> setOf("dc", "dreamcast")
        "virtualboy"   -> setOf("virtualboy", "vb")
        "vb"           -> setOf("vb", "virtualboy")
        "atarilynx"    -> setOf("atarilynx", "lynx")
        "lynx"         -> setOf("lynx", "atarilynx")
        "wonderswan"   -> setOf("wonderswan", "ws")
        "ws"           -> setOf("ws", "wonderswan")
        "wonderswancolor" -> setOf("wonderswancolor", "wsc")
        "wsc"          -> setOf("wsc", "wonderswancolor")
        "ngp"          -> setOf("ngp", "ngpc")
        "ngpc"         -> setOf("ngpc", "ngp")
        else           -> setOf(platformId)
    }
}
