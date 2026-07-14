package com.playfieldportal.feature.achievements.match

/**
 * MSB-first bit reader over a byte buffer, transcribed from libchdr `bitstream.c`. Used to decode a
 * CHD v5 compressed hunk map. A 32-bit accumulator is refilled a byte at a time from the most
 * significant end; [peek] returns the next [numbits] bits without consuming, [read] consumes them.
 */
class ChdBitReader(private val data: ByteArray) {
    private var buffer = 0   // 32-bit accumulator, valid bits packed from the MSB
    private var bits = 0     // number of valid bits currently in [buffer]
    private var offset = 0   // next byte to pull from [data]

    fun peek(numbits: Int): Int {
        if (numbits == 0) return 0
        if (numbits > bits) {
            while (bits <= 24) {
                if (offset < data.size) buffer = buffer or ((data[offset].toInt() and 0xFF) shl (24 - bits))
                offset++
                bits += 8
            }
        }
        return buffer ushr (32 - numbits)
    }

    fun remove(numbits: Int) {
        buffer = buffer shl numbits
        bits -= numbits
    }

    fun read(numbits: Int): Int {
        val r = peek(numbits)
        remove(numbits)
        return r
    }

    fun overflow(): Boolean = (offset - bits / 8) > data.size
}

/**
 * Canonical Huffman decoder, transcribed from libchdr `huffman.c`. The CHD v5 map stores its
 * per-hunk compression types as an RLE-encoded canonical Huffman tree ([importTreeRle]) followed by
 * a Huffman-coded stream; [decodeOne] reads one symbol. This is a faithful port — the lookup-table
 * layout `(symbol << 5) | codelength` matches libchdr's `MAKE_LOOKUP`.
 */
class ChdHuffman(private val numcodes: Int, private val maxbits: Int) {
    private val nodeBits = IntArray(numcodes)   // code length assigned to each symbol
    private val nodeCode = IntArray(numcodes)   // canonical code assigned to each symbol
    private val lookup = IntArray(1 shl maxbits)

    fun decodeOne(bits: ChdBitReader): Int {
        val lv = lookup[bits.peek(maxbits)]
        bits.remove(lv and 0x1f)
        return lv ushr 5
    }

    /** Reads an RLE-encoded tree from [bits], then assigns canonical codes and builds the lookup. */
    fun importTreeRle(bits: ChdBitReader): Boolean {
        val numbits = when {
            maxbits >= 16 -> 5
            maxbits >= 8 -> 4
            else -> 3
        }
        var curnode = 0
        while (curnode < numcodes) {
            val nodebits = bits.read(numbits)
            if (nodebits != 1) {
                nodeBits[curnode++] = nodebits
            } else {
                val escaped = bits.read(numbits)
                if (escaped == 1) {
                    nodeBits[curnode++] = 1
                } else {
                    var repcount = bits.read(numbits) + 3
                    if (repcount + curnode > numcodes) return false
                    while (repcount-- > 0) nodeBits[curnode++] = escaped
                }
            }
        }
        if (curnode != numcodes) return false
        if (!assignCanonicalCodes()) return false
        buildLookupTable()
        return !bits.overflow()
    }

    private fun assignCanonicalCodes(): Boolean {
        val bithisto = IntArray(33)
        for (c in 0 until numcodes) {
            val n = nodeBits[c]
            if (n > maxbits) return false
            if (n <= 32) bithisto[n]++
        }
        var curstart = 0
        for (codelen in 32 downTo 1) {
            val nextstart = (curstart + bithisto[codelen]) shr 1
            if (codelen != 1 && nextstart * 2 != curstart + bithisto[codelen]) return false
            bithisto[codelen] = curstart
            curstart = nextstart
        }
        for (c in 0 until numcodes) {
            val n = nodeBits[c]
            if (n > 0) nodeCode[c] = bithisto[n]++
        }
        return true
    }

    private fun buildLookupTable() {
        for (c in 0 until numcodes) {
            val n = nodeBits[c]
            if (n <= 0) continue
            val value = (c shl 5) or n
            val shift = maxbits - n
            var dest = nodeCode[c] shl shift
            val destend = ((nodeCode[c] + 1) shl shift) - 1
            while (dest <= destend) lookup[dest++] = value
        }
    }
}
