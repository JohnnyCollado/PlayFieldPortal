package com.playfieldportal.feature.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.domain.model.IntentType
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbDecode
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbDecoder
import com.playfieldportal.feature.launcher.kb.EmulatorKnowledgeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The wizard's KNOWN suggestion comes from the effective knowledge base, per-package overrides included. */
@RunWith(RobolectricTestRunner::class)
class AppLaunchInspectorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clean() {
        File(context.filesDir, "emulator_kb").deleteRecursively()
    }

    private fun inspector() = AppLaunchInspector(context, EmulatorKnowledgeStore(context, Dispatchers.Unconfined))

    @Test
    fun `a KB package is suggested with the entry's launch recipe`() = runBlocking {
        val suggestion = inspector().suggestForPackage("com.github.stenzek.duckstation")

        assertEquals(DetectionConfidence.KNOWN, suggestion.confidence)
        assertEquals("DuckStation", suggestion.profile.name)
        assertEquals("com.github.stenzek.duckstation", suggestion.profile.packageName)
        assertTrue(suggestion.profile.isCustom)
    }

    @Test
    fun `a package with a per-build override gets that override`() = runBlocking {
        val suggestion = inspector().suggestForPackage("org.sudachi.android")

        assertEquals(DetectionConfidence.KNOWN, suggestion.confidence)
        assertEquals(IntentType.ACTION_VIEW, suggestion.profile.intentType)
        assertEquals(null, suggestion.profile.activityClass)
    }

    @Test
    fun `the entry's base launch applies to its other packages`() = runBlocking {
        val suggestion = inspector().suggestForPackage("org.sudachi.sudachi_emu")

        assertEquals(IntentType.COMPONENT, suggestion.profile.intentType)
    }

    @Test
    fun `an unknown package is not a KNOWN suggestion`() = runBlocking {
        val suggestion = inspector().suggestForPackage("org.example.unknown")

        assertTrue(suggestion.confidence != DetectionConfidence.KNOWN)
    }

    @Test
    fun `a KNOWN suggestion keeps the signer pin, array extras, ROM data and knowledge id`() = runBlocking {
        val digest = "ab".repeat(32)
        val text = """{"format":"pfp-emulator-kb","schemaVersion":1,"version":0,"label":"","platforms":[],
            "emulators":[{"id":"pinned","name":"Pinned","packageNames":["com.example.pinned"],"platformIds":["psx"],
            "signerSha256":["$digest"],
            "launch":{"intentType":"COMPONENT","activityClass":"com.example.pinned.Main","attachRomData":true,
            "arrayExtras":{"args":["-g","{rom_uri}"]}}}]}"""
        val store = EmulatorKnowledgeStore(context, Dispatchers.Unconfined)
        store.addUserFile("pinned.json", (EmulatorKbDecoder.decode(text) as EmulatorKbDecode.Decoded).document)

        val profile = AppLaunchInspector(context, store).suggestForPackage("com.example.pinned").profile

        assertEquals(listOf(digest), profile.signerSha256)
        assertEquals(mapOf("args" to listOf("-g", "{rom_uri}")), profile.intentArrayExtras)
        assertTrue(profile.attachRomData)
        assertEquals("pinned", profile.knowledgeId)
    }
}
