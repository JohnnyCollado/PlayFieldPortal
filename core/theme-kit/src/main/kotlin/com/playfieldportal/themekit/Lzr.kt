package com.playfieldportal.themekit

/**
 * Decompressor for Sony's LZR streams — the compression PSP firmware (and firmware
 * 3.70-era `.ptf` themes) use for resource payloads (`sceLzrDecompress`).
 *
 * Independent Kotlin implementation written from the stream format's semantics (the
 * format is publicly documented by the homebrew community; no reference code is ported).
 *
 * Stream layout:
 * ```
 * +0  s8   type: < 0 -> stored block; >= 0 -> compressed, value = literal-context shift
 * +1  u32  big-endian: stored length (stored mode) / initial range-coder code (compressed)
 * +5       payload
 * ```
 *
 * Compressed mode is a binary range coder (32-bit code/range, byte-wise renormalization
 * when the range drops to 24 bits) over 2800 adaptive probability contexts (8-bit states
 * starting at 128; decrement by an eighth per decode, +31 when the coded bit is 1),
 * driving an LZ77 token stream: a selector bit chooses literal vs. back-reference, literals
 * are decoded bit-by-bit through a 255-node context tree picked from the output position
 * and previous byte, and back-references carry gamma-style variable-length lengths and
 * offsets with their own context groups. A length code of 0xFF terminates the stream.
 */
object Lzr {

    /**
     * Decompresses the LZR stream at [offset]..[offset]+[length] into at most
     * [maxOutput] bytes. Null on any malformation: truncated input, offsets pointing
     * before the output start, or output exceeding [maxOutput] before the end marker.
     */
    fun decompress(input: ByteArray, offset: Int, length: Int, maxOutput: Int): ByteArray? {
        if (offset < 0 || length < 5 || offset.toLong() + length > input.size) return null
        if (maxOutput !in 0..MAX_OUTPUT_BYTES) return null
        return Decoder(input, offset, offset + length, maxOutput).run()
    }

    /** Well beyond any theme resource; guards against absurd size claims, like Bmp/PtfParser. */
    const val MAX_OUTPUT_BYTES: Int = 32 * 1024 * 1024

    private const val PROBABILITY_SLOTS = 2800
    private const val INITIAL_PROBABILITY = 0x80
    private const val RENORM_LIMIT = 0x00FFFFFFL
    private const val U32 = 0xFFFFFFFFL

