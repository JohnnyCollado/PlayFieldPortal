package com.playfieldportal.feature.achievements.match

import com.playfieldportal.feature.achievements.provider.vita.ParamSfo
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DiscImage.listDir] plus the exact read `Ps3TropDirReader` performs on an `.iso`: resolve
 * `PS3_GAME/TROPDIR`, enumerate its `NPWR…` children in directory order, and read `PARAM.SFO`.
 * The reader itself needs a `Context`, so the disc-side mechanics are covered here.
 */
class DiscImageListDirTest {

    private val temp = mutableListOf<File>()

    @AfterTest
    fun cleanup() = temp.forEach { it.delete() }

    private fun image(iso: ByteArray): DiscImage {
        val file = File.createTempFile("ps3", ".iso").also { it.writeBytes(iso); temp += it }
        return assertNotNull(DiscImage.open(file))
    }

    // A PARAM.SFO holding only the string keys the reader asks for.
    private fun paramSfo(entries: List<Pair<String, String>>): ByteArray {
        val keyTable = entries.joinToString("") { it.first + "\u0000" }.toByteArray(Charsets.US_ASCII)
        val dataEntries = entries.map { it.second.toByteArray(Charsets.US_ASCII) + byteArrayOf(0) }
        val headerSize = 20 + entries.size * 16
        val keyOffset = headerSize
        val dataOffset = keyOffset + keyTable.size
        val out = java.io.ByteArrayOutputStream()
        val header = java.nio.ByteBuffer.allocate(headerSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put(byteArrayOf(0x00, 0x50, 0x53, 0x46))      // "\0PSF"
        header.putInt(4, 0x0101)
        header.putInt(8, keyOffset)
        header.putInt(12, dataOffset)
        header.putInt(16, entries.size)
        var keyPos = 0
        var dataPos = 0
        entries.forEachIndexed { i, (key, _) ->
            val e = 20 + i * 16
            val bytes = dataEntries[i]
            header.putShort(e, keyPos.toShort())
            // data_fmt 0x0204 (null-terminated UTF-8) as a little-endian u16: low byte first.
            header.put(e + 2, 0x04)
            header.put(e + 3, 0x02)
            header.putInt(e + 4, bytes.size)
            header.putInt(e + 8, bytes.size)
            header.putInt(e + 12, dataPos)
            keyPos += key.length + 1
            dataPos += bytes.size
        }
        out.write(header.array())
        out.write(keyTable)
        dataEntries.forEach(out::write)
        return out.toByteArray()
    }

    @Test
    fun `lists a directory's children with their extents and directory flag`() {
        val iso = SyntheticIso()
            .addDir("PS3_GAME\\TROPDIR\\NPWR05915_00")
            .addFile("PS3_GAME\\PARAM.SFO", ByteArray(64) { 1 })
            .build()

        image(iso).use { disc ->
            val ps3Game = assertNotNull(disc.findFile("PS3_GAME"))
            val children = disc.listDir(ps3Game.lba, ps3Game.size)

            assertEquals(setOf("TROPDIR", "PARAM.SFO"), children.map { it.name }.toSet())
            assertTrue(children.first { it.name == "TROPDIR" }.isDirectory)
            // A file's ;1 version suffix is stripped and its real length reported.
            val sfo = children.first { it.name == "PARAM.SFO" }
            assertEquals(false, sfo.isDirectory)
            assertEquals(64, sfo.size)
            assertTrue(sfo.lba > 16)
        }
    }

    @Test
    fun `reads the captured sample's shape - one NPWR set plus its serial`() {
        val iso = SyntheticIso()
            .addDir("PS3_GAME\\TROPDIR\\NPWR05915_00")
            .addFile(
                "PS3_GAME\\PARAM.SFO",
                paramSfo(
                    listOf(
                        "CATEGORY" to "DG",
                        "TITLE" to "The Witch and the Hundred Knight",
                        "TITLE_ID" to "BLUS30964",
                    ),
                ),
            )
            .build()

        image(iso).use { disc ->
            val tropDir = assertNotNull(disc.findFile("PS3_GAME\\TROPDIR"))
            val ids = disc.listDir(tropDir.lba, tropDir.size)
                .filter { it.isDirectory && it.name.startsWith("NPWR") }
                .map { it.name }
            assertEquals(listOf("NPWR05915_00"), ids)

            val sfoEntry = assertNotNull(disc.findFile("PS3_GAME\\PARAM.SFO"))
            val sfo = ParamSfo.parseStrings(disc.readFileBytes(sfoEntry.lba, sfoEntry.size, 64 * 1024))
            assertEquals("BLUS30964", sfo["TITLE_ID"])
            assertEquals("The Witch and the Hundred Knight", sfo["TITLE"])
            assertEquals("DG", sfo["CATEGORY"])
        }
    }

    @Test
    fun `a multi-entry TROPDIR keeps directory order, base set first`() {
        val iso = SyntheticIso()
            .addDir("PS3_GAME\\TROPDIR\\NPWR05915_00")
            .addDir("PS3_GAME\\TROPDIR\\NPWR05915_01")
            .addDir("PS3_GAME\\TROPDIR\\NPWR05915_02")
            .build()

        image(iso).use { disc ->
            val tropDir = assertNotNull(disc.findFile("PS3_GAME\\TROPDIR"))
            val ids = disc.listDir(tropDir.lba, tropDir.size).map { it.name }

            assertEquals(listOf("NPWR05915_00", "NPWR05915_01", "NPWR05915_02"), ids)
        }
    }

    @Test
    fun `a game with no TROPDIR is distinguishable from one with no PS3_GAME`() {
        val noTropDir = SyntheticIso().addFile("PS3_GAME\\PARAM.SFO", ByteArray(16)).build()
        image(noTropDir).use { disc ->
            assertNotNull(disc.findFile("PS3_GAME"))
            assertNull(disc.findFile("PS3_GAME\\TROPDIR"))
        }

        val notPs3 = SyntheticIso().addFile("SYSTEM.CNF", ByteArray(16)).build()
        image(notPs3).use { disc -> assertNull(disc.findFile("PS3_GAME")) }
    }

    @Test
    fun `the self and parent records a real ISO carries are skipped, not listed`() {
        val iso = SyntheticIso()
            .addDir("PS3_GAME\\TROPDIR\\NPWR05915_00")
            .addDir("PS3_GAME\\TROPDIR\\NPWR05915_01")
            .build(includeDotRecords = true)

        image(iso).use { disc ->
            val tropDir = assertNotNull(disc.findFile("PS3_GAME\\TROPDIR"))
            val children = disc.listDir(tropDir.lba, tropDir.size)

            assertEquals(listOf("NPWR05915_00", "NPWR05915_01"), children.map { it.name })
        }
    }

    @Test
    fun `listing reads only the directory's own sector`() {
        val iso = SyntheticIso().addDir("PS3_GAME\\TROPDIR\\NPWR05915_00").build()
        val file = File.createTempFile("ps3", ".iso").also { it.writeBytes(iso); temp += it }

        var sectorsRead = 0
        val counting = object : DiscImage.SeekableSource {
            private val raf = java.io.RandomAccessFile(file, "r")
            override fun readFully(offset: Long, dest: ByteArray, len: Int): Int {
                if (len > 8) sectorsRead++
                raf.seek(offset)
                var total = 0
                while (total < len) {
                    val n = raf.read(dest, total, len - total)
                    if (n < 0) break
                    total += n
                }
                return total
            }
            override fun close() = raf.close()
        }

        assertNotNull(DiscImage.open(counting)).use { disc ->
            val tropDir = assertNotNull(disc.findFile("PS3_GAME\\TROPDIR"))
            val before = sectorsRead
            disc.listDir(tropDir.lba, tropDir.size)
            // One sector for the listing itself — never a walk of a multi-GB image.
            assertEquals(1, sectorsRead - before)
        }
    }
}
