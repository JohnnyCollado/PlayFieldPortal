package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Set building over a scan pass: tagged discs group into one set with disc 1 primary, an .m3u
 * beside them takes over as primary, unreadable/unresolvable playlists create nothing, folders
 * keep same-named games apart, and tag-less games stay untouched.
 * See docs/plans/README.md (C1) (DiscSetBuilderTest).
 */
class DiscSetBuilderTest {

    private val builder = DiscSetBuilder()

    private fun game(path: String, platformId: String = "psx"): Game {
        val stem = path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
        return Game(title = stem, platformId = platformId, romPath = path)
    }

    private fun m3u(entries: List<String>): DiscSetBuilder.M3uReader =
        DiscSetBuilder.M3uReader { game ->
            if (game.romPath.orEmpty().endsWith(".m3u", ignoreCase = true)) entries else null
        }

    @Test
    fun `three cue discs in one folder form one set with disc 1 primary`() {
        val games = listOf(
            game("/roms/psx/Final Fantasy VII (Disc 1).cue"),
            game("/roms/psx/Final Fantasy VII (Disc 2).cue"),
            game("/roms/psx/Final Fantasy VII (Disc 3).cue"),
        )

        val assigned = builder.assign(games) { null }

        assertEquals(1, assigned.map { it.discSetKey }.distinct().size)
        assertEquals(listOf(1, 2, 3), assigned.map { it.discNumber })
        assertEquals(1, assigned.count { it.isDiscPrimary })
        assertEquals("/roms/psx/Final Fantasy VII (Disc 1).cue", assigned.single { it.isDiscPrimary }.romPath)
    }

    @Test
    fun `an m3u beside the discs takes over as primary`() {
        val games = listOf(
            game("/roms/psx/Final Fantasy VII (Disc 1).cue"),
            game("/roms/psx/Final Fantasy VII (Disc 2).cue"),
            game("/roms/psx/Final Fantasy VII.m3u"),
        )

        val assigned = builder.assign(games) {
            m3u(listOf("Final Fantasy VII (Disc 1).cue", "Final Fantasy VII (Disc 2).cue")).read(it)
        }

        val primary = assigned.single { it.isDiscPrimary }
        assertEquals("/roms/psx/Final Fantasy VII.m3u", primary.romPath)
        assertNull(primary.discNumber)
        assertTrue(assigned.filter { it.romPath.orEmpty().endsWith(".cue") }.none { it.isDiscPrimary })
        assertEquals(1, assigned.single { it.romPath == "/roms/psx/Final Fantasy VII (Disc 1).cue" }.discNumber)
        assertEquals(2, assigned.single { it.romPath == "/roms/psx/Final Fantasy VII (Disc 2).cue" }.discNumber)
    }

    @Test
    fun `playlist entries with path prefixes still resolve`() {
        val games = listOf(
            game("/roms/psx/Final Fantasy VII (Disc 1).cue"),
            game("/roms/psx/Final Fantasy VII (Disc 2).cue"),
            game("/roms/psx/Final Fantasy VII.m3u"),
        )

        val assigned = builder.assign(games) {
            m3u(listOf("./Final Fantasy VII (Disc 1).cue", "discs/Final Fantasy VII (Disc 2).cue")).read(it)
        }

        assertEquals("/roms/psx/Final Fantasy VII.m3u", assigned.single { it.isDiscPrimary }.romPath)
        assertEquals(2, assigned.count { it.discSetKey != null && !it.isDiscPrimary })
    }

    @Test
    fun `an m3u listing files that were not scanned creates no set`() {
        val games = listOf(game("/roms/psx/Final Fantasy VII.m3u"))

        val assigned = builder.assign(games) {
            m3u(listOf("Some Other Game (Disc 1).cue", "Still Another Game (Disc 2).cue")).read(it)
        }

        assertNull(assigned.single().discSetKey)
        assertFalse(assigned.single().isDiscPrimary)
    }

    @Test
    fun `an unreadable m3u leaves discs with their own identity`() {
        val games = listOf(
            game("/roms/psx/Final Fantasy VII (Disc 1).cue"),
            game("/roms/psx/Final Fantasy VII (Disc 2).cue"),
            game("/roms/psx/Final Fantasy VII.m3u"),
        )

        val assigned = builder.assign(games) { null }

        // The tagged discs form a set on their own; the m3u joins nothing (no tag, nothing read).
        val discs = assigned.filter { it.romPath.orEmpty().endsWith(".cue") }
        assertEquals(1, discs.map { it.discSetKey }.distinct().size)
        assertNull(assigned.single { it.romPath.orEmpty().endsWith(".m3u") }.discSetKey)
    }

    @Test
    fun `two same-named games in different folders do not merge`() {
        val games = listOf(
            game("/roms/psx/NA/Final Fantasy VII (Disc 1).cue"),
            game("/roms/psx/NA/Final Fantasy VII (Disc 2).cue"),
            game("/roms/psx/EU/Final Fantasy VII (Disc 1).cue"),
            game("/roms/psx/EU/Final Fantasy VII (Disc 2).cue"),
        )

        val assigned = builder.assign(games) { null }

        assertEquals(2, assigned.mapNotNull { it.discSetKey }.distinct().size)
        assertEquals(2, assigned.count { it.isDiscPrimary })
    }

