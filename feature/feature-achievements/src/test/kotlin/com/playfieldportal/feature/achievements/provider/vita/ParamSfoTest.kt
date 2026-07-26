package com.playfieldportal.feature.achievements.provider.vita

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ParamSfoTest {

    // Builds a minimal valid SFO blob for the given UTF-8 string entries.
    private fun sfoOf(vararg pairs: Pair<String, String>): ByteArray {
        val keyTable = ByteArrayOutputStream()
        val dataTable = ByteArrayOutputStream()
        val keyOffsets = IntArray(pairs.size)
        val dataOffsets = IntArray(pairs.size)
        val dataLens = IntArray(pairs.size)
        pairs.forEachIndexed { i, (k, v) ->
            keyOffsets[i] = keyTable.size()
            keyTable.write(k.toByteArray(Charsets.UTF_8)); keyTable.write(0)
            dataOffsets[i] = dataTable.size()
            val vb = v.toByteArray(Charsets.UTF_8)
            dataTable.write(vb); dataTable.write(0)
            dataLens[i] = vb.size + 1   // string data_len includes the trailing NUL
        }
        val keyBytes = keyTable.toByteArray()
        val dataBytes = dataTable.toByteArray()
        val keyTableStart = 20 + pairs.size * 16
        val dataTableStart = keyTableStart + keyBytes.size
        val bb = ByteBuffer.allocate(dataTableStart + dataBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0x46535000)         // magic
        bb.putInt(0x00000101)         // version 1.1
        bb.putInt(keyTableStart)
        bb.putInt(dataTableStart)
        bb.putInt(pairs.size)
        pairs.indices.forEach { i ->
            bb.putShort(keyOffsets[i].toShort())
            bb.putShort(0x0204.toShort())   // FMT_UTF8
            bb.putInt(dataLens[i])
            bb.putInt(dataLens[i])          // data_max_len
            bb.putInt(dataOffsets[i])
        }
        bb.put(keyBytes); bb.put(dataBytes)
        return bb.array()
    }

    @Test
    fun `parses the TITLE string, cutting at the trailing NUL`() {
        val sfo = sfoOf("TITLE_ID" to "PCSB00098", "TITLE" to "Disgaea 3: Absence of Detention")
        assertEquals("Disgaea 3: Absence of Detention", ParamSfo.title(sfo))
    }

    @Test
    fun `exposes all string entries by key`() {
        val sfo = sfoOf("TITLE_ID" to "PCSB00098", "TITLE" to "Disgaea 3")
        val map = ParamSfo.parseStrings(sfo)
        assertEquals("PCSB00098", map["TITLE_ID"])
        assertEquals("Disgaea 3", map["TITLE"])
    }

    @Test
    fun `non-sfo input returns null title and empty map`() {
        assertNull(ParamSfo.title(byteArrayOf(1, 2, 3, 4, 5)))
        assertEquals(emptyMap(), ParamSfo.parseStrings(ByteArray(4)))
    }
}
