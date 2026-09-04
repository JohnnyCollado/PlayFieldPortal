package com.playfieldportal.feature.launcher

import android.content.Context
import android.content.pm.PackageManager
import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.EmulatorProfileAdmission
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

// Injected rather than hardcoded so the dispatcher is part of this repository's contract and a
// test can pin that its file reads really do leave the caller's thread. Mirrors the pattern
// LibraryScanner already uses for @ScannerIoDispatcher.
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ProfileIoDispatcher

@Module
@InstallIn(SingletonComponent::class)
object EmulatorProfileModule {
    @Provides
    @ProfileIoDispatcher
    fun provideProfileIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}

/**
 * Owns the emulator profile set: bundled defaults merged with whatever the user has saved.
 *
 * Both of this class's contracts are deliberate and were previously implicit.
 *
 * **It declares its dispatcher.** Every accessor that touches disk is `suspend` and hops to [io].
 * The reads used to sit behind plain functions called from `viewModelScope`, so a game launch
 * parsed JSON off the UI thread; the suspend siblings were safe only because the one caller
 * happened to use an IO scope.
 *
 * **It does not trust what it loads.** A persisted profile chooses the `ComponentName` a launch
 * intent targets and its package receives `grantUriPermission(...)` for the ROM, and the file can
 * arrive from a restored backup. Everything read off disk goes through [EmulatorProfileAdmission].
 */
@Singleton
class EmulatorProfileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @ProfileIoDispatcher private val io: CoroutineDispatcher,
) {
    private val _profiles = MutableStateFlow<List<EmulatorProfile>>(emptyList())
    val profiles: Flow<List<EmulatorProfile>> = _profiles.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun initialize() {
        val bundled  = withContext(io) { loadBundledProfiles() }
        val persisted = withContext(io) { loadPersistedProfiles() }
        _profiles.value = mergeProfiles(bundled, persisted)
        Timber.i("Emulator profiles loaded: ${bundled.size} bundled, ${persisted.size} persisted")
    }

    // Returns every profile saved to local storage (custom + auto-generated).
    // Used by EmulatorAutoConfigService to check existing entries.
    suspend fun getAllPersistedProfiles(): List<EmulatorProfile> = withContext(io) { loadPersistedProfiles() }

    // Reads the in-memory set only; the package-manager queries are cheap and involve no disk of
    // ours, so this one stays non-suspend.
    fun getInstalledProfiles(): List<EmulatorProfile> {
        val pm = context.packageManager
        return _profiles.value.filter { profile ->
            try { pm.getPackageInfo(profile.packageName, 0); true }
            catch (_: PackageManager.NameNotFoundException) { false }
        }
    }

    // Ordered so the automatic pick (first entry) is a standalone emulator when one is installed,
    // with RetroArch cores after it (see EmulatorLaunchPreference). Unavailable profiles — e.g. a
    // RetroArch core detected as NOT installed via the SAF link — are excluded so they can never be
    // launched into a black screen.
    // Suspend even though the current implementation reads memory: the profile set is loaded from
    // disk, and callers reach this during a game launch. Declaring it here keeps a future change
    // that re-reads the file from silently reintroducing a main-thread parse.
    suspend fun getProfilesForPlatform(platformId: String): List<EmulatorProfile> = withContext(io) {
        getInstalledProfiles()
            .filter { it.isAvailable && it.supportsPlatform(platformId) }
            .byLaunchPreference()
    }

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
    suspend fun savePersistedProfile(profile: EmulatorProfile) = withContext(io) {
        val current = loadPersistedProfiles().toMutableList()
        val idx = current.indexOfFirst { it.id == profile.id }
        if (idx >= 0) current[idx] = profile else current.add(profile)
        _profiles.value = mergeProfiles(loadBundledProfiles(), current)
        persistProfiles(current)
    }

    suspend fun deleteCustomProfile(id: String) = withContext(io) {
        val current = loadPersistedProfiles().filter { it.id != id }
        _profiles.value = mergeProfiles(loadBundledProfiles(), current)
        persistProfiles(current)
    }

    /**
     * Clears all persisted (auto-generated + custom) emulator profiles and reloads bundled
     * defaults. Does not touch the game library, ROM paths, artwork, saves, or metadata.
     */
    suspend fun resetPersistedProfiles() = withContext(io) {
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

    // Blocking by design; every caller reaches it through a withContext(io) hop above.
    private fun loadPersistedProfiles(): List<EmulatorProfile> {
        val parsed = try {
            val file = java.io.File(context.filesDir, "emulator_profiles/custom_profiles.json")
            if (!file.exists()) return emptyList()
            json.decodeFromString<List<EmulatorProfile>>(file.readText())
        } catch (e: Exception) {
            Timber.e(e, "Failed to load persisted emulator profiles")
            return emptyList()
        }
        // This file can arrive from a restored backup, and a profile decides an intent target that
        // then receives a URI grant. RestoreArchive already filters it, but the check belongs here
        // too: this is where the bytes actually become a launchable profile.
        val admitted = EmulatorProfileAdmission.admit(parsed, selfPackage = context.packageName)
        admitted.refused.forEach {
            Timber.w("Ignoring inadmissible persisted emulator profile %s: %s", it.id, it.reason)
        }
        return admitted.admitted
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

    // supportsPlatform / platformAliases live in EmulatorPlatformMapping.kt (shared with the
    // intent resolver and the launch ladder) so no copy can drift.
}