    @Test
    fun `a lone disc-tagged game forms a set of one and is primary`() {
        val games = listOf(game("/roms/dreamcast/Crazy Taxi (Disc 1).gdi", "dreamcast"))

        val assigned = builder.assign(games) { null }

        assertNotNull(assigned.single().discSetKey)
        assertEquals(1, assigned.single().discNumber)
        assertTrue(assigned.single().isDiscPrimary)
    }

    @Test
    fun `a game with no disc tag gets a null set key and is unaffected`() {
        val games = listOf(game("/roms/gba/Pokemon Emerald.gba", "gba"))

        val assigned = builder.assign(games) { null }

        assertNull(assigned.single().discSetKey)
        assertNull(assigned.single().discNumber)
        assertFalse(assigned.single().isDiscPrimary)
    }

    @Test
    fun `disc tag ordering does not change the set key`() {
        val a = builder.assign(listOf(game("/roms/psx/Final Fantasy VII (Disc 1) (USA).cue"))) { null }
        val b = builder.assign(listOf(game("/roms/psx/Final Fantasy VII (USA) (Disc 1).cue"))) { null }

        assertEquals(a.single().discSetKey, b.single().discSetKey)
    }

    @Test
    fun `windows-style paths group discs into one set`() {
        // Live-data finding: desktop ROM folders use backslash paths and one folder per disc
        // (D:\Emulators\Roms\psx\Parasite Eve II (USA) (Disc 1)\…cue). The folder suffix is the
        // disc tag, so it must not split the set — disc 1 and disc 2 belong to one game.
        val games = listOf(
            game("D:\\Emulators\\Roms\\psx\\Parasite Eve II (USA) (Disc 1)\\Parasite Eve II (USA) (Disc 1).cue"),
            game("D:\\Emulators\\Roms\\psx\\Parasite Eve II (USA) (Disc 2)\\Parasite Eve II (USA) (Disc 2).cue"),
        )

        val assigned = builder.assign(games) { null }

        assertEquals(1, assigned.mapNotNull { it.discSetKey }.distinct().size)
        assertEquals(1, assigned.count { it.isDiscPrimary })
        assertEquals(listOf(1, 2), assigned.mapNotNull { it.discNumber }.sorted())
        assertEquals(
            "D:\\Emulators\\Roms\\psx\\Parasite Eve II (USA) (Disc 1)\\Parasite Eve II (USA) (Disc 1).cue",
            assigned.single { it.isDiscPrimary }.romPath,
        )
    }

    @Test
    fun `per-disc subfolders with forward slashes group into one set`() {
        // The same one-folder-per-disc layout on POSIX-style paths.
        val games = listOf(
            game("/roms/psx/Parasite Eve II (USA) (Disc 1)/Parasite Eve II (USA) (Disc 1).cue"),
            game("/roms/psx/Parasite Eve II (USA) (Disc 2)/Parasite Eve II (USA) (Disc 2).cue"),
        )

        val assigned = builder.assign(games) { null }

        assertEquals(1, assigned.mapNotNull { it.discSetKey }.distinct().size)
        assertEquals(1, assigned.count { it.isDiscPrimary })
        assertEquals(listOf(1, 2), assigned.mapNotNull { it.discNumber }.sorted())
        assertEquals(
            "/roms/psx/Parasite Eve II (USA) (Disc 1)/Parasite Eve II (USA) (Disc 1).cue",
            assigned.single { it.isDiscPrimary }.romPath,
        )
    }

    @Test
    fun `region tag on one disc's folder only does not split the set`() {
        // Live-data finding (the handheld): Disc 1's folder carries (USA) while Disc 2's does not
        // (/storage/…/psx/Parasite Eve II (USA) (Disc 1)/ next to /storage/…/psx/Parasite Eve II
        // (Disc 2)/). Folder names are cleaned like titles — disc tag stripped, region tags removed
        // — so an inconsistent region tag between sibling disc folders cannot split the set.
        val games = listOf(
            game("/storage/408C-3861/Emulation/roms/psx/Parasite Eve II (USA) (Disc 1)/Parasite Eve II (USA) (Disc 1).cue"),
            game("/storage/408C-3861/Emulation/roms/psx/Parasite Eve II (Disc 2)/Parasite Eve II (Disc 2).cue"),
        )

        val assigned = builder.assign(games) { null }

        assertEquals(1, assigned.mapNotNull { it.discSetKey }.distinct().size)
        assertEquals(1, assigned.count { it.isDiscPrimary })
        assertEquals(listOf(1, 2), assigned.mapNotNull { it.discNumber }.sorted())
        assertEquals(
            "/storage/408C-3861/Emulation/roms/psx/Parasite Eve II (USA) (Disc 1)/Parasite Eve II (USA) (Disc 1).cue",
            assigned.single { it.isDiscPrimary }.romPath,
        )
    }

