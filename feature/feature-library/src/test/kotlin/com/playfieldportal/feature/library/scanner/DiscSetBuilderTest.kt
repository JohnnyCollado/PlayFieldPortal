package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.domain.model.Game
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
 * See docs/plans/multi-disc-games-plan.md step 3 (DiscSetBuilderTest).
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
        // (D:\Emulators\Roms\psx\Parasite Eve II (USA) (Disc 1)\…cue). The key is per-folder, so
        // per-disc folders stay separate sets (plan: over-merging is worse) — but the tag must
        // still parse and form a set within its own folder.
        val games = listOf(
            game("D:\\Emulators\\Roms\\psx\\Parasite Eve II (USA) (Disc 1)\\Parasite Eve II (USA) (Disc 1).cue"),
            game("D:\\Emulators\\Roms\\psx\\Parasite Eve II (USA) (Disc 2)\\Parasite Eve II (USA) (Disc 2).cue"),
        )

        val assigned = builder.assign(games) { null }

        // Each disc lives in its own folder → its own set, primary, disc 1…2. An .m3u beside the
        // folders unifies them into one set — see the cross-folder m3u tests below.
        assertEquals(2, assigned.count { it.isDiscPrimary })
        assertEquals(listOf(1, 2), assigned.mapNotNull { it.discNumber }.sorted())
        assertNotNull(assigned.single { it.romPath.orEmpty().contains("(Disc 1)") }.discSetKey)
    }

    @Test
    fun `an m3u beside per-disc subfolders unifies them into one set with the m3u primary`() {
        // Live-data layout (ES-DE): one folder per disc, .m3u sitting beside them in the parent
        // (D:\Emulators\Roms\psx\Parasite Eve II (USA).m3u next to the (Disc 1)/(Disc 2) folders).
        // The discs' own per-folder keys differ, so only the m3u's cross-folder basename resolution
        // can pull them into one set — the path under verification.
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
        // The discs keep their tag numbers but leave their own folders' sets for the m3u's.
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
        // the m3u's set key; disc 3 just arrived, so its single-pass assign left it as its own
        // per-folder set. Reconcile must re-derive the union and pull disc 3 into the m3u's set.
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
}
