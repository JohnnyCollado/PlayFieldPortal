package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import com.playfieldportal.core.data.repository.CoreInventory
import com.playfieldportal.core.data.repository.RetroArchLink
import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.IntentType
import com.playfieldportal.core.domain.model.emulatorkb.EffectiveKb
import com.playfieldportal.core.domain.model.emulatorkb.EffectiveKbEmulator
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbEmulator
import com.playfieldportal.core.domain.model.emulatorkb.KbSource
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.launcher.AppLaunchInspector
import com.playfieldportal.feature.launcher.EmulatorIntentResolver
import com.playfieldportal.feature.launcher.EmulatorProfileRepository
import com.playfieldportal.feature.launcher.kb.EmulatorKnowledgeRefresher
import com.playfieldportal.feature.launcher.kb.EmulatorKnowledgeStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Editing keeps what the editor cannot show, package checks stay off Main, and rescans go through the refresher. */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION")
class EmulatorsSettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val profileRepository = mockk<EmulatorProfileRepository>(relaxed = true)
    private val knowledgeStore = mockk<EmulatorKnowledgeStore>(relaxed = true)
    private val retroArchLink = mockk<RetroArchLink>(relaxed = true)
    private val refresher = mockk<EmulatorKnowledgeRefresher>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val packageManager = mockk<PackageManager>(relaxed = true)

    private val profiles = MutableStateFlow<List<EmulatorProfile>>(emptyList())
    private val effective = MutableStateFlow(EffectiveKb(emptyList(), emptyList(), officialApplied = false))

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { context.packageManager } returns packageManager
        every { profileRepository.profiles } returns profiles
        every { knowledgeStore.effective } returns effective
        coEvery { knowledgeStore.current() } coAnswers { effective.value }
        coEvery { retroArchLink.inventory() } returns CoreInventory.Unlinked
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun buildVm() = EmulatorsSettingsViewModel(
        profileRepository,
        mockk<AppLaunchInspector>(relaxed = true),
        knowledgeStore,
        mockk<GameRepository>(relaxed = true),
        mockk<EmulatorIntentResolver>(relaxed = true),
        retroArchLink,
        refresher,
        context,
    )

    private fun pinned() = EmulatorProfile(
        id = "mine", name = "Mine", packageName = "org.example.mine", intentType = IntentType.COMPONENT,
        supportedPlatformIds = listOf("psx"), isCustom = true,
        knowledgeId = "kb-mine", signerSha256 = listOf("a".repeat(64)),
        intentArrayExtras = mapOf("AppStartParameters" to listOf("-r", "{title_id}")), attachRomData = true,
        customCommand = "rm -rf /",
    )

    @Test
    fun `editing a profile keeps its knowledge id, signer pin and launch extras and writes no custom command`() = runTest(dispatcher) {
        val original = pinned()
        profiles.value = listOf(original)
        val vm = buildVm()
        eventually("the profile is listed") { vm.uiState.value.customProfiles.isNotEmpty() }
        val saved = slot<EmulatorProfile>()
        coEvery { profileRepository.savePersistedProfile(capture(saved)) } returns Unit

        vm.openEditor("mine")
        vm.updateEditorName("Renamed")
        vm.saveEditorProfile()
        advanceUntilIdle()

        assertEquals("Renamed", saved.captured.name)
        assertEquals(original.knowledgeId, saved.captured.knowledgeId)
        assertEquals(original.signerSha256, saved.captured.signerSha256)
        assertEquals(original.intentArrayExtras, saved.captured.intentArrayExtras)
        assertTrue(saved.captured.attachRomData)
        assertNull(saved.captured.customCommand)
    }

    @Test
    fun `package checks for the available list do not run on the main thread`() = runTest(dispatcher) {
        val mainThread = Thread.currentThread()
        val checkedOn = CopyOnWriteArrayList<Thread>()
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } answers {
            checkedOn += Thread.currentThread()
            mockk<PackageInfo>()
        }
        effective.value = EffectiveKb(
            listOf(
                EffectiveKbEmulator(
                    EmulatorKbEmulator(id = "a", name = "A", packageNames = listOf("org.a"), platformIds = listOf("psx")),
                    KbSource.BuiltIn, null,
                ),
            ),
            emptyList(), officialApplied = false,
        )
        buildVm()

        eventually("the package was checked") { checkedOn.isNotEmpty() }

        assertNotEquals(mainThread, checkedOn.first())
    }

    @Test
    fun `linking, rescanning and unlinking RetroArch run the knowledge refresher`() = runTest(dispatcher) {
        coEvery { retroArchLink.inventory() } returns CoreInventory.Verified(setOf("snes9x_libretro_android.so"))
        val vm = buildVm()
        advanceUntilIdle()

        vm.linkRetroArch(mockk<Uri>())
        advanceUntilIdle()
        vm.redetectRetroArchCores()
        advanceUntilIdle()
        vm.unlinkRetroArch()
        advanceUntilIdle()

        coVerify(exactly = 3) { refresher.run() }
    }
}