    @Test
    fun `dumps in structurally different folders still do not merge`() {
        // Folder cleaning strips parenthesized/bracketed tags (region, revision, disc) but leaves
        // real folder names alone — NA/ vs EU/ are two dumps, not one set.
        val games = listOf(
            game("/roms/psx/NA/Final Fantasy VII (Disc 1)/Final Fantasy VII (Disc 1).cue"),
            game("/roms/psx/NA/Final Fantasy VII (Disc 2)/Final Fantasy VII (Disc 2).cue"),
            game("/roms/psx/EU/Final Fantasy VII (Disc 1)/Final Fantasy VII (Disc 1).cue"),
            game("/roms/psx/EU/Final Fantasy VII (Disc 2)/Final Fantasy VII (Disc 2).cue"),
        )

        val assigned = builder.assign(games) { null }

        assertEquals(2, assigned.mapNotNull { it.discSetKey }.distinct().size)
        assertEquals(2, assigned.count { it.isDiscPrimary })
    }

    @Test
    fun `an m3u beside per-disc subfolders unifies them into one set with the m3u primary`() {
        // Live-data layout (ES-DE): one folder per disc, .m3u sitting beside them in the parent
        // (D:\Emulators\Roms\psx\Parasite Eve II (USA).m3u next to the (Disc 1)/(Disc 2) folders).
        // The disc-tagged folders already form one set on their own; the m3u adopts them and
        // takes over as primary.
        val games = listOf(
            game("D:\\Emulators\\Roms\\psx\\Parasite Eve II (USA) (Disc 1)\\Parasite Eve II (USA) (Disc 1).cue"),
            game("D:\\Emulators\\Roms\\psx\\Parasite Eve II (USA) (Disc 2)\\Parasite Eve II (USA) (Disc 2).cue"),
            game("D:\\Emulators\\Roms\\psx\\Parasite Eve II (USA).m3u"),
        )

        val assigned = builder.assign(games) {
            m3u(listOf(
                "Parasite Eve II (USA) (Disc 1).cue",
                "Parasite Eve II (USA) (Disc 2).cue",
            )).read(it)
        }

        assertEquals(1, assigned.mapNotNull { it.discSetKey }.distinct().size)
        val primary = assigned.single { it.isDiscPrimary }
        assertEquals("D:\\Emulators\\Roms\\psx\\Parasite Eve II (USA).m3u", primary.romPath)
        assertNull(primary.discNumber)
        // The discs keep their tag numbers but the m3u becomes the set's primary.
        assertEquals(1, assigned.single { it.romPath.orEmpty().contains("(Disc 1)") }.discNumber)
        assertEquals(2, assigned.single { it.romPath.orEmpty().contains("(Disc 2)") }.discNumber)
        assertTrue(assigned.filter { it.romPath.orEmpty().endsWith(".cue") }.none { it.isDiscPrimary })
    }

    @Test
    fun `an m3u beside per-disc subfolders unifies them with forward-slash paths too`() {
        // Same layout on POSIX-style paths: the cross-folder fallback must not depend on the
        // separator, only on the basename.
        val games = listOf(
            game("/roms/psx/Final Fantasy VII (Disc 1)/Final Fantasy VII (Disc 1).cue"),
            game("/roms/psx/Final Fantasy VII (Disc 2)/Final Fantasy VII (Disc 2).cue"),
            game("/roms/psx/Final Fantasy VII.m3u"),
        )

        val assigned = builder.assign(games) {
            m3u(listOf("Final Fantasy VII (Disc 1).cue", "Final Fantasy VII (Disc 2).cue")).read(it)
        }

        assertEquals(1, assigned.mapNotNull { it.discSetKey }.distinct().size)
        assertEquals("/roms/psx/Final Fantasy VII.m3u", assigned.single { it.isDiscPrimary }.romPath)
        assertEquals(2, assigned.count { it.discSetKey != null && !it.isDiscPrimary })
    }

    @Test
    fun `same detected region unifies discs even when folder region tags disagree`() {
        // Live-data finding (the handheld): Disc 1's folder carries (USA), Disc 2's does not, but
        // both .bin images are NTSC-U. The detected region — never the filename — decides.
        val games = listOf(
            game("/storage/408C-3861/Emulation/roms/psx/Parasite Eve II (USA) (Disc 1)/Parasite Eve II (USA) (Disc 1).cue")
                .copy(region = GameRegion.NTSC_U),
            game("/storage/408C-3861/Emulation/roms/psx/Parasite Eve II (Disc 2)/Parasite Eve II (Disc 2).cue")
                .copy(region = GameRegion.NTSC_U),
        )

        val assigned = builder.assign(games) { null }

        assertEquals(1, assigned.mapNotNull { it.discSetKey }.distinct().size)
        assertEquals(1, assigned.count { it.isDiscPrimary })
        assertEquals(listOf(1, 2), assigned.mapNotNull { it.discNumber }.sorted())
        assertEquals(GameRegion.NTSC_U, assigned.single { it.isDiscPrimary }.region)
    }

