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
        val bundled = loadBundledProfiles()
        val custom  = loadCustomProfiles()
        _profiles.value = bundled + custom
        Timber.i("Emulator profiles loaded: ${bundled.size} bundled, ${custom.size} custom")
    }

    fun getInstalledProfiles(): List<EmulatorProfile> {
        val pm = context.packageManager
        return _profiles.value.filter { profile ->
            try {
                pm.getPackageInfo(profile.packageName, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    fun getProfilesForPlatform(platformId: String): List<EmulatorProfile> {
        return getInstalledProfiles().filter { platformId in it.supportedPlatformIds }
    }

    fun getInstalledVersionCode(packageName: String): Long {
        return try {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (e: Exception) {
            -1L
        }
    }

    suspend fun saveCustomProfile(profile: EmulatorProfile) {
        val current = _profiles.value.toMutableList()
        val existing = current.indexOfFirst { it.id == profile.id }
        if (existing >= 0) current[existing] = profile else current.add(profile)
        _profiles.value = current
        persistCustomProfiles(current.filter { it.isCustom })
    }

    suspend fun deleteCustomProfile(id: String) {
        val current = _profiles.value.filter { it.id != id }
        _profiles.value = current
        persistCustomProfiles(current.filter { it.isCustom })
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

    private fun loadCustomProfiles(): List<EmulatorProfile> {
        return try {
            val file = java.io.File(
                context.filesDir,
                "emulator_profiles/custom_profiles.json"
            )
            if (!file.exists()) return emptyList()
            json.decodeFromString<List<EmulatorProfile>>(file.readText())
        } catch (e: Exception) {
            Timber.e(e, "Failed to load custom emulator profiles")
            emptyList()
        }
    }

    private fun persistCustomProfiles(profiles: List<EmulatorProfile>) {
        try {
            val dir = java.io.File(context.filesDir, "emulator_profiles")
            dir.mkdirs()
            val file = java.io.File(dir, "custom_profiles.json")
            file.writeText(json.encodeToString(ListSerializer(EmulatorProfile.serializer()), profiles))
        } catch (e: Exception) {
            Timber.e(e, "Failed to persist custom emulator profiles")
        }
    }
}
