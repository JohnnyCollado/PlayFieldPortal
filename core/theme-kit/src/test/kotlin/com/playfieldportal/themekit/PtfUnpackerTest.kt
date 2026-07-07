package com.playfieldportal.themekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PtfUnpackerTest {

    private val pink = 0xFFFF72B1.toInt()

    /** PTF with one slot holding a chain: GIM record (LZR stored), raw 4-byte record. */
    private fun chainedPtf(): ByteArray {
        val gim = TestFixtures.buildGim(16, 8) { x, _ -> if (x < 8) pink else 0x8020FF40.toInt() }
        val gimStream = TestFixtures.lzrStored(gim)
        val flags = byteArrayOf(1, 0, 0, 0)

        fun record(seq: Int, type: Int, method: Int, comp: ByteArray, uncomp: Int): ByteArray {
            val r = ByteArray(32 + comp.size)
            r.putU32(0, seq); r.putU16(4, type); r.putU16(6, method)
            r.putU32(8, comp.size); r.putU32(12, uncomp)
            comp.copyInto(r, 32)
            return r
        }

        val chain = record(0, 5, 1, gimStream, gim.size) + record(1, 5, 1, flags, flags.size)
        val descriptorOffset = 0x120
        val dataOffset = 0x140
        val file = ByteArray(dataOffset + chain.size)
        file[1] = 'P'.code.toByte(); file[2] = 'T'.code.toByte(); file[3] = 'F'.code.toByte()
        "Chained".toByteArray().copyInto(file, 0x08)
        "3.70".toByteArray().copyInto(file, 0xB8)
        file.putU32(0x100, descriptorOffset)
        file.putU16(descriptorOffset, 0)          // slot id 0
        file.putU16(descriptorOffset + 2, 3)
        file.putU32(descriptorOffset + 4, chain.size)
        file.putU32(descriptorOffset + 8, dataOffset)
        chain.copyInto(file, dataOffset)
        return file
    }

    @Test
    fun `walks a record chain and decodes what it finds`() {
        val dump = assertNotNull(PtfUnpacker.unpack(chainedPtf()))
        assertEquals("Chained", dump.name)
        assertEquals("3.70", dump.firmware)
        assertEquals(2, dump.resources.size)

        val gimRes = dump.resources[0]
        assertEquals(PtfUnpacker.Resource.Kind.GIM, gimRes.kind)
        val image = assertNotNull(gimRes.image)
        assertEquals(16, image.width)
        assertEquals(pink, image[0, 0])
        assertEquals(0x8020FF40.toInt(), image[8, 0]) // translucent survives the round trip

        // The raw record stores compressed == uncompressed with no LZR wrapper.
        val flagRes = dump.resources[1]
        assertEquals(PtfUnpacker.Resource.Kind.OTHER, flagRes.kind)
        assertEquals(4, assertNotNull(flagRes.payload).size)
    }

    @Test
    fun `wallpaper ptf unpacks its single BMP record`() {
        val bmp = TestFixtures.buildBmp(8, 4) { _, _ -> pink }
        val ptf = TestFixtures.buildPtf("Wp", "5.00", bmp)
        val dump = assertNotNull(PtfUnpacker.unpack(ptf))
        assertEquals(1, dump.resources.size)
        assertEquals(PtfUnpacker.Resource.Kind.BMP, dump.resources[0].kind)
        assertEquals(8, assertNotNull(dump.resources[0].image).width)
    }

    @Test
    fun `record with an undecodable stream is reported FAILED, chain continues past it`() {
        val ptf = chainedPtf()
        // Corrupt the first record's LZR stored-length claim (big-endian u32 at +33)
        // so the stream promises far more data than exists.
        ptf[0x140 + 33] = 0x7F
        val dump = assertNotNull(PtfUnpacker.unpack(ptf))
        assertEquals(2, dump.resources.size)
        assertEquals(PtfUnpacker.Resource.Kind.FAILED, dump.resources[0].kind)
        assertNull(dump.resources[0].payload)
        assertEquals(PtfUnpacker.Resource.Kind.OTHER, dump.resources[1].kind)
    }

    @Test
    fun `a chain of max-size records stops at the total output budget`() {
        // 16 records each CLAIMING the per-record 32 MB maximum (the streams are junk, so
        // decoding fails, but the budget must count the claims): the walk has to stop once
        // the 256 MB total is hit instead of grinding through gigabytes of expansion.
        fun record(seq: Int): ByteArray {
            val r = ByteArray(32 + 64)
            r.putU32(0, seq); r.putU16(4, 5); r.putU16(6, 1)
            r.putU32(8, 64); r.putU32(12, 32 * 1024 * 1024)
            return r
        }

        var chain = ByteArray(0)
        repeat(16) { chain += record(it) }
        val descriptorOffset = 0x120
        val dataOffset = 0x140
        val file = ByteArray(dataOffset + chain.size)
        file[1] = 'P'.code.toByte(); file[2] = 'T'.code.toByte(); file[3] = 'F'.code.toByte()
        file.putU32(0x100, descriptorOffset)
        file.putU16(descriptorOffset, 0)
        file.putU16(descriptorOffset + 2, 3)
        file.putU32(descriptorOffset + 4, chain.size)
        file.putU32(descriptorOffset + 8, dataOffset)
        chain.copyInto(file, dataOffset)

        val dump = assertNotNull(PtfUnpacker.unpack(file))
        assertEquals(8, dump.resources.size, "walk must stop at 8 x 32 MB = the 256 MB budget")
    }

    @Test
    fun `garbage and cxmb are rejected like the parser`() {
        assertNull(PtfUnpacker.unpack(ByteArray(0x200)))
        assertNull(PtfUnpacker.unpack(chainedPtf() + "/vsh/resource/x".toByteArray()))
    }

    private fun ByteArray.putU16(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = (value shr 8 and 0xFF).toByte()
    }

    private fun ByteArray.putU32(offset: Int, value: Int) {
        putU16(offset, value and 0xFFFF)
        putU16(offset + 2, value ushr 16)
    }
}