    @Test
    fun `detected region from the reader drives unification and is persisted on the rows`() {
        // The reader (the content-based detector) fills region for games that carry none; the
        // same NTSC-U answer then keeps the mismatched-folder pair in one set.
        val games = listOf(
            game("/roms/psx/Parasite Eve II (USA) (Disc 1)/Parasite Eve II (USA) (Disc 1).cue"),
            game("/roms/psx/Parasite Eve II (Disc 2)/Parasite Eve II (Disc 2).cue"),
        )

        val assigned = builder.assign(games, { GameRegion.NTSC_U }) { null }

        assertEquals(1, assigned.mapNotNull { it.discSetKey }.distinct().size)
        assertEquals(2, assigned.count { it.region == GameRegion.NTSC_U })
    }

    @Test
    fun `conflicting detected regions split sibling disc folders into two sets`() {
        // Genuinely different dumps (NTSC-U vs PAL) stay separate — the region-split only fires
        // when every member carries a known region.
        val games = listOf(
            game("/roms/psx/Final Fantasy VII (USA) (Disc 1)/Final Fantasy VII (Disc 1).cue")
                .copy(region = GameRegion.NTSC_U),
            game("/roms/psx/Final Fantasy VII (USA) (Disc 2)/Final Fantasy VII (Disc 2).cue")
                .copy(region = GameRegion.NTSC_U),
            game("/roms/psx/Final Fantasy VII (Europe) (Disc 1)/Final Fantasy VII (Disc 1).cue")
                .copy(region = GameRegion.PAL),
            game("/roms/psx/Final Fantasy VII (Europe) (Disc 2)/Final Fantasy VII (Disc 2).cue")
                .copy(region = GameRegion.PAL),
        )

        val assigned = builder.assign(games) { null }

        assertEquals(2, assigned.mapNotNull { it.discSetKey }.distinct().size)
        assertEquals(2, assigned.count { it.isDiscPrimary })
    }

    @Test
    fun `a disc with unknown region keeps the group merged`() {
        // One disc unreadable or in a compressed container (region null) must not break the set —
        // unknown only ever falls back to merging.
        val games = listOf(
            game("/roms/psx/Final Fantasy VII (Disc 1)/Final Fantasy VII (Disc 1).cue")
                .copy(region = GameRegion.NTSC_U),
            game("/roms/psx/Final Fantasy VII (Disc 2)/Final Fantasy VII (Disc 2).cue"),
        )

        val assigned = builder.assign(games) { null }

        assertEquals(1, assigned.mapNotNull { it.discSetKey }.distinct().size)
        assertEquals(1, assigned.count { it.isDiscPrimary })
    }

    @Test
    fun `reconcile persists a newly detected region`() {
        // Rows scanned before region detection existed carry null; reconcile re-reads the image,
        // detects NTSC-U, and returns both rows so the caller upserts the region.
        val disc1 = setGame("/roms/psx/Final Fantasy VII (Disc 1).cue", "psx\u0001/roms/psx\u0001Final Fantasy VII", 1, true)
        val disc2 = setGame("/roms/psx/Final Fantasy VII (Disc 2).cue", "psx\u0001/roms/psx\u0001Final Fantasy VII", 2, false)

        val updated = builder.reconcile(listOf(disc1, disc2), { GameRegion.NTSC_U }) { null }

        assertEquals(2, updated.size)
        assertTrue(updated.all { it.region == GameRegion.NTSC_U })
    }

    private fun setGame(
        path: String,
        key: String?,
        number: Int?,
        primary: Boolean,
        platformId: String = "psx",
    ): Game = game(path, platformId).copy(discSetKey = key, discNumber = number, isDiscPrimary = primary)

    @Test
    fun `reconcile joins a newly added disc into an existing m3u set`() {
        // Incremental scan (plan follow-up): the m3u and discs 1-2 were scanned earlier and carry
        // the m3u's set key; disc 3 just arrived (its stored key is the pre-fix per-folder form).
        // Reconcile must re-derive the union and pull disc 3 into the m3u's set.
        val m3uKey = "psx\u0001/roms/psx\u0001Final Fantasy VII"
        val existing = listOf(
            setGame("/roms/psx/Final Fantasy VII.m3u", m3uKey, null, true),
            setGame("/roms/psx/Final Fantasy VII (Disc 1)/Final Fantasy VII (Disc 1).cue", m3uKey, 1, false),
            setGame("/roms/psx/Final Fantasy VII (Disc 2)/Final Fantasy VII (Disc 2).cue", m3uKey, 2, false),
        )
        val disc3 = setGame(
            "/roms/psx/Final Fantasy VII (Disc 3)/Final Fantasy VII (Disc 3).cue",
            "psx\u0001/roms/psx/Final Fantasy VII (Disc 3)\u0001Final Fantasy VII",
            3,
            true,
        )

        val updated = builder.reconcile(existing + disc3) {
            m3u(listOf(
                "Final Fantasy VII (Disc 1).cue",
                "Final Fantasy VII (Disc 2).cue",
                "Final Fantasy VII (Disc 3).cue",
            )).read(it)
        }

        assertEquals(1, updated.size)
        val joined = updated.single()
        assertEquals(disc3.romPath, joined.romPath)
        assertEquals(m3uKey, joined.discSetKey)
        assertEquals(3, joined.discNumber)
        assertFalse(joined.isDiscPrimary)
    }

