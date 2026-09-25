package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.data.platform.PlatformFolderHintResolver
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File

/**
 * Folder-shaped games: a PS3 decrypted JB directory named `<title>.ps3dir`.
 *
 * Two things have to hold, and the second is the one that is easy to get wrong. The folder must
 * become exactly one game — and the scanner must NOT walk into it, because its contents are that
 * game's parts and any one of them could otherwise be mistaken for a game of its own.
 */
class DirectoryGameScanTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val arcadeRomsets = mockk<ArcadeRomsetCatalog> {
        every { decide(any(), any(), any()) } returns ArcadeRomsetCatalog.Decision.UseDefault
    }

    private val scanner = RomScanner(
        context = mockk(relaxed = true),
        platformExtensionMap = mockk(relaxed = true),
        discImageResolver = DiscImageResolver(PlatformFolderHintResolver()),
        folderHintResolver = PlatformFolderHintResolver(),
        arcadeRomsets = arcadeRomsets,
        discSetBuilder = DiscSetBuilder(),
        discCompanionSuppressor = DiscCompanionSuppressor(),
        m3uPlaylistReader = mockk(relaxed = true) { every { read(any()) } returns null },
        discRegionReader = mockk(relaxed = true) { every { read(any()) } returns null },
    )

    private val ps3Extensions = listOf("iso", "pkg", "ps3dir")

    private suspend fun scan(
        root: File,
        extensions: List<String> = ps3Extensions,
        recursive: Boolean = true,
        platformId: String = "ps3",
    ): ScanResult.Complete =
        scanner.scanDirectory(root.absolutePath, extensions, platformId, recursive, emptySet())
            .toList()
            .filterIsInstance<ScanResult.Complete>()
            .single()

    /** A decrypted JB game: the folder, and the innards a real one carries. */
    private fun jbFolder(parent: File, name: String): File {
        val dir = File(parent, name).apply { mkdirs() }
        File(dir, "PS3_GAME/USRDIR").mkdirs()
        File(dir, "PS3_GAME/USRDIR/EBOOT.BIN").writeText("eboot")
        File(dir, "PS3_GAME/PARAM.SFO").writeText("sfo")
        return dir
    }

    @Test
    fun `a ps3dir folder becomes one game whose rom path is the folder`() = runTest {
        val root = temp.newFolder("ps3")
        val game = jbFolder(root, "Demon's Souls.ps3dir")

        val complete = scan(root)

        assertEquals(1, complete.newGames.size)
        assertEquals("Demon's Souls", complete.newGames.single().title)
        assertEquals(game.absolutePath, complete.newGames.single().romPath)
        assertEquals("ps3", complete.newGames.single().platformId)
    }

    @Test
    fun `the scanner never walks into a ps3dir folder`() = runTest {
        val root = temp.newFolder("ps3")
        val jb = jbFolder(root, "Demon's Souls.ps3dir")
        // A file that WOULD match if the folder were descended into. Its presence inside a game is
        // ordinary — a JB rip can carry an install image — and it must not become a second row.
        File(jb, "PS3_GAME/extra.iso").writeText("not a game of its own")

        val complete = scan(root)

        assertEquals("the folder is one game, its contents are not", 1, complete.newGames.size)
        assertEquals(jb.absolutePath, complete.newGames.single().romPath)
    }

    @Test
    fun `ordinary folders are still descended into`() = runTest {
        val root = temp.newFolder("ps3")
        val sub = File(root, "Disc Images").apply { mkdirs() }
        File(sub, "Ico.iso").writeText("iso")
        jbFolder(root, "Demon's Souls.ps3dir")

        val complete = scan(root)

        assertEquals(
            setOf("Ico", "Demon's Souls"),
            complete.newGames.mapTo(mutableSetOf()) { it.title },
        )
    }

    @Test
    fun `a top-level ps3dir is found even when the scan is not recursive`() = runTest {
        val root = temp.newFolder("ps3")
        val jb = jbFolder(root, "Demon's Souls.ps3dir")

        val complete = scan(root, recursive = false)

        // "Recursive" governs looking inside ordinary folders. A game is not an ordinary folder,
        // so switching it off must not hide one sitting in plain sight.
        assertEquals(1, complete.newGames.size)
        assertEquals(jb.absolutePath, complete.newGames.single().romPath)
    }

    @Test
    fun `a ps3dir folder is ignored by a platform that does not list the extension`() = runTest {
        val root = temp.newFolder("snes")
        jbFolder(root, "Backups.ps3dir")
        File(root, "Chrono Trigger.sfc").writeText("rom")

        val complete = scan(root, extensions = listOf("sfc", "smc"), platformId = "snes")

        // Membership in DirectoryGameExtensions is not enough on its own — the platform has to
        // list the extension too, or every card would start collecting other platforms' folders.
        assertEquals(listOf("Chrono Trigger"), complete.newGames.map { it.title })
    }

    @Test
    fun `a folder merely named like a file is not a game`() = runTest {
        val root = temp.newFolder("ps3")
        val decoy = File(root, "Old Backups.iso").apply { mkdirs() }
        File(decoy, "Ico.iso").writeText("iso")

        val complete = scan(root)

        // `iso` is in the platform's list but is NOT a directory-game extension, so this folder is
        // walked like any other rather than becoming a game in its own right.
        assertEquals(listOf("Ico"), complete.newGames.map { it.title })
    }

    @Test
    fun `an existing ps3dir game is counted as already in the library, not added twice`() = runTest {
        val root = temp.newFolder("ps3")
        val jb = jbFolder(root, "Demon's Souls.ps3dir")

        val complete = scanner
            .scanDirectory(root.absolutePath, ps3Extensions, "ps3", true, setOf(jb.absolutePath))
            .toList()
            .filterIsInstance<ScanResult.Complete>()
            .single()

        assertTrue(complete.newGames.isEmpty())
        assertEquals(1, complete.alreadyInLibrary)
        // Still reported as present, so the missing-ROM reconciler never flags it as gone.
        assertTrue(jb.absolutePath in complete.presentRomPaths.orEmpty())
    }

    // ── The rule itself ──────────────────────────────────────────────────────

    @Test
    fun `isGameFolder needs both a known directory extension and the platform's blessing`() {
        val ps3 = setOf("iso", "pkg", "ps3dir")

        assertTrue(DirectoryGameExtensions.isGameFolder("Game.ps3dir", ps3))
        assertTrue("extension case never matters", DirectoryGameExtensions.isGameFolder("Game.PS3DIR", ps3))

        // Not a directory-game extension, however allowed it is as a file.
        assertEquals(false, DirectoryGameExtensions.isGameFolder("Game.iso", ps3))
        // A directory-game extension the platform does not list.
        assertEquals(false, DirectoryGameExtensions.isGameFolder("Game.ps3dir", setOf("sfc")))
        // No extension at all.
        assertEquals(false, DirectoryGameExtensions.isGameFolder("Game", ps3))
        assertEquals(false, DirectoryGameExtensions.isGameFolder("ps3dir", ps3))
    }
}
