package com.playfieldportal.feature.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.database.seeder.PlatformSeeder
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbDecode
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbDecoder
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbDocument
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * `docs/emulator-kb/pfp-default-emulators.json` is the built-in knowledge base written out as a user
 * file: the example for writing your own, and a copy of the defaults anyone can import. It must stay
 * identical to the built-in asset's entries and pass the same validator an import runs.
 */
@RunWith(RobolectricTestRunner::class)
class ExampleKnowledgeBaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val knownPlatformIds: Set<String> =
        PlatformSeeder.DEFAULT_PLATFORMS.map { it.id }.flatMap(::platformAliases).toSet()

    // Unit tests run from the module directory.
    private val exampleFile = File("../../docs/emulator-kb/pfp-default-emulators.json")

    private fun decode(text: String): EmulatorKbDocument {
        val decoded = EmulatorKbDecoder.decode(text)
        assertTrue("must decode: $decoded", decoded is EmulatorKbDecode.Decoded)
        return (decoded as EmulatorKbDecode.Decoded).document
    }

    private fun builtIn(): EmulatorKbDocument =
        decode(context.assets.open("emulator_kb/emulators.json").bufferedReader().use { it.readText() })

    @Test
    fun `the example carries exactly the built-in entries`() {
        assertTrue("missing: ${exampleFile.absolutePath}", exampleFile.isFile)
        val example = decode(exampleFile.readText())
        assertEquals(
            "regenerate docs/emulator-kb/pfp-default-emulators.json from the built-in asset",
            builtIn().emulators,
            example.emulators,
        )
        assertEquals(builtIn().platforms, example.platforms)
    }

    @Test
    fun `the example is labelled as a user file`() {
        val example = decode(exampleFile.readText())
        // User files and exports use version 0; only the built-in and official layers count up.
        assertEquals(0L, example.version)
        assertEquals("PFP default (example)", example.label)
    }

    @Test
    fun `the example imports with zero refusals`() {
        val result = EmulatorKbValidator.validate(decode(exampleFile.readText()), knownPlatformIds, context.packageName)
        assertTrue("refused: ${result.refusedEmulators}", result.refusedEmulators.isEmpty())
        assertEquals(builtIn().emulators.size, result.emulators.size)
    }
}
