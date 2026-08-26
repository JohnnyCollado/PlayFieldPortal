package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.domain.model.GameRegion

/**
 * Content-based disc region detection — reads a region out of the first bytes of a disc image,
 * never out of the filename. Pure byte/string logic (no Android, no file access) so it is
 * unit-testable and shared by every scan path.
 *
 * Supported platforms (the disc-based ones with multi-disc capability; PC Engine deliberately
 * excluded — it has no reliable in-image marker and is effectively all NTSC-J):
 *  - psx      → license string in the first data sectors, serial prefix fallback
 *  - ps2      → `REGION=` in SYSTEM.CNF, serial prefix fallback
 *  - psp      → product code in UMD_DATA.BIN
 *  - gc / wii → region byte in boot.bin (game-id char fallback)
 *  - saturn / dreamcast / segacd → region char in IP.BIN
 *  - x360     → game-region bitfield in default.xex (XEX2 optional header, best-effort)
 *  - ps3      → TITLE_ID prefix in PARAM.SFO (see [detectPs3Sfo])
 *
 * Every detector is deliberately conservative: ambiguity or an unexpected layout returns null
 * (→ region Unknown), which never splits a disc set — it only falls back to merging.
 */
object DiscRegionDetectors {

    // ── PS1 ──────────────────────────────────────────────────────────────────
    // Pressed discs carry "Licensed by Sony Computer Entertainment <America|Europe|Inc.>" in the
    // first data sectors (sector 4 of the data track; the search is offset-agnostic so both
    // 2048-byte and raw 2352-byte sector dumps work). Fallback: the region-encoding serial
    // (SLUS/SCUS = US, SLES/SCES = Europe, SLPS/SCPS/SLPM = Japan) that appears in SYSTEM.CNF.
    fun detectPsx(head: ByteArray): GameRegion? {
        val marker = head.indexOfAscii("Licensed by Sony Computer Entertainment")
        if (marker >= 0) {
            val after = head.copyOfRange(marker, minOf(marker + 120, head.size))
                .toString(Charsets.ISO_8859_1)
            val region = when {
                after.contains("America", ignoreCase = true) -> GameRegion.NTSC_U
                after.contains("Europe", ignoreCase = true) -> GameRegion.PAL
                after.contains("Inc.", ignoreCase = true) -> GameRegion.NTSC_J
                else -> null
            }
            if (region != null) return region
        }
        return serialRegion(head, PSX_NTSC_U_SERIAL, PSX_PAL_SERIAL, PSX_NTSC_J_SERIAL)
    }

    // ── PS2 ──────────────────────────────────────────────────────────────────
    // Retail discs ship SYSTEM.CNF with an explicit REGION=NTSC-U/PAL/NTSC-J line.
    fun detectPs2(head: ByteArray): GameRegion? {
        val text = head.toString(Charsets.ISO_8859_1)
        val match = Regex("""REGION\s*=\s*(NTSC-U|PAL|NTSC-J)""", RegexOption.IGNORE_CASE).find(text)
        if (match != null) {
            return when (match.groupValues[1].uppercase()) {
                "NTSC-U" -> GameRegion.NTSC_U
                "PAL" -> GameRegion.PAL
                else -> GameRegion.NTSC_J
            }
        }
        return serialRegion(head, PSX_NTSC_U_SERIAL, PSX_PAL_SERIAL, PSX_NTSC_J_SERIAL)
    }

    // ── PSP ──────────────────────────────────────────────────────────────────
    // UMD_DATA.BIN (near the start of the image) carries the product code whose prefix encodes
    // region: ULUS/UCUS = US, ULES/UCES = Europe, ULJM/UCJS = Japan (PSN: NPUH/NPEG/NPJH).
    fun detectPsp(head: ByteArray): GameRegion? {
        val text = head.toString(Charsets.ISO_8859_1)
        return when {
            Regex("""\b(ULUS|UCUS|NPUH|ULAS|UCAS)\s?-\s?\d""").containsMatchIn(text) -> GameRegion.NTSC_U
            Regex("""\b(ULES|UCES|NPEG|ULES)\s?-\s?\d""").containsMatchIn(text) -> GameRegion.PAL
            Regex("""\b(ULJM|UCJS|NPJH)\s?-\s?\d""").containsMatchIn(text) -> GameRegion.NTSC_J
            else -> null
        }
    }

    // ── GameCube / Wii ───────────────────────────────────────────────────────
    // boot.bin starts at offset 0 of the disc: a 6-char game id ("GALE01", "RSPE01", …) whose
    // 4th char encodes region (E = USA, J = Japan, P/W/D/F/I/S/X = Europe), and a big-endian
    // region field at 0x58 — 0 = Japan, 1 = USA, 2 = Europe. The 0x58 field is primary; the id
    // char is the fallback. A non-GC/Wii first char (G/R/S/D) rejects the header outright so a
    // coincidental 0/1/2 in unrelated bytes can never mislabel a disc.
    fun detectBootBin(head: ByteArray): GameRegion? {
        if (head.size < 0x5C) return null
        val id = head.copyOfRange(0, 6).toString(Charsets.ISO_8859_1)
        if (id.any { !it.isLetterOrDigit() }) return null
        if (id.getOrNull(0)?.uppercaseChar() !in setOf('G', 'R', 'S', 'D')) return null
        when (beInt(head, 0x58)) {
            0 -> return GameRegion.NTSC_J
            1 -> return GameRegion.NTSC_U
            2 -> return GameRegion.PAL
        }
        return when (id.getOrNull(3)?.uppercaseChar()) {
            'E' -> GameRegion.NTSC_U
            'J' -> GameRegion.NTSC_J
            'P', 'W', 'D', 'F', 'I', 'S', 'X' -> GameRegion.PAL
            else -> null
        }
    }