    @Test
    fun `reconcile flips the primary to a newly added lower disc`() {
        // No m3u: the first scan found disc 2 (primary of the folder's set); disc 1 arrives later.
        // The union's lowest disc number must win the primary, flipping the existing row.
        val key = "psx\u0001/roms/psx\u0001Final Fantasy VII"
        val disc2 = setGame("/roms/psx/Final Fantasy VII (Disc 2).cue", key, 2, true)
        val disc1 = setGame("/roms/psx/Final Fantasy VII (Disc 1).cue", key, 1, true)

        val updated = builder.reconcile(listOf(disc2, disc1)) { null }

        assertEquals(1, updated.size)
        val flipped = updated.single()
        assertEquals(disc2.romPath, flipped.romPath)
        assertFalse(flipped.isDiscPrimary)
    }

    @Test
    fun `reconcile is a no-op on a fully correct batch`() {
        val m3uKey = "psx\u0001/roms/psx\u0001Final Fantasy VII"
        val correct = listOf(
            setGame("/roms/psx/Final Fantasy VII.m3u", m3uKey, null, true),
            setGame("/roms/psx/Final Fantasy VII (Disc 1)/Final Fantasy VII (Disc 1).cue", m3uKey, 1, false),
            setGame("/roms/psx/Final Fantasy VII (Disc 2)/Final Fantasy VII (Disc 2).cue", m3uKey, 2, false),
            setGame("/roms/gba/Pokemon Emerald.gba", null, null, false, "gba"),
        )

        val updated = builder.reconcile(correct) {
            m3u(listOf(
                "Final Fantasy VII (Disc 1).cue",
                "Final Fantasy VII (Disc 2).cue",
            )).read(it)
        }

        assertTrue(updated.isEmpty())
    }

    @Test
    fun `reconcile adopts existing discs into a newly added m3u set`() {
        // The reverse direction: discs 1-2 were scanned as their own per-folder sets, then the user
        // adds an m3u beside the folders. Single-pass assign for the lone m3u resolves nothing, so
        // reconcile must pull the existing discs into the m3u's set and make the m3u primary.
        val m3u = setGame("/roms/psx/Final Fantasy VII.m3u", null, null, false)
        val disc1 = setGame(
            "/roms/psx/Final Fantasy VII (Disc 1)/Final Fantasy VII (Disc 1).cue",
            "psx\u0001/roms/psx/Final Fantasy VII (Disc 1)\u0001Final Fantasy VII",
            1,
            true,
        )
        val disc2 = setGame(
            "/roms/psx/Final Fantasy VII (Disc 2)/Final Fantasy VII (Disc 2).cue",
            "psx\u0001/roms/psx/Final Fantasy VII (Disc 2)\u0001Final Fantasy VII",
            2,
            true,
        )

        val updated = builder.reconcile(listOf(m3u, disc1, disc2)) {
            m3u(listOf("Final Fantasy VII (Disc 1).cue", "Final Fantasy VII (Disc 2).cue")).read(it)
        }

        val m3uKey = "psx\u0001/roms/psx\u0001Final Fantasy VII"
        assertEquals(3, updated.size)
        assertEquals(m3uKey, updated.single { it.romPath.orEmpty().endsWith(".m3u") }.discSetKey)
        assertTrue(updated.single { it.romPath.orEmpty().endsWith(".m3u") }.isDiscPrimary)
        assertEquals(1, updated.single { it.romPath.orEmpty().contains("(Disc 1)") }.discNumber)
        assertEquals(m3uKey, updated.single { it.romPath.orEmpty().contains("(Disc 1)") }.discSetKey)
    }

    @Test
    fun `an unreadable existing playlist clears its stale set assignment`() {
        val key = "psx\u0001/roms/psx\u0001Final Fantasy VII"
        val playlist = setGame("/roms/psx/Final Fantasy VII.m3u", key, null, true)

        val updated = builder.reconcile(listOf(playlist)) { null }

        assertEquals(1, updated.size)
        assertNull(updated.single().discSetKey)
        assertNull(updated.single().discNumber)
        assertFalse(updated.single().isDiscPrimary)
    }

    // ── Stale companion rows ────────────────────────────────────────────────────────────────────
    // A library scanned before companion suppression existed still holds a row for every .bin
    // beside its .cue. A scan never adds those rows now, but reconcile runs over the stored rows.

    private fun sheets(vararg linesByPath: Pair<String, List<String>>): DiscSetBuilder.SheetReader {
        val byPath = linesByPath.toMap()
        return DiscSetBuilder.SheetReader { game -> byPath[game.romPath] }
    }

