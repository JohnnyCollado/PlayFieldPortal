package com.playfieldportal.feature.settings.viewmodel

import android.net.Uri
import com.playfieldportal.core.data.kb.PlatformGain
import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.IntentType
import com.playfieldportal.core.domain.model.emulatorkb.EffectiveKb
import com.playfieldportal.core.domain.model.emulatorkb.EffectiveKbEmulator
import com.playfieldportal.core.domain.model.emulatorkb.EffectiveKbPlatform
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbDecode
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbDecoder
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbValidator
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbDocument
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbEmulator
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbLaunch
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbPlatform
import com.playfieldportal.core.domain.model.emulatorkb.ImportItem
import com.playfieldportal.core.domain.model.emulatorkb.KbItem
import com.playfieldportal.core.domain.model.emulatorkb.KbSource
import com.playfieldportal.core.domain.model.emulatorkb.SignerState
import com.playfieldportal.feature.launcher.EmulatorProfileRepository
import com.playfieldportal.feature.launcher.kb.SignerProbe
import com.playfieldportal.feature.launcher.kb.EmulatorKbUpdater
import com.playfieldportal.feature.launcher.kb.EmulatorKnowledgeRefresher
import com.playfieldportal.feature.launcher.kb.EmulatorKnowledgeStore
import com.playfieldportal.feature.launcher.kb.KbUpdateResult
import com.playfieldportal.feature.launcher.kb.KbUpdateStatus
import com.playfieldportal.feature.launcher.kb.KbUserFile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 5.1: state and actions behind Settings > Emulators > Emulator knowledge. Reset is never a
 * one-press action, and a remove or reset is followed by a refresh so detection and platform
 * extensions follow the layers now in force.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmulatorKnowledgeViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val updater = mockk<EmulatorKbUpdater>(relaxed = true)
    private val store = mockk<EmulatorKnowledgeStore>(relaxed = true)
    private val refresher = mockk<EmulatorKnowledgeRefresher>(relaxed = true)
    private val signerProbe = mockk<SignerProbe>()
    private val profiles = mockk<EmulatorProfileRepository>()
    private var picked: KbPick = KbPick.TooLarge
    private var readGate: CompletableDeferred<Unit>? = null
    private var reads = 0
    private val reader = KbFileReader { reads++; readGate?.await(); picked }
    private val planDispatches = AtomicInteger()
    private val planDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            planDispatches.incrementAndGet()
            dispatcher.dispatch(context, block)
        }
    }
    private val written = mutableListOf<Pair<Uri, ByteArray>>()
    private var writeOk = true
    private val writer = KbFileWriter { target, bytes -> written += target to bytes; writeOk }

    private val autoUpdate = MutableStateFlow(true)
    private val lastCheckAt = MutableStateFlow(0L)
    private val status = MutableStateFlow<KbUpdateStatus?>(null)
    private val effective = MutableStateFlow(EffectiveKb(emptyList(), emptyList(), officialApplied = false))
    private val userFiles = MutableStateFlow<List<KbUserFile>>(emptyList())
    private val gained = MutableStateFlow<List<PlatformGain>>(emptyList())

    private val now = ZonedDateTime.of(2026, 10, 2, 12, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    private fun at(day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private lateinit var vm: EmulatorKnowledgeViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { updater.isConfigured } returns true
        every { updater.autoUpdateEnabled } returns autoUpdate
        every { updater.lastCheckAt } returns lastCheckAt
        every { updater.status } returns status
        every { store.effective } returns effective
        every { store.userFiles } returns userFiles
        every { refresher.gainedFileTypes } returns gained
        coEvery { updater.check(any()) } returns KbUpdateResult.Installed
        coEvery { store.removeUserFile(any()) } returns true
        coEvery { store.current() } coAnswers { effective.value }
        coEvery { store.addUserFile(any(), any()) } returns "file-id"
        coEvery { profiles.getAllPersistedProfiles() } returns emptyList()
        every { signerProbe.probe(any(), any()) } returns SignerState.NotInstalled
        vm = EmulatorKnowledgeViewModel(
            updater, store, refresher, reader, writer, signerProbe, profiles,
            setOf("psx"), "com.playfieldportal.launcher", { now }, TimeZone.getTimeZone("UTC"), planDispatcher,
        )
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun official(label: String = "2026.10.02", emulators: Int = 87, result: KbUpdateResult = KbUpdateResult.Installed) {
        effective.value = EffectiveKb(emptyList(), emptyList(), officialApplied = true)
        status.value = KbUpdateStatus(result, version = 20261002, label = label, emulatorCount = emulators)
    }

    @Test
    fun `the store is loaded when the screen opens`() = runTest(dispatcher) {
        advanceUntilIdle()

        coVerify(exactly = 1) { store.current() }
    }

    @Test
    fun `an installed official file reads as the mockup status line`() = runTest(dispatcher) {
        official()
        lastCheckAt.value = at(2, 9, 14)
        advanceUntilIdle()

        val ui = vm.uiState.value
        assertEquals("Official v2026.10.02 · verified · 87 emulators · checked today 09:14", ui.statusText)
        assertNull(ui.errorText)
    }

    @Test
    fun `the check time says yesterday and then the date`() = runTest(dispatcher) {
        official()
        lastCheckAt.value = at(1, 23, 5)
        advanceUntilIdle()
        assertEquals("Official v2026.10.02 · verified · 87 emulators · checked yesterday 23:05", vm.uiState.value.statusText)

        lastCheckAt.value = ZonedDateTime.of(2026, 9, 30, 8, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        advanceUntilIdle()
        assertEquals("Official v2026.10.02 · verified · 87 emulators · checked 30 Sep 08:00", vm.uiState.value.statusText)
    }

    @Test
    fun `one emulator is singular`() = runTest(dispatcher) {
        official(emulators = 1)
        advanceUntilIdle()

        assertEquals("Official v2026.10.02 · verified · 1 emulator", vm.uiState.value.statusText)
    }

    @Test
    fun `no official file reads as built-in only`() = runTest(dispatcher) {
        advanceUntilIdle()

        assertEquals("Built-in only", vm.uiState.value.statusText)
        assertNull(vm.uiState.value.errorText)
    }

    @Test
    fun `updates not configured reads as built-in with updates not configured`() = runTest(dispatcher) {
        status.value = KbUpdateStatus(KbUpdateResult.NotConfigured)
        advanceUntilIdle()

        assertEquals("Built-in · updates not configured", vm.uiState.value.statusText)
        assertNull(vm.uiState.value.errorText)
    }

    @Test
    fun `every failed result shows its message and keeps the installed file in the status`() = runTest(dispatcher) {
        val failures = KbUpdateResult.entries - setOf(KbUpdateResult.Installed, KbUpdateResult.NotConfigured, KbUpdateResult.Skipped)
        for (failure in failures) {
            official(result = failure)
            advanceUntilIdle()

            val ui = vm.uiState.value
            assertEquals("Official v2026.10.02 · verified · 87 emulators", ui.statusText, "result $failure")
            assertEquals(failure.message, ui.errorText, "result $failure")
        }
    }

    @Test
    fun `a failed check with no official file shows built-in only and the message`() = runTest(dispatcher) {
        status.value = KbUpdateStatus(KbUpdateResult.Offline)
        advanceUntilIdle()

        assertEquals("Built-in only", vm.uiState.value.statusText)
        assertEquals(KbUpdateResult.Offline.message, vm.uiState.value.errorText)
    }

    @Test
    fun `a stale status is not shown as official once a reset removed the file`() = runTest(dispatcher) {
        status.value = KbUpdateStatus(KbUpdateResult.Installed, version = 20261002, label = "2026.10.02", emulatorCount = 87)
        advanceUntilIdle()

        assertEquals("Built-in only", vm.uiState.value.statusText)
    }

    @Test
    fun `a manual check shows progress then the result`() = runTest(dispatcher) {
        val gate = CompletableDeferred<KbUpdateResult>()
        coEvery { updater.check(true) } coAnswers { gate.await() }
        advanceUntilIdle()

        vm.checkNow()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isChecking)
        assertNull(vm.uiState.value.checkResult)

        gate.complete(KbUpdateResult.Offline)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isChecking)
        assertEquals(KbUpdateResult.Offline.message, vm.uiState.value.checkResult)
    }

    @Test
    fun `a second press while checking does not start another check`() = runTest(dispatcher) {
        val gate = CompletableDeferred<KbUpdateResult>()
        coEvery { updater.check(true) } coAnswers { gate.await() }
        advanceUntilIdle()

        vm.checkNow()
        vm.checkNow()
        advanceUntilIdle()
        gate.complete(KbUpdateResult.Installed)
        advanceUntilIdle()

        coVerify(exactly = 1) { updater.check(true) }
    }

    @Test
    fun `starting a new check clears the previous result`() = runTest(dispatcher) {
        advanceUntilIdle()
        vm.checkNow()
        advanceUntilIdle()
        assertEquals(KbUpdateResult.Installed.message, vm.uiState.value.checkResult)

        val gate = CompletableDeferred<KbUpdateResult>()
        coEvery { updater.check(true) } coAnswers { gate.await() }
        vm.checkNow()
        advanceUntilIdle()

        assertNull(vm.uiState.value.checkResult)
        gate.complete(KbUpdateResult.Installed)
    }

    @Test
    fun `the auto update toggle persists through the updater and is mirrored`() = runTest(dispatcher) {
        advanceUntilIdle()
        assertTrue(vm.uiState.value.autoUpdate)

        coEvery { updater.setAutoUpdate(any()) } coAnswers { autoUpdate.value = firstArg() }
        vm.setAutoUpdate(false)
        advanceUntilIdle()

        coVerify(exactly = 1) { updater.setAutoUpdate(false) }
        assertFalse(vm.uiState.value.autoUpdate)
    }

    @Test
    fun `your files are listed from the store`() = runTest(dispatcher) {
        val files = listOf(KbUserFile("a", "mine.json", unreadable = false, emulatorCount = 2), KbUserFile("b", "bad.json", unreadable = true, emulatorCount = 0))
        userFiles.value = files
        advanceUntilIdle()

        assertEquals(files, vm.uiState.value.userFiles)
    }

    @Test
    fun `consoles that gained file types are surfaced`() = runTest(dispatcher) {
        gained.value = listOf(PlatformGain("psx", listOf("chd")))
        advanceUntilIdle()

        assertEquals(listOf(PlatformGain("psx", listOf("chd"))), vm.uiState.value.gainedFileTypes)
    }

    @Test
    fun `remove calls the store and then the refresher`() = runTest(dispatcher) {
        advanceUntilIdle()

        vm.removeFile("a")
        advanceUntilIdle()

        coVerifyOrder {
            store.removeUserFile("a")
            refresher.run()
        }
    }

    @Test
    fun `remove of a file that is already gone does not refresh`() = runTest(dispatcher) {
        coEvery { store.removeUserFile("gone") } returns false
        advanceUntilIdle()

        vm.removeFile("gone")
        advanceUntilIdle()

        coVerify(exactly = 0) { refresher.run() }
    }

    @Test
    fun `reset asks for confirmation before touching anything`() = runTest(dispatcher) {
        advanceUntilIdle()

        vm.requestReset()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.confirmResetVisible)
        coVerify(exactly = 0) { store.resetToBuiltIn() }
    }

    @Test
    fun `cancel dismisses the reset confirmation and resets nothing`() = runTest(dispatcher) {
        advanceUntilIdle()
        vm.requestReset()

        vm.dismissReset()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.confirmResetVisible)
        coVerify(exactly = 0) { store.resetToBuiltIn() }
    }

    @Test
    fun `confirm resets once then refreshes and closes the dialog`() = runTest(dispatcher) {
        advanceUntilIdle()
        vm.requestReset()

        vm.confirmReset()
        vm.confirmReset() // a double press must not reset twice
        advanceUntilIdle()

        coVerifyOrder {
            store.resetToBuiltIn()
            refresher.run()
        }
        coVerify(exactly = 1) { store.resetToBuiltIn() }
        assertFalse(vm.uiState.value.confirmResetVisible)
    }

    @Test
    fun `confirm without an open dialog does nothing`() = runTest(dispatcher) {
        advanceUntilIdle()

        vm.confirmReset()
        advanceUntilIdle()

        coVerify(exactly = 0) { store.resetToBuiltIn() }
        coVerify(exactly = 0) { refresher.run() }
    }

    // ---- Task 6.2: import ----

    private val uri = mockk<Uri>()

    private fun emulatorJson(id: String, activity: String = "com.example.$id.Main", extra: String = "") =
        """{"id":"$id","name":"Name $id","packageNames":["com.example.$id"],"platformIds":["psx"],
            "launch":{"intentType":"COMPONENT","activityClass":"$activity"}$extra}"""

    private fun fileJson(emulators: String, platforms: String = "") =
        """{"format":"pfp-emulator-kb","schemaVersion":1,"version":0,"label":"",
            "emulators":[$emulators],"platforms":[$platforms]}"""

    private fun pick(text: String, name: String? = "mine.json") {
        picked = KbPick.Content(name, text.toByteArray())
    }

    private fun existing(id: String, activity: String) = EmulatorKbEmulator(
        id = id, name = "Name $id", packageNames = listOf("com.example.$id"), platformIds = listOf("psx"),
        launch = EmulatorKbLaunch(IntentType.COMPONENT, activityClass = activity),
    )

    private fun psxWith(vararg extensions: String) = EffectiveKbPlatform(
        EmulatorKbPlatform("psx", extensions.toList()), KbSource.BuiltIn, null,
    )

    private fun TestScope.importFile() {
        advanceUntilIdle()
        vm.importFrom(uri)
        advanceUntilIdle()
    }

    @Test
    fun `a valid file opens a review with new checked and changes unchecked`() = runTest(dispatcher) {
        effective.value = EffectiveKb(
            listOf(EffectiveKbEmulator(existing("old", "com.example.old.Other"), KbSource.BuiltIn, null)),
            emptyList(), officialApplied = false,
        )
        pick(fileJson(emulatorJson("fresh") + "," + emulatorJson("old")))

        importFile()

        val review = vm.uiState.value.review!!
        assertEquals("mine.json", review.displayName)
        assertTrue(review.plan.items.filterIsInstance<ImportItem.New>().single().selected)
        assertFalse(review.plan.items.filterIsInstance<ImportItem.Change>().single().selected)
        assertEquals(1, review.plan.selectedCount)
        assertNull(vm.uiState.value.importNotice)
    }

    @Test
    fun `toggling a review item flips it`() = runTest(dispatcher) {
        pick(fileJson(emulatorJson("fresh")))
        importFile()

        vm.toggleReviewItem("emulator:fresh")

        assertEquals(0, vm.uiState.value.review!!.plan.selectedCount)
        assertFalse(vm.uiState.value.review!!.confirmEnabled)
    }

    @Test
    fun `confirm stores only the selected entries then refreshes and closes the review`() = runTest(dispatcher) {
        pick(fileJson(emulatorJson("one") + "," + emulatorJson("two")))
        importFile()
        vm.toggleReviewItem("emulator:two")
        val doc = slot<EmulatorKbDocument>()
        coEvery { store.addUserFile("mine.json", capture(doc)) } returns "file-id"

        vm.confirmImport()
        vm.confirmImport() // a double press must not store twice
        advanceUntilIdle()

        assertEquals(listOf("one"), doc.captured.emulators.map { (it as KbItem.Ok).value.id })
        coVerifyOrder {
            store.addUserFile("mine.json", any())
            refresher.run()
        }
        coVerify(exactly = 1) { store.addUserFile(any(), any()) }
        assertNull(vm.uiState.value.review)
    }

    @Test
    fun `cancel stores nothing and closes the review`() = runTest(dispatcher) {
        pick(fileJson(emulatorJson("one")))
        importFile()

        vm.cancelImport()
        vm.confirmImport()
        advanceUntilIdle()

        assertNull(vm.uiState.value.review)
        coVerify(exactly = 0) { store.addUserFile(any(), any()) }
        coVerify(exactly = 0) { refresher.run() }
    }

    @Test
    fun `an oversize file says too large and stores nothing`() = runTest(dispatcher) {
        picked = KbPick.TooLarge

        importFile()

        assertNull(vm.uiState.value.review)
        assertTrue(vm.uiState.value.importNotice!!.contains("too large"))
        coVerify(exactly = 0) { store.addUserFile(any(), any()) }
    }

    @Test
    fun `an unreadable file ends in a notice`() = runTest(dispatcher) {
        picked = KbPick.Unreadable

        importFile()

        assertNull(vm.uiState.value.review)
        assertTrue(vm.uiState.value.importNotice!!.contains("read"))
    }

    @Test
    fun `a file that is not a knowledge file ends in a notice that can be dismissed`() = runTest(dispatcher) {
        pick("hello")

        importFile()

        assertNull(vm.uiState.value.review)
        assertTrue(vm.uiState.value.importNotice!!.contains("not a PlayFieldPortal emulator knowledge file"))
        vm.dismissImportNotice()
        assertNull(vm.uiState.value.importNotice)
    }

    @Test
    fun `a file with nothing for this device ends in a notice`() = runTest(dispatcher) {
        pick(fileJson(""))

        importFile()

        assertNull(vm.uiState.value.review)
        assertTrue(vm.uiState.value.importNotice!!.contains("nothing"))
    }

    @Test
    fun `an empty selection disables confirm and stores nothing`() = runTest(dispatcher) {
        pick(fileJson(emulatorJson("one")))
        importFile()
        vm.toggleReviewItem("emulator:one")

        assertFalse(vm.uiState.value.review!!.confirmEnabled)
        vm.confirmImport()
        advanceUntilIdle()

        coVerify(exactly = 0) { store.addUserFile(any(), any()) }
        assertTrue(vm.uiState.value.review != null)
    }

    @Test
    fun `a console update alone can be confirmed`() = runTest(dispatcher) {
        effective.value = EffectiveKb(emptyList(), listOf(psxWith("bin")), officialApplied = false)
        pick(fileJson("", """{"id":"psx","romExtensions":["bin","chd"]}"""))
        importFile()
        assertFalse(vm.uiState.value.review!!.confirmEnabled)

        vm.toggleReviewItem("platform:psx")

        val review = vm.uiState.value.review!!
        assertTrue(review.confirmEnabled)
        assertEquals("Update 1 console", review.confirmLabel)
        val doc = slot<EmulatorKbDocument>()
        coEvery { store.addUserFile(any(), capture(doc)) } returns "file-id"
        vm.confirmImport()
        advanceUntilIdle()
        assertEquals(listOf("psx"), doc.captured.platforms.map { (it as KbItem.Ok).value.id })
    }

    @Test
    fun `the confirm label counts emulators and console updates`() = runTest(dispatcher) {
        effective.value = EffectiveKb(emptyList(), listOf(psxWith("bin")), officialApplied = false)
        pick(fileJson(emulatorJson("one") + "," + emulatorJson("two"), """{"id":"psx","romExtensions":["bin","chd"]}"""))
        importFile()

        assertEquals("Import 2 emulators", vm.uiState.value.review!!.confirmLabel)
        vm.toggleReviewItem("emulator:two")
        assertEquals("Import 1 emulator", vm.uiState.value.review!!.confirmLabel)
        vm.toggleReviewItem("platform:psx")
        assertEquals("Import 1 emulator and 1 console update", vm.uiState.value.review!!.confirmLabel)
        vm.toggleReviewItem("emulator:one")
        assertEquals("Update 1 console", vm.uiState.value.review!!.confirmLabel)
    }

    @Test
    fun `the display name is capped at 64 characters and has a fallback`() = runTest(dispatcher) {
        pick(fileJson(emulatorJson("one")), name = "x".repeat(100))
        importFile()
        assertEquals(64, vm.uiState.value.review!!.displayName.length)

        pick(fileJson(emulatorJson("one")), name = null)
        vm.importFrom(uri)
        advanceUntilIdle()
        assertEquals("Knowledge file", vm.uiState.value.review!!.displayName)
    }

    @Test
    fun `an entry whose signer differs from the installed build is blocked`() = runTest(dispatcher) {
        every { signerProbe.probe("com.example.one", any()) } returns SignerState.Mismatch
        val pin = "a".repeat(64)
        pick(fileJson(emulatorJson("one", extra = ""","signerSha256":["$pin"]""")))

        importFile()

        assertTrue(vm.uiState.value.review!!.plan.items.single() is ImportItem.Blocked)
    }

    @Test
    fun `a user-edited profile is passed to the plan`() = runTest(dispatcher) {
        effective.value = EffectiveKb(
            listOf(EffectiveKbEmulator(existing("old", "com.example.old.Other"), KbSource.BuiltIn, null)),
            emptyList(), officialApplied = false,
        )
        coEvery { profiles.getAllPersistedProfiles() } returns listOf(
            EmulatorProfile(
                id = "p", name = "n", packageName = "com.example.old", intentType = IntentType.COMPONENT,
                supportedPlatformIds = listOf("psx"), userModified = true, knowledgeId = "old",
            ),
        )
        pick(fileJson(emulatorJson("old")))

        importFile()

        assertTrue(vm.uiState.value.review!!.plan.items.filterIsInstance<ImportItem.Change>().single().userEdited)
    }

    @Test
    fun `a failed write ends in a notice and does not refresh`() = runTest(dispatcher) {
        pick(fileJson(emulatorJson("one")))
        importFile()
        coEvery { store.addUserFile(any(), any()) } throws IOException("disk full")

        vm.confirmImport()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.importNotice!!.contains("save"))
        coVerify(exactly = 0) { refresher.run() }
    }

    // ---- Task 7.2: export ----

    private fun mine(id: String, name: String = "Name $id", custom: Boolean = true, extras: Map<String, String> = emptyMap()) =
        EmulatorProfile(
            id = id, name = name, packageName = "com.example.$id", activityClass = "com.example.$id.Main",
            intentType = IntentType.COMPONENT, supportedPlatformIds = listOf("psx"), intentExtras = extras,
            isCustom = custom, userModified = !custom,
        )

    private fun TestScope.openExport(vararg all: EmulatorProfile) {
        coEvery { profiles.getAllPersistedProfiles() } returns all.toList()
        advanceUntilIdle()
        vm.openExport()
        advanceUntilIdle()
    }

    private fun writtenIds(): List<String> {
        val decoded = EmulatorKbDecoder.decode(String(written.single().second, Charsets.UTF_8)) as EmulatorKbDecode.Decoded
        val validated = EmulatorKbValidator.validate(decoded.document, setOf("psx"), "com.playfieldportal.launcher")
        assertTrue(validated.refusedEmulators.isEmpty())
        return validated.emulators.map { it.id }
    }

    @Test
    fun `the export picker lists only eligible profiles, all selected`() = runTest(dispatcher) {
        openExport(mine("a"), mine("b", custom = false), mine("c", custom = false).copy(userModified = false, isCustom = false))

        val picker = vm.uiState.value.export!!
        assertEquals(listOf("a", "b"), picker.entries.map { it.id })
        assertEquals(setOf("a", "b"), picker.selectedIds)
        assertEquals("Export 2 emulators", picker.confirmLabel)
        assertTrue(picker.exportEnabled)
    }

    @Test
    fun `an entry that cannot be shared is listed apart and cannot be selected`() = runTest(dispatcher) {
        openExport(mine("good"), mine("bad", extras = mapOf("rom" to "/storage/emulated/0/x")))

        val picker = vm.uiState.value.export!!
        assertEquals(listOf("good"), picker.entries.map { it.id })
        assertEquals(listOf("bad"), picker.cantShare.map { it.profileId })
    }

    @Test
    fun `with nothing to export a notice is shown instead of the picker`() = runTest(dispatcher) {
        openExport(mine("auto", custom = false).copy(userModified = false))

        assertNull(vm.uiState.value.export)
        assertTrue(vm.uiState.value.exportNotice != null)
    }

    @Test
    fun `deselecting one excludes it from the written bytes`() = runTest(dispatcher) {
        openExport(mine("a"), mine("b"))
        vm.toggleExportItem("b")

        vm.exportTo(uri)
        advanceUntilIdle()

        assertEquals(listOf("custom_name_a"), writtenIds())
        assertEquals(uri, written.single().first)
    }

    @Test
    fun `select all toggles between everything and nothing`() = runTest(dispatcher) {
        openExport(mine("a"), mine("b"))

        vm.toggleExportAll()
        assertEquals(emptySet<String>(), vm.uiState.value.export!!.selectedIds)
        vm.toggleExportAll()
        assertEquals(setOf("a", "b"), vm.uiState.value.export!!.selectedIds)
    }

    @Test
    fun `nothing selected disables export and writes nothing`() = runTest(dispatcher) {
        openExport(mine("a"))
        vm.toggleExportItem("a")

        assertFalse(vm.uiState.value.export!!.exportEnabled)
        assertEquals("Export 0 emulators", vm.uiState.value.export!!.confirmLabel)
        vm.exportTo(uri)
        advanceUntilIdle()

        assertTrue(written.isEmpty())
    }

    @Test
    fun `a successful export closes the picker and reports the count`() = runTest(dispatcher) {
        openExport(mine("a"), mine("b"))

        vm.exportTo(uri)
        advanceUntilIdle()

        assertNull(vm.uiState.value.export)
        assertEquals("Exported 2 emulators", vm.uiState.value.exportNotice!!.message)
        assertEquals(listOf("custom_name_a", "custom_name_b"), writtenIds())
    }

    @Test
    fun `a cancelled file picker keeps the picker open and writes nothing`() = runTest(dispatcher) {
        openExport(mine("a"))

        vm.exportTo(null)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.export != null)
        assertTrue(written.isEmpty())
    }

    @Test
    fun `a failed write ends in a failure notice`() = runTest(dispatcher) {
        writeOk = false
        openExport(mine("a"))

        vm.exportTo(uri)
        advanceUntilIdle()

        assertNull(vm.uiState.value.export)
        assertTrue(vm.uiState.value.exportNotice!!.title.contains("Couldn't"))
        vm.dismissExportNotice()
        assertNull(vm.uiState.value.exportNotice)
    }

    @Test
    fun `cancelling the export closes the picker`() = runTest(dispatcher) {
        openExport(mine("a"))

        vm.cancelExport()

        assertNull(vm.uiState.value.export)
    }

    // ---- Drill-down fixes ----

    @Test
    fun `an export cannot open while an import read is in flight`() = runTest(dispatcher) {
        coEvery { profiles.getAllPersistedProfiles() } returns listOf(mine("a"))
        readGate = CompletableDeferred()
        pick(fileJson(emulatorJson("one")))
        advanceUntilIdle()

        vm.importFrom(uri)
        advanceUntilIdle()
        vm.openExport()
        advanceUntilIdle()
        assertNull(vm.uiState.value.export)

        readGate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.review != null)
    }

    @Test
    fun `an export cannot open over an open review`() = runTest(dispatcher) {
        coEvery { profiles.getAllPersistedProfiles() } returns listOf(mine("a"))
        pick(fileJson(emulatorJson("one")))
        importFile()

        vm.openExport()
        advanceUntilIdle()

        assertNull(vm.uiState.value.export)
    }

    @Test
    fun `an import does nothing while the export picker is open`() = runTest(dispatcher) {
        openExport(mine("a"))
        pick(fileJson(emulatorJson("one")))

        vm.importFrom(uri)
        advanceUntilIdle()

        assertEquals(0, reads)
        assertNull(vm.uiState.value.review)
    }

    @Test
    fun `a second import while one is in flight does not read again`() = runTest(dispatcher) {
        readGate = CompletableDeferred()
        pick(fileJson(emulatorJson("one")))
        advanceUntilIdle()

        vm.importFrom(uri)
        vm.importFrom(uri)
        advanceUntilIdle()
        readGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, reads)
        assertFalse(vm.uiState.value.isImporting)
    }

    @Test
    fun `decoding and planning run on the plan dispatcher`() = runTest(dispatcher) {
        pick(fileJson(emulatorJson("one")))

        importFile()

        assertTrue(planDispatches.get() > 0)
        assertTrue(vm.uiState.value.review != null)
    }

    @Test
    fun `the picked display name loses control and bidi characters`() = runTest(dispatcher) {
        pick(fileJson(emulatorJson("one")), name = "a\u202Eb\nc\u200Fd.json")

        importFile()

        assertEquals("abcd.json", vm.uiState.value.review!!.displayName)
    }

    @Test
    fun `a reader that throws anything else is unreadable`() = runTest {
        val context = mockk<android.content.Context>()
        val resolver = mockk<android.content.ContentResolver>()
        every { context.contentResolver } returns resolver
        every { resolver.openInputStream(uri) } throws IllegalStateException("provider died")

        assertTrue(ContentResolverKbFileReader(context).read(uri) is KbPick.Unreadable)
    }

    @Test
    fun `a check that throws ends the check with a message`() = runTest(dispatcher) {
        coEvery { updater.check(true) } throws IllegalStateException("boom")
        advanceUntilIdle()

        vm.checkNow()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isChecking)
        assertTrue(vm.uiState.value.checkResult != null)
    }

    @Test
    fun `a refresh that throws after a confirmed import does not crash`() = runTest(dispatcher) {
        pick(fileJson(emulatorJson("one")))
        importFile()
        coEvery { refresher.run() } throws IllegalStateException("boom")

        vm.confirmImport()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.importNotice != null)
    }

    @Test
    fun `a store that throws on remove or reset ends in a notice`() = runTest(dispatcher) {
        advanceUntilIdle()
        coEvery { store.removeUserFile("a") } throws IllegalStateException("boom")
        vm.removeFile("a")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.exportNotice!!.title.contains("remove"))

        vm.dismissExportNotice()
        coEvery { store.resetToBuiltIn() } throws IllegalStateException("boom")
        vm.requestReset()
        vm.confirmReset()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.exportNotice!!.title.contains("reset"))
    }

    @Test
    fun `when nothing can be shared a notice replaces the empty picker`() = runTest(dispatcher) {
        openExport(mine("bad", extras = mapOf("rom" to "/storage/emulated/0/x")))

        assertNull(vm.uiState.value.export)
        val notice = vm.uiState.value.exportNotice!!
        assertEquals("Nothing to export", notice.title)
        assertTrue(notice.message.startsWith("None of your emulators can be shared: "))
    }

    @Test
    fun `update controls follow whether the build can verify updates`() = runTest(dispatcher) {
        advanceUntilIdle()
        assertTrue(vm.uiState.value.updatesConfigured)

        every { updater.isConfigured } returns false
        val unconfigured = EmulatorKnowledgeViewModel(
            updater, store, refresher, reader, writer, signerProbe, profiles,
            setOf("psx"), "com.playfieldportal.launcher", { now }, TimeZone.getTimeZone("UTC"), planDispatcher,
        )
        assertFalse(unconfigured.uiState.value.updatesConfigured)
    }
}
