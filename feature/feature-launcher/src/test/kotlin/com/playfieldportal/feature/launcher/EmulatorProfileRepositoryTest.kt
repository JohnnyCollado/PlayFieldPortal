package com.playfieldportal.feature.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineDispatcher
import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.IntentType
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Two properties this repository did not have.
 *
 * It read and JSON-parsed a file from plain non-suspend getters, and those getters were called
 * from `viewModelScope` during game launch — so the read happened on `Dispatchers.Main.immediate`
 * every time a game started. Nothing in the signature said so. Its sibling suspend functions were
 * safe only because `PFPApplication.appScope` happens to be `Dispatchers.IO`, which is a property
 * of the call site rather than of the repository.
 *
 * It also loaded persisted profiles verbatim, and a persisted profile decides a ComponentName and
 * receives a URI grant at launch.
 */
@RunWith(RobolectricTestRunner::class)
class EmulatorProfileRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var profilesFile: File

    private fun profile(
        id: String,
        packageName: String = "org.example.emu",
        customCommand: String? = null,
        intentType: IntentType = IntentType.COMPONENT,
    ) = EmulatorProfile(
        id = id,
        name = "Profile $id",
        packageName = packageName,
        activityClass = "$packageName.Main",
        intentType = intentType,
        supportedPlatformIds = listOf("psx"),
        customCommand = customCommand,
        isCustom = true,
    )

    @Before
    fun setUp() {
        profilesFile = File(context.filesDir, "emulator_profiles/custom_profiles.json")
        profilesFile.parentFile?.mkdirs()
        profilesFile.delete()
    }

    private fun writePersisted(vararg profiles: EmulatorProfile) {
        profilesFile.writeText(
            json.encodeToString(ListSerializer(EmulatorProfile.serializer()), profiles.toList()),
        )
    }

    private fun repository(dispatcher: CoroutineDispatcher) =
        EmulatorProfileRepository(context, dispatcher)

    // ── Dispatcher ────────────────────────────────────────────────────────────

    @Test
    fun `persisted profiles are read on the injected dispatcher, not the caller's thread`() = runTest {
        writePersisted(profile("a"))
        val io = StandardTestDispatcher(testScheduler, name = "io")

        val repo = repository(io)
        // If the read ran inline on the test's dispatcher this would return before the scheduler
        // ever advanced; requiring a scheduler turn is what pins the withContext hop.
        repo.initialize()

        assertEquals(listOf("a"), repo.getAllPersistedProfiles().map { it.id })
    }

    @Test
    fun `getProfilesForPlatform is suspend so its disk read cannot land on the main thread`() = runTest {
        writePersisted(profile("a"))
        val repo = repository(StandardTestDispatcher(testScheduler))

        repo.initialize()

        // Compiles only because the function is suspend — the regression guard is the signature.
        val result: List<EmulatorProfile> = repo.getProfilesForPlatform("psx")
        assertTrue(result.all { "psx" in it.supportedPlatformIds })
    }

    // ── Admission ─────────────────────────────────────────────────────────────

    @Test
    fun `a persisted profile carrying a custom command is not loaded`() = runTest {
        writePersisted(
            profile("safe"),
            profile("hostile", customCommand = "su -c wipe", intentType = IntentType.CUSTOM_COMMAND),
        )
        val repo = repository(StandardTestDispatcher(testScheduler))

        repo.initialize()

        assertEquals(listOf("safe"), repo.getAllPersistedProfiles().map { it.id })
    }

    @Test
    fun `a persisted profile targeting this app's own package is not loaded`() = runTest {
        writePersisted(profile("self", packageName = context.packageName))
        val repo = repository(StandardTestDispatcher(testScheduler))

        repo.initialize()

        assertTrue(repo.getAllPersistedProfiles().isEmpty())
    }

    @Test
    fun `an unreadable profiles file yields no profiles rather than throwing`() = runTest {
        profilesFile.writeText("{{{ not json")
        val repo = repository(StandardTestDispatcher(testScheduler))

        repo.initialize()

        assertTrue(repo.getAllPersistedProfiles().isEmpty())
    }

    @Test
    fun `a missing profiles file is not an error`() = runTest {
        val repo = repository(StandardTestDispatcher(testScheduler))

        repo.initialize()

        assertTrue(repo.getAllPersistedProfiles().isEmpty())
    }
}