    @Test
    fun `reconcile drops a stale bin companion from its cue's set and makes the cue primary`() {
        // The device layout: one folder per disc, each holding a .cue and the .bin it lists. The
        // stored state is what the path tie-break produced — ".bin" sorts before ".cue".
        val key = "psx\u0001/roms/psx/Parasite Eve II\u0001Parasite Eve II"
        val bin1 = "/roms/psx/Parasite Eve II (USA) (Disc 1)/Parasite Eve II (USA) (Disc 1).bin"
        val cue1 = "/roms/psx/Parasite Eve II (USA) (Disc 1)/Parasite Eve II (USA) (Disc 1).cue"
        val bin2 = "/roms/psx/Parasite Eve II (Disc 2)/Parasite Eve II (Disc 2).bin"
        val cue2 = "/roms/psx/Parasite Eve II (Disc 2)/Parasite Eve II (Disc 2).cue"
        val rows = listOf(
            setGame(bin1, key, 1, true),
            setGame(cue1, key, 1, false),
            setGame(bin2, key, 2, false),
            setGame(cue2, key, 2, false),
        )

        val updated = builder.reconcile(
            rows,
            sheetReader = sheets(
                cue1 to listOf("FILE \"Parasite Eve II (USA) (Disc 1).bin\" BINARY", "  TRACK 01 MODE2/2352"),
                cue2 to listOf("FILE \"Parasite Eve II (Disc 2).bin\" BINARY", "  TRACK 01 MODE2/2352"),
            ),
        ) { null }.associateBy { it.romPath }

        // Both .bin rows leave the set entirely; disc 1's .cue takes the primary. Disc 2's .cue
        // was already correct, so it is not rewritten.
        assertEquals(setOf(bin1, bin2, cue1), updated.keys)
        for (bin in listOf(bin1, bin2)) {
            assertNull(updated.getValue(bin).discSetKey)
            assertNull(updated.getValue(bin).discNumber)
            assertFalse(updated.getValue(bin).isDiscPrimary)
        }
        assertEquals(key, updated.getValue(cue1).discSetKey)
        assertTrue(updated.getValue(cue1).isDiscPrimary)
    }

    @Test
    fun `reconcile drops disc-tagged track files listed by a cue`() {
        // A multi-track dump: the track files carry the disc tag too, so they would join the set.
        val key = "psx\u0001/roms/psx\u0001Game"
        val cue = "/roms/psx/Game (Disc 1).cue"
        val track1 = "/roms/psx/Game (Disc 1) (Track 1).bin"
        val track2 = "/roms/psx/Game (Disc 1) (Track 2).bin"
        val rows = listOf(
            setGame(track1, key, 1, true),
            setGame(track2, key, 1, false),
            setGame(cue, key, 1, false),
        )

        val updated = builder.reconcile(
            rows,
            sheetReader = sheets(
                cue to listOf(
                    "FILE \"Game (Disc 1) (Track 1).bin\" BINARY",
                    "FILE \"Game (Disc 1) (Track 2).bin\" BINARY",
                ),
            ),
        ) { null }.associateBy { it.romPath }

        assertNull(updated.getValue(track1).discSetKey)
        assertNull(updated.getValue(track2).discSetKey)
        assertTrue(updated.getValue(cue).isDiscPrimary)
    }

    @Test
    fun `a bin no sheet lists stays a disc of its set`() {
        // Sheet-less .bin dumps are real discs. A sheet in the folder that lists other files must
        // not take them out of the set.
        val key = "psx\u0001/roms/psx\u0001Final Fantasy VII"
        val rows = listOf(
            setGame("/roms/psx/Final Fantasy VII (Disc 1).bin", key, 1, true),
            setGame("/roms/psx/Final Fantasy VII (Disc 2).bin", key, 2, false),
            game("/roms/psx/Resident Evil.cue"),
        )

        val updated = builder.reconcile(
            rows,
            sheetReader = sheets("/roms/psx/Resident Evil.cue" to listOf("FILE \"Resident Evil.bin\" BINARY")),
        ) { null }

        assertTrue(updated.isEmpty())
    }

    @Test
    fun `an unreadable cue still beats its same-numbered bin for the primary`() {
        // With no sheet contents the .bin cannot be proven a companion, so it stays in the set —
        // but the sheet is the launch file and must not lose the primary to a path tie-break.
        val key = "psx\u0001/roms/psx\u0001Parasite Eve II"
        val bin = "/roms/psx/Parasite Eve II (Disc 1).bin"
        val cue = "/roms/psx/Parasite Eve II (Disc 1).cue"

        val updated = builder.reconcile(
            listOf(setGame(bin, key, 1, true), setGame(cue, key, 1, false)),
        ) { null }.associateBy { it.romPath }

        assertFalse(updated.getValue(bin).isDiscPrimary)
        assertEquals(key, updated.getValue(bin).discSetKey)
        assertTrue(updated.getValue(cue).isDiscPrimary)
    }

    // ── Missing discs ───────────────────────────────────────────────────────────────────────────
    // The primary is the row the library shows and launches, so it must be a disc that is there.

    @Test
    fun `a present disc takes the primary from a missing lower disc`() {
        val key = "psx\u0001/roms/psx\u0001Final Fantasy VII"
        val disc1 = setGame("/roms/psx/Final Fantasy VII (Disc 1).cue", key, 1, true).copy(isMissing = true)
        val disc2 = setGame("/roms/psx/Final Fantasy VII (Disc 2).cue", key, 2, false)
        val disc3 = setGame("/roms/psx/Final Fantasy VII (Disc 3).cue", key, 3, false)

        val updated = builder.reconcile(listOf(disc1, disc2, disc3)) { null }.associateBy { it.romPath }

        // Disc 1 stays in the set under its own number; the lowest PRESENT disc leads.
        assertEquals(setOf(disc1.romPath, disc2.romPath), updated.keys)
        assertFalse(updated.getValue(disc1.romPath).isDiscPrimary)
        assertEquals(key, updated.getValue(disc1.romPath).discSetKey)
        assertEquals(1, updated.getValue(disc1.romPath).discNumber)
        assertTrue(updated.getValue(disc2.romPath).isDiscPrimary)
    }