    // ── Saturn / Dreamcast / Sega CD ─────────────────────────────────────────
    // IP.BIN at the start of the data track: "SEGA SEGASATURN " (Saturn / Mega-CD) or
    // "SEGA SEGAKATANA " (Dreamcast), with a region char ('J' Japan, 'T'/'U' USA, 'E' Europe) in
    // the header — 0x10 for Dreamcast, 0x20 for Saturn.
    fun detectIpBin(head: ByteArray): GameRegion? {
        val text = head.toString(Charsets.ISO_8859_1)
        val regionByte = when {
            text.startsWith("SEGA SEGAKATANA") -> head.getOrNull(0x10)
            text.startsWith("SEGA SEGASATURN") -> head.getOrNull(0x20)
            else -> return null
        } ?: return null
        return when (regionByte.toInt().toChar().uppercaseChar()) {
            'J' -> GameRegion.NTSC_J
            'T', 'U' -> GameRegion.NTSC_U
            'E' -> GameRegion.PAL
            else -> null
        }
    }

    // ── Xbox 360 ─────────────────────────────────────────────────────────────
    // default.xex starts with "XEX2"; its optional headers include Execution Info (type 0x0001)
    // whose game-region bitfield (data + 0x1C) says 0x1 = Americas, 0x2 = Japan, 0x4 = Europe.
    // Best-effort per the XexTool-documented layout: an unrecognized layout yields null (safe —
    // region Unknown only ever falls back to merging, never mis-splits).
    fun detectX360(head: ByteArray): GameRegion? {
        val start = head.indexOfAscii("XEX2")
        if (start < 0 || start + 0x1C > head.size) return null
        val count = leInt(head, start + 0x18)
        if (count <= 0 || count > 64) return null
        var off = start + 0x1C
        repeat(count) {
            if (off + 8 > head.size) return null
            val size = leInt(head, off)
            val type = leInt(head, off + 4)
            val data = off + 8
            // The size field covers the whole entry including this 8-byte header.
            if (size < 8 || off > head.size - size) return null
            if (type == 0x00000001 && data + 0x20 <= head.size) {  // Execution Info
                val region = leInt(head, data + 0x1C)
                return when {
                    region and 0x01 != 0 -> GameRegion.NTSC_U
                    region and 0x02 != 0 -> GameRegion.NTSC_J
                    region and 0x04 != 0 -> GameRegion.PAL
                    else -> null
                }
            }
            off += size
        }
        return null
    }

    // ── PS3 ──────────────────────────────────────────────────────────────────
    // PARAM.SFO (in the PS3_GAME folder) stores the TITLE_ID whose prefix encodes region:
    // BLUS/BCUS/NPUB = US, BLES/BCES/NPEP = Europe, BLJM/BCJS/NPJB = Japan.
    fun detectPs3Sfo(sfo: ByteArray): GameRegion? {
        val text = sfo.toString(Charsets.ISO_8859_1)
        return when {
            Regex("""\b(BLUS|BCUS|NPUB)\d{5}""").containsMatchIn(text) -> GameRegion.NTSC_U
            Regex("""\b(BLES|BCES|NPEP)\d{5}""").containsMatchIn(text) -> GameRegion.PAL
            Regex("""\b(BLJM|BCJS|NPJB)\d{5}""").containsMatchIn(text) -> GameRegion.NTSC_J
            else -> null
        }
    }

    // ── shared helpers ───────────────────────────────────────────────────────

    private val PSX_NTSC_U_SERIAL = Regex("""\b(SLUS|SCUS|PBPX|PAPX)[-_ ]?\d""")
    private val PSX_PAL_SERIAL = Regex("""\b(SLES|SCES|SIPS)[-_ ]?\d""")
    private val PSX_NTSC_J_SERIAL = Regex("""\b(SLPS|SCPS|SLPM|SCPM)[-_ ]?\d""")

    private fun serialRegion(head: ByteArray, ntscU: Regex, pal: Regex, ntscJ: Regex): GameRegion? {
        val text = head.toString(Charsets.ISO_8859_1)
        return when {
            ntscU.containsMatchIn(text) -> GameRegion.NTSC_U
            pal.containsMatchIn(text) -> GameRegion.PAL
            ntscJ.containsMatchIn(text) -> GameRegion.NTSC_J
            else -> null
        }
    }

    private fun ByteArray.indexOfAscii(text: String, from: Int = 0): Int {
        val needle = text.encodeToByteArray()
        if (needle.isEmpty() || needle.size > size - from) return -1
        outer@ for (i in from..size - needle.size) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun beInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
