package com.playfieldportal.studio.preview

import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageVector
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every bundled slot in [StudioIconSet.RESOURCE_SLOTS] must exist on the classpath and decode the
 * way StudioIconSet's painter decodes it: PNGs through Skia, the XML vector through the Compose
 * resources decoder. A missing or unreadable file would otherwise only show up as a crash in the
 * running Studio.
 */
class StudioIconSetResourcesTest {

    @OptIn(ExperimentalResourceApi::class)
    @Test
    fun `every bundled slot decodes`() {
        val loader = StudioIconSet::class.java.classLoader
        for ((slot, path) in StudioIconSet.RESOURCE_SLOTS) {
            val bytes = loader.getResourceAsStream(path)?.use { it.readBytes() }
            assertNotNull("$slot: $path is missing", bytes)
            if (path.endsWith(".xml")) {
                val vector = bytes!!.decodeToImageVector(Density(1f))
                assertTrue("$slot: empty vector", vector.defaultWidth.value > 0f)
            } else {
                val bitmap = org.jetbrains.skia.Image.makeFromEncoded(bytes!!).toComposeImageBitmap()
                assertTrue("$slot: empty bitmap", bitmap.width > 0 && bitmap.height > 0)
            }
        }
    }
}