    @Test
    fun `the lowest disc takes the primary back once it is present again`() {
        val key = "psx\u0001/roms/psx\u0001Final Fantasy VII"
        val disc1 = setGame("/roms/psx/Final Fantasy VII (Disc 1).cue", key, 1, false)
        val disc2 = setGame("/roms/psx/Final Fantasy VII (Disc 2).cue", key, 2, true)

        val updated = builder.reconcile(listOf(disc1, disc2)) { null }.associateBy { it.romPath }

        assertTrue(updated.getValue(disc1.romPath).isDiscPrimary)
        assertFalse(updated.getValue(disc2.romPath).isDiscPrimary)
    }

    @Test
    fun `a set whose discs are all missing keeps the lowest disc primary`() {
        // An unplugged card flags every disc missing; that must not reshuffle the set.
        val key = "psx\u0001/roms/psx\u0001Final Fantasy VII"
        val rows = listOf(
            setGame("/roms/psx/Final Fantasy VII (Disc 1).cue", key, 1, true).copy(isMissing = true),
            setGame("/roms/psx/Final Fantasy VII (Disc 2).cue", key, 2, false).copy(isMissing = true),
        )

        assertTrue(builder.reconcile(rows) { null }.isEmpty())
    }

    // ── The Choose Disc pick ────────────────────────────────────────────────────────────────────
    // Picking a disc makes it the set's primary. A scan re-derives the primary from disc numbers,
    // so the pick is stored on the row (isDiscPreferred) and honored here, or it would not survive.

    @Test
    fun `a preferred disc stays primary across reconcile`() {
        val key = "psx\u0001/roms/psx\u0001Final Fantasy VII"
        val rows = listOf(
            setGame("/roms/psx/Final Fantasy VII (Disc 1).cue", key, 1, false),
            setGame("/roms/psx/Final Fantasy VII (Disc 2).cue", key, 2, true).copy(isDiscPreferred = true),
        )

        assertTrue(builder.reconcile(rows) { null }.isEmpty())
    }

    @Test
    fun `a preferred disc stays primary over the playlist`() {
        val key = "psx\u0001/roms/psx\u0001Final Fantasy VII"
        val rows = listOf(
            setGame("/roms/psx/Final Fantasy VII.m3u", key, null, false),
            setGame("/roms/psx/Final Fantasy VII (Disc 1).cue", key, 1, false),
            setGame("/roms/psx/Final Fantasy VII (Disc 2).cue", key, 2, true).copy(isDiscPreferred = true),
        )

        val updated = builder.reconcile(rows) {
            m3u(listOf("Final Fantasy VII (Disc 1).cue", "Final Fantasy VII (Disc 2).cue")).read(it)
        }

        assertTrue(updated.isEmpty())
    }

    @Test
    fun `a missing preferred disc yields the primary and keeps the pick for its return`() {
        val key = "psx\u0001/roms/psx\u0001Final Fantasy VII"
        val disc1 = setGame("/roms/psx/Final Fantasy VII (Disc 1).cue", key, 1, false)
        val disc2 = setGame("/roms/psx/Final Fantasy VII (Disc 2).cue", key, 2, true)
            .copy(isDiscPreferred = true, isMissing = true)

        val gone = builder.reconcile(listOf(disc1, disc2)) { null }.associateBy { it.romPath }

        assertTrue(gone.getValue(disc1.romPath).isDiscPrimary)
        assertFalse(gone.getValue(disc2.romPath).isDiscPrimary)
        assertTrue(gone.getValue(disc2.romPath).isDiscPreferred)

        // The file comes back: the pick is still on the row, so it takes the primary again.
        val back = builder.reconcile(
            listOf(gone.getValue(disc1.romPath), gone.getValue(disc2.romPath).copy(isMissing = false)),
        ) { null }.associateBy { it.romPath }

        assertFalse(back.getValue(disc1.romPath).isDiscPrimary)
        assertTrue(back.getValue(disc2.romPath).isDiscPrimary)
    }

    @Test
    fun `a row that leaves its set loses the pick`() {
        val key = "psx\u0001/roms/psx\u0001Final Fantasy VII"
        val playlist = setGame("/roms/psx/Final Fantasy VII.m3u", key, null, true).copy(isDiscPreferred = true)

        val updated = builder.reconcile(listOf(playlist)) { null }

        assertNull(updated.single().discSetKey)
        assertFalse(updated.single().isDiscPreferred)
    }