    private class Decoder(
        private val input: ByteArray,
        private val start: Int,
        private val end: Int,
        maxOutput: Int,
    ) {
        private val out = ByteArray(maxOutput)
        private var outPos = 0
        private var inPos = 0

        /** Range-coder state: 32-bit code word and current range, kept in unsigned space. */
        private var code = 0L
        private var range = U32

        /** Speculative range used only while decoding the length-class unary prefix. */
        private var spec = 0L

        private val probs = IntArray(PROBABILITY_SLOTS) { INITIAL_PROBABILITY }

        /** Set when the input runs dry or state degenerates; poisons all further decoding. */
        private var corrupt = false

        fun run(): ByteArray? {
            val type = input[start].toInt() // signed on purpose: negative = stored block
            val header = readBigEndianU32(start + 1)
            inPos = start + 5

            if (type < 0) return storedBlock(length = header)

            code = header
            while (true) {
                val tokenIsMatch = decodeBit(bufOffContext())
                if (corrupt) return null
                if (tokenIsMatch == 0) {
                    if (!decodeLiteral(type)) return null
                } else {
                    when (decodeMatch()) {
                        MatchResult.OK -> Unit
                        MatchResult.END_OF_STREAM -> return out.copyOf(outPos)
                        MatchResult.CORRUPT -> return null
                    }
                }
            }
        }

        // ── stored mode ──────────────────────────────────────────────────────

        private fun storedBlock(length: Long): ByteArray? {
            if (length > out.size || inPos + length > end) return null
            val n = length.toInt()
            input.copyInto(out, 0, inPos, inPos + n)
            return out.copyOf(n) // a single padding byte follows in the stream; nothing after it
        }

        // ── token decoding ───────────────────────────────────────────────────

        /**
         * Recent-match parity nudges the token-selector context: 0 initially, decremented
         * by literals, reset to 6/7 from the output parity after each match.
         */
        private var bufOff = 0
        private var lastByte = 0

        private fun bufOffContext(): Int = bufOff + 2488

        private fun decodeLiteral(shift: Int): Boolean {
            if (bufOff > 0) bufOff--
            if (outPos == out.size) return false // no room, and this is not the end marker
            val context = ((((outPos and 0x07) shl 8) + lastByte) shr shift) and 0x07
            val treeBase = context * 0xFF - 1
            var node = 1
            while (node <= 0xFF) {
                node = (node shl 1) or decodeBit(treeBase + node)
                if (corrupt) return false
            }
            out[outPos++] = node.toByte()
            lastByte = node and 0xFF
            return true
        }

        private enum class MatchResult { OK, END_OF_STREAM, CORRUPT }

        private fun decodeMatch(): MatchResult {
            // Length class: unary prefix of up to 7 speculative bits (-1 -> shortest).
            var slot = bufOffContext()
            spec = range
            var lengthClass = -1
            var bit: Int
            do {
                slot += 8
                bit = decodeBitSpeculative(slot)
                if (corrupt) return MatchResult.CORRUPT
                lengthClass += bit
            } while (bit != 0 && lengthClass < 6)

            // Length value, from a context picked by class, output position, and parity.
            var offsetGroup = lengthClass + 2033
            var offsetBias = 64
            val length: Long
            if (bit != 0 || lengthClass >= 0) {
                val lengthContext = (lengthClass shl 5) +
                    (((outPos shl lengthClass) and 0x03) shl 3) + bufOff + 2552
                length = decodeNumber(lengthClass, lengthContext, step = 8)
                if (corrupt) return MatchResult.CORRUPT
                if (length == 0xFFL) return MatchResult.END_OF_STREAM
                if (lastNumberFlag != 0 || lengthClass > 0) {
                    offsetGroup += 56
                    offsetBias = 352
                }
            } else {
                length = 1
            }

            // Offset class: another unary walk; the loop is bounded because the index
            // doubles each round and exits as soon as (index * 16) reaches the bias.
            var index = 1
            var offsetClass: Int
            do {
                offsetClass = (index shl 4) - offsetBias
                val probIndex = offsetGroup + (index shl 3)
                if (probIndex >= PROBABILITY_SLOTS) return MatchResult.CORRUPT
                bit = decodeBit(probIndex)
                if (corrupt) return MatchResult.CORRUPT
                index = (index shl 1) or bit
            } while (offsetClass < 0)

            val offset: Long
            if (bit != 0 || offsetClass > 0) {
                if (bit == 0) offsetClass -= 8
                val base = offsetClass + 2344
                if (base < 0 || base + 3 >= PROBABILITY_SLOTS) return MatchResult.CORRUPT
                offset = decodeNumber(offsetClass / 8, base, step = 1)
                if (corrupt) return MatchResult.CORRUPT
            } else {
                offset = 1
            }

            if (offset > outPos) return MatchResult.CORRUPT // reaches before the output start
            val copyEnd = outPos + length + 1
            if (copyEnd > out.size) return MatchResult.CORRUPT
            bufOff = ((copyEnd.toInt() + 1) and 0x01) + 0x06
            var from = outPos - offset.toInt()
            val until = copyEnd.toInt()
            while (outPos < until) out[outPos++] = out[from++] // may overlap: byte-wise on purpose
            lastByte = out[outPos - 1].toInt() and 0xFF
            return MatchResult.OK
        }

        /** Flag bit of the most recent [decodeNumber] call (callers branch on it). */
        private var lastNumberFlag = 0

        /**
         * Variable-length number: [bits]+2 coded bits assembled around a leading 1 —
         * two context bits, then ([bits]-4) equiprobable raw bits, then the flag bit and
         * up to two low context bits. Mirrors the format's interleaved bit order exactly.
         */
        private fun decodeNumber(bits: Int, base: Int, step: Int): Long {
            var number = 1L
            if (bits >= 3) {
                number = (number shl 1) or decodeBit(base + 3 * step).toLong()
                if (bits >= 4) {
                    number = (number shl 1) or decodeBit(base + 3 * step).toLong()
                    if (bits >= 5) {
                        renormalize()
                        var remaining = bits
                        while (remaining >= 5) {
                            if (range == 0L) { corrupt = true; return 0 }
                            number = number shl 1
                            range = range ushr 1
                            if (code < range) number++ else code -= range
                            remaining--
                        }
                    }
                }
            }
            lastNumberFlag = decodeBit(base)
            number = (number shl 1) or lastNumberFlag.toLong()
            if (bits >= 1) {
                number = (number shl 1) or decodeBit(base + step).toLong()
                if (bits >= 2) {
                    number = (number shl 1) or decodeBit(base + 2 * step).toLong()
                }
            }
            return number
        }

        // ── range coder ──────────────────────────────────────────────────────

        private fun renormalize() {
            if (range <= RENORM_LIMIT) {
                code = ((code shl 8) or nextByte()) and U32
                range = (range shl 8) and U32
            }
        }

        private fun decodeBit(probIndex: Int): Int {
            renormalize()
            if (corrupt) return 0
            val p = probs[probIndex]
            val bound = (range ushr 8) * p
            probs[probIndex] = p - (p shr 3)
            return if (code < bound) {
                range = bound
                probs[probIndex] += 31
                1
            } else {
                code -= bound
                range -= bound
                0
            }
        }

        /**
         * Length-class prefix bits renormalize against — and store their bound into —
         * the speculative range, while the real range still splits the code space.
         */
        private fun decodeBitSpeculative(probIndex: Int): Int {
            if (spec <= RENORM_LIMIT) {
                code = ((code shl 8) or nextByte()) and U32
                range = (spec shl 8) and U32
            }
            if (corrupt) return 0
            val p = probs[probIndex]
            val bound = (range ushr 8) * p
            spec = bound
            probs[probIndex] = p - (p shr 3)
            return if (code < bound) {
                range = bound
                probs[probIndex] += 31
                1
            } else {
                code -= bound
                range -= bound
                0
            }
        }

        // ── input ────────────────────────────────────────────────────────────

        private fun nextByte(): Long {
            if (inPos >= end) {
                corrupt = true
                return 0
            }
            return (input[inPos++].toInt() and 0xFF).toLong()
        }

        private fun readBigEndianU32(at: Int): Long =
            ((input[at].toInt() and 0xFF).toLong() shl 24) or
                ((input[at + 1].toInt() and 0xFF).toLong() shl 16) or
                ((input[at + 2].toInt() and 0xFF).toLong() shl 8) or
                (input[at + 3].toInt() and 0xFF).toLong()
    }
}
