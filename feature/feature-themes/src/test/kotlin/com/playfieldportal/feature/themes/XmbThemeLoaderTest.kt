package com.playfieldportal.feature.themes

import android.content.Context
import com.playfieldportal.core.data.database.dao.ThemeDao
import com.playfieldportal.core.data.database.entity.ThemeEntity
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XmbThemeLoaderTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var themeDao: ThemeDao
    private lateinit var loader: XmbThemeLoader

    @Before
    fun setUp() {
        context  = mockk(relaxed = true)
        themeDao = mockk(relaxed = true)
        every { context.filesDir } returns tempFolder.root
        loader = XmbThemeLoader(context, themeDao)
    }

    // ── loadFromStream — success ──────────────────────────────────────────────

    @Test
    fun `valid zip returns Success and upserts entity`() = runTest {
        val stream = buildZip { writeManifest(XmbThemeManifest(id = "my_theme", name = "My Theme")) }

        val result = loader.loadFromStream(stream)

        assertTrue("Expected Success, got $result", result is ThemeLoadResult.Success)
        assertEquals("my_theme", (result as ThemeLoadResult.Success).themeId)
        coVerify { themeDao.upsert(any()) }
    }

    @Test
    fun `entity upserted with correct color Long values`() = runTest {
        val manifest = XmbThemeManifest(
            id          = "colors",
            name        = "Colors",
            waveColor   = "#0055AA",
            accentColor = "#FF0000",
            textColor   = "#FFFFFF",
        )
        val stream = buildZip { writeManifest(manifest) }

        loader.loadFromStream(stream)

        val slot = slot<ThemeEntity>()
        coVerify { themeDao.upsert(capture(slot)) }
        assertEquals(0xFF0055AAL, slot.captured.waveColor)
        assertEquals(0xFFFF0000L, slot.captured.accentColor)
        assertEquals(0xFFFFFFFFL, slot.captured.textColor)
    }

    @Test
    fun `entity upserted with correct wave parameters`() = runTest {
        val manifest = XmbThemeManifest(
            id            = "wave",
            name          = "Wave",
            waveOpacity   = 0.5f,
            waveSpeed     = 1.5f,
            waveAmplitude = 0.8f,
        )
        val stream = buildZip { writeManifest(manifest) }

        loader.loadFromStream(stream)

        val slot = slot<ThemeEntity>()
        coVerify { themeDao.upsert(capture(slot)) }
        assertEquals(0.5f,  slot.captured.waveOpacity,   0.001f)
        assertEquals(1.5f,  slot.captured.waveSpeed,     0.001f)
        assertEquals(0.8f,  slot.captured.waveAmplitude, 0.001f)
    }

    @Test
    fun `entity is marked not built-in`() = runTest {
        val stream = buildZip { writeManifest(XmbThemeManifest(id = "user", name = "User")) }

        loader.loadFromStream(stream)

        val slot = slot<ThemeEntity>()
        coVerify { themeDao.upsert(capture(slot)) }
        assertFalse(slot.captured.isBuiltIn)
    }

    // ── loadFromStream — failure modes ────────────────────────────────────────

    @Test
    fun `zip without theme_json returns InvalidFormat`() = runTest {
        val stream = buildZip { /* no manifest */ }

        val result = loader.loadFromStream(stream)

        assertTrue(result is ThemeLoadResult.InvalidFormat)
        assertTrue(
            "Reason should mention theme.json",
            (result as ThemeLoadResult.InvalidFormat).reason.contains("theme.json"),
        )
    }

    @Test
    fun `future format version returns UnsupportedVersion with correct values`() = runTest {
        val stream = buildZip {
            writeManifest(XmbThemeManifest(id = "future", name = "Future", formatVersion = 999))
        }

        val result = loader.loadFromStream(stream)

        assertTrue(result is ThemeLoadResult.UnsupportedVersion)
        val r = result as ThemeLoadResult.UnsupportedVersion
        assertEquals(999, r.found)
        assertEquals(THEME_FORMAT_VERSION, r.supported)
    }

    @Test
    fun `invalid wave_color hex returns InvalidFormat`() = runTest {
        val stream = buildZip {
            writeManifest(XmbThemeManifest(id = "bad", name = "Bad", waveColor = "#ZZZZZZ"))
        }

        val result = loader.loadFromStream(stream)

        assertTrue(result is ThemeLoadResult.InvalidFormat)
        assertTrue((result as ThemeLoadResult.InvalidFormat).reason.contains("wave_color"))
    }

    @Test
    fun `invalid accent_color hex returns InvalidFormat`() = runTest {
        val stream = buildZip {
            writeManifest(XmbThemeManifest(id = "bad", name = "Bad", accentColor = "NOTHEX"))
        }

        val result = loader.loadFromStream(stream)

        assertTrue(result is ThemeLoadResult.InvalidFormat)
        assertTrue((result as ThemeLoadResult.InvalidFormat).reason.contains("accent_color"))
    }

    @Test
    fun `invalid text_color hex returns InvalidFormat`() = runTest {
        val stream = buildZip {
            writeManifest(XmbThemeManifest(id = "bad", name = "Bad", textColor = "???"))
        }

        val result = loader.loadFromStream(stream)

        assertTrue(result is ThemeLoadResult.InvalidFormat)
        assertTrue((result as ThemeLoadResult.InvalidFormat).reason.contains("text_color"))
    }

    @Test
    fun `malformed JSON in theme_json returns InvalidFormat`() = runTest {
        val stream = buildZip {
            putNextEntry(ZipEntry("theme.json"))
            write("{ this is not valid json }".toByteArray())
            closeEntry()
        }

        val result = loader.loadFromStream(stream)

        assertTrue("Expected InvalidFormat for bad JSON, got $result", result is ThemeLoadResult.InvalidFormat)
    }

    @Test
    fun `empty zip returns InvalidFormat`() = runTest {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { /* close immediately */ }
        val stream = ByteArrayInputStream(bos.toByteArray())

        val result = loader.loadFromStream(stream)

        assertTrue(result is ThemeLoadResult.InvalidFormat)
    }

    // ── Asset extraction ──────────────────────────────────────────────────────

    @Test
    fun `background asset extracted when hasBackground is true`() = runTest {
        val fakeImage = byteArrayOf(0x01, 0x02, 0x03, 0xFF.toByte())
        val manifest  = XmbThemeManifest(id = "bg_theme", name = "BG", hasBackground = true)
        val stream = buildZip {
            writeManifest(manifest)
            putNextEntry(ZipEntry("background.jpg"))
            write(fakeImage)
            closeEntry()
        }

        val result = loader.loadFromStream(stream)
        assertTrue("Expected Success, got $result", result is ThemeLoadResult.Success)

        val dest = java.io.File(tempFolder.root, "themes/bg_theme/background.jpg")
        assertTrue("background.jpg should be extracted to themes dir", dest.exists())
        assertArrayEquals(fakeImage, dest.readBytes())
    }

    @Test
    fun `backgroundUri in entity points to extracted file`() = runTest {
        val manifest = XmbThemeManifest(id = "bg2", name = "BG2", hasBackground = true)
        val stream = buildZip {
            writeManifest(manifest)
            putNextEntry(ZipEntry("background.jpg"))
            write(byteArrayOf(0x42))
            closeEntry()
        }

        loader.loadFromStream(stream)

        val slot = slot<ThemeEntity>()
        coVerify { themeDao.upsert(capture(slot)) }
        val expected = java.io.File(tempFolder.root, "themes/bg2/background.jpg").absolutePath
        assertEquals(expected, slot.captured.backgroundUri)
    }

    @Test
    fun `background not extracted when hasBackground is false even if file present in zip`() = runTest {
        val manifest = XmbThemeManifest(id = "nobg", name = "No BG", hasBackground = false)
        val stream = buildZip {
            writeManifest(manifest)
            putNextEntry(ZipEntry("background.jpg"))
            write(byteArrayOf(0x01))
            closeEntry()
        }

        loader.loadFromStream(stream)

        val slot = slot<ThemeEntity>()
        coVerify { themeDao.upsert(capture(slot)) }
        assertNull("backgroundUri should be null when hasBackground=false", slot.captured.backgroundUri)

        val dest = java.io.File(tempFolder.root, "themes/nobg/background.jpg")
        assertFalse("File should not be extracted", dest.exists())
    }

    @Test
    fun `theme dir created under filesDir themes subdirectory`() = runTest {
        val stream = buildZip { writeManifest(XmbThemeManifest(id = "dir_test", name = "Dir")) }

        loader.loadFromStream(stream)

        val themeDir = java.io.File(tempFolder.root, "themes/dir_test")
        assertTrue("Theme directory should be created", themeDir.exists())
        assertTrue("Should be a directory", themeDir.isDirectory)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildZip(block: ZipOutputStream.() -> Unit): ByteArrayInputStream {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { it.block() }
        return ByteArrayInputStream(bos.toByteArray())
    }

    private fun ZipOutputStream.writeManifest(manifest: XmbThemeManifest) {
        putNextEntry(ZipEntry("theme.json"))
        write(Json.encodeToString(XmbThemeManifest.serializer(), manifest).toByteArray())
        closeEntry()
    }
}