    @Test
    fun `untagged playlist entries take playlist order for disc numbers`() {
        val games = listOf(
            game("/roms/psx/Resident Evil.cue"),
            game("/roms/psx/Resident Evil (Disc 2).cue"),
            game("/roms/psx/Resident Evil.m3u"),
        )

        val assigned = builder.assign(games) {
            m3u(listOf("Resident Evil.cue", "Resident Evil (Disc 2).cue")).read(it)
        }

        assertEquals("/roms/psx/Resident Evil.m3u", assigned.single { it.isDiscPrimary }.romPath)
        assertEquals(1, assigned.single { it.romPath == "/roms/psx/Resident Evil.cue" }.discNumber)
        assertEquals(2, assigned.single { it.romPath == "/roms/psx/Resident Evil (Disc 2).cue" }.discNumber)
        assertEquals(1, assigned.mapNotNull { it.discSetKey }.distinct().size)
    }

    // ── region read memoisation ──────────────────────────────────────────────────
    // derive calls regionOf from two places: the region-split step and the row-enrichment step.
    // Reading a disc head costs up to 256 KB per game, so the batch memoises by path — but the memo
    // must hold a NULL answer too. A map keyed by nullness cannot tell "cached, undetectable" from
    // "not cached", so every disc whose region cannot be read is re-read once per call site,
    // for exactly the population where detection is already failing.

    /** A [DiscSetBuilder.RegionReader] that records how often each path was actually read. */
    private class CountingRegionReader(
        private val answer: (Game) -> GameRegion? = { null },
    ) : DiscSetBuilder.RegionReader {
        val calls = mutableMapOf<String, Int>()

        override fun read(game: Game): GameRegion? {
            calls.merge(game.romPath.orEmpty(), 1, Int::plus)
            return answer(game)
        }
    }

    @Test
    fun `an undetectable region is read once per path, not once per call site`() {
        val disc1 = "/roms/psx/Parasite Eve II (USA) (Disc 1)/Parasite Eve II (USA) (Disc 1).cue"
        val disc2 = "/roms/psx/Parasite Eve II (Disc 2)/Parasite Eve II (Disc 2).cue"
        val reader = CountingRegionReader { null }

        builder.assign(listOf(game(disc1), game(disc2)), reader) { null }

        assertEquals(mapOf(disc1 to 1, disc2 to 1), reader.calls)
    }

    @Test
    fun `an undetectable region is still read only once when reconciling`() {
        val disc1 = "/roms/psx/Parasite Eve II (USA) (Disc 1)/Parasite Eve II (USA) (Disc 1).cue"
        val disc2 = "/roms/psx/Parasite Eve II (Disc 2)/Parasite Eve II (Disc 2).cue"
        val reader = CountingRegionReader { null }

        builder.reconcile(listOf(game(disc1), game(disc2)), reader) { null }

        assertEquals(mapOf(disc1 to 1, disc2 to 1), reader.calls)
    }

    @Test
    fun `a detected region is read once per path`() {
        // Guard on the working path: a non-null answer already memoises, and must keep doing so.
        val disc1 = "/roms/psx/Parasite Eve II (USA) (Disc 1)/Parasite Eve II (USA) (Disc 1).cue"
        val disc2 = "/roms/psx/Parasite Eve II (Disc 2)/Parasite Eve II (Disc 2).cue"
        val reader = CountingRegionReader { GameRegion.NTSC_U }

        val assigned = builder.assign(listOf(game(disc1), game(disc2)), reader) { null }

        assertEquals(mapOf(disc1 to 1, disc2 to 1), reader.calls)
        assertEquals(2, assigned.count { it.region == GameRegion.NTSC_U })
    }

    @Test
    fun `a region split does not re-read the images it splits on`() {
        // The split step consults regionOf twice — once to collect the regions, once to build the
        // per-region key. Both must come from the memo.
        val usa1 = "/roms/psx/Final Fantasy VII (USA) (Disc 1)/Final Fantasy VII (Disc 1).cue"
        val usa2 = "/roms/psx/Final Fantasy VII (USA) (Disc 2)/Final Fantasy VII (Disc 2).cue"
        val eu1 = "/roms/psx/Final Fantasy VII (Europe) (Disc 1)/Final Fantasy VII (Disc 1).cue"
        val eu2 = "/roms/psx/Final Fantasy VII (Europe) (Disc 2)/Final Fantasy VII (Disc 2).cue"
        val reader = CountingRegionReader { g ->
            if (g.romPath.orEmpty().contains("(USA)")) GameRegion.NTSC_U else GameRegion.PAL
        }

        val assigned = builder.assign(
            listOf(game(usa1), game(usa2), game(eu1), game(eu2)),
            reader,
        ) { null }

        assertEquals(mapOf(usa1 to 1, usa2 to 1, eu1 to 1, eu2 to 1), reader.calls)
        assertEquals(2, assigned.mapNotNull { it.discSetKey }.distinct().size)
    }

    @Test
    fun `a stored region survives an undetectable read and is not re-read`() {
        // The `?: game.region` fallback is what stops a transient read failure wiping a known
        // region. Memoising a null must not defeat it.
        val path = "/roms/psx/Final Fantasy VII (Disc 1)/Final Fantasy VII (Disc 1).cue"
        val stored = game(path).copy(region = GameRegion.NTSC_U)
        val reader = CountingRegionReader { null }

        val assigned = builder.assign(listOf(stored), reader) { null }

        assertEquals(mapOf(path to 1), reader.calls)
        assertEquals(GameRegion.NTSC_U, assigned.single().region)
    }
}
