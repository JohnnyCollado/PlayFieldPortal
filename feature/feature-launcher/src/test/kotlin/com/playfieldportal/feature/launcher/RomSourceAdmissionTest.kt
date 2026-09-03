package com.playfieldportal.feature.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The FileProvider can mint a URI for any file under `/storage/`, but a launch only ever needs a
 * file belonging to a configured ROM source. Nothing enforced the narrower rule, and the grantee
 * package is chosen by an emulator profile — which a restored backup can supply. This is the check
 * that makes the provider's reach irrelevant.
 */
class RomSourceAdmissionTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `a rom directly inside a configured source is admitted`() {
        val roms = temp.newFolder("roms")
        val game = File(roms, "game.iso").apply { writeText("x") }

        assertTrue(RomSourceAdmission.isAdmissible(game.path, listOf(roms.path)))
    }

    @Test
    fun `a rom nested under a configured source is admitted`() {
        val roms = temp.newFolder("roms")
        val nested = File(roms, "psx/disc1").apply { mkdirs() }
        val game = File(nested, "game.bin").apply { writeText("x") }

        assertTrue(RomSourceAdmission.isAdmissible(game.path, listOf(roms.path)))
    }

    @Test
    fun `a file outside every configured source is refused`() {
        val roms = temp.newFolder("roms")
        val elsewhere = File(temp.newFolder("other"), "secret.txt").apply { writeText("x") }

        assertFalse(RomSourceAdmission.isAdmissible(elsewhere.path, listOf(roms.path)))
    }

    @Test
    fun `another app's external data directory is refused`() {
        // The concrete shape the repo's own test already imagines:
        // content://.../storage_volumes/emulated/0/secret
        val roms = temp.newFolder("roms")

        assertFalse(
            RomSourceAdmission.isAdmissible(
                "/storage/emulated/0/Android/data/com.other.app/files/token.txt",
                listOf(roms.path),
            ),
        )
    }

    @Test
    fun `a traversal out of a configured source is refused`() {
        val roms = temp.newFolder("roms")
        val outside = File(temp.newFolder("other"), "secret.txt").apply { writeText("x") }
        val traversal = File(roms, "../other/secret.txt").path

        assertFalse(RomSourceAdmission.isAdmissible(traversal, listOf(roms.path)))
        assertFalse(RomSourceAdmission.isAdmissible(outside.path, listOf(roms.path)))
    }

    @Test
    fun `a sibling directory sharing a name prefix is refused`() {
        val roms = temp.newFolder("roms")
        val evil = temp.newFolder("roms_evil")
        val game = File(evil, "game.iso").apply { writeText("x") }

        assertFalse(RomSourceAdmission.isAdmissible(game.path, listOf(roms.path)))
    }

    @Test
    fun `the source directory itself is not a rom`() {
        val roms = temp.newFolder("roms")

        assertFalse(RomSourceAdmission.isAdmissible(roms.path, listOf(roms.path)))
    }

    @Test
    fun `no configured sources means nothing is admissible`() {
        val game = File(temp.newFolder("roms"), "game.iso").apply { writeText("x") }

        assertFalse(RomSourceAdmission.isAdmissible(game.path, emptyList()))
    }

    @Test
    fun `blank paths are refused rather than treated as the root`() {
        val roms = temp.newFolder("roms")

        assertFalse(RomSourceAdmission.isAdmissible("", listOf(roms.path)))
        assertFalse(RomSourceAdmission.isAdmissible("   ", listOf(roms.path)))
        // A blank *source* must not admit everything either.
        assertFalse(RomSourceAdmission.isAdmissible(File(roms, "g.iso").path, listOf("", "  ")))
    }

    @Test
    fun `any one matching source is enough`() {
        val a = temp.newFolder("cardA")
        val b = temp.newFolder("cardB")
        val game = File(b, "game.iso").apply { writeText("x") }

        assertTrue(RomSourceAdmission.isAdmissible(game.path, listOf(a.path, b.path)))
    }
}
