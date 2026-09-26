package com.playfieldportal.feature.achievements.provider.localsteam

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.database.dao.LocalSteamFolderDao
import com.playfieldportal.core.data.repository.WindowsLibrarySetup
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Scoped discovery over a fake SAF tree: one PICKED folder rather than a library-wide walk.
 *
 * The fixtures are the shapes real installs actually come in — the Unity nesting that puts
 * `steam_settings` four levels down, the emu's own `local_save_path` redirect, the documented
 * `saves/` fallback, a Steam build with a DLL but no settings folder, and a folder that is not a
 * Steam build at all. Each of those leads somewhere different on screen, so each is pinned.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LocalSteamDiscoveryScopedTest {

    private val context = mockk<Context>(relaxed = true)
    private val resolver = mockk<ContentResolver>(relaxed = true)
    private val windowsLibrary = mockk<WindowsLibrarySetup>(relaxed = true)
    private val credentials = mockk<AchievementCredentialsProvider>()
    private val registry = mockk<LocalSteamFolderDao>(relaxed = true)
    private val discovery = LocalSteamDiscovery(context, windowsLibrary, credentials, registry)

    // A real SAF tree uri shape — DocumentsContract only accepts `<authority>/tree/<id>` paths.
    private val tree = "content://com.android.externalstorage.documents/tree/primary%3AGames"
    private val root = "primary:Games"

    /** A fixture path as a document id under [root]. */
    private fun d(relative: String): String = if (relative.isEmpty()) root else "$root/$relative"

    /** docId -> its children, as `name to isDirectory`. The whole fake filesystem. */
    private val dirs = mutableMapOf<String, List<Pair<String, Boolean>>>()

    /** docId -> file contents, for the few small files discovery reads. */
    private val files = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        every { context.contentResolver } returns resolver
        coEvery { credentials.localSteamTrackingEnabled() } returns true
        coEvery { credentials.goldbergInstallerEnabled() } returns false

        val uriSlot = slot<Uri>()
        every { resolver.query(capture(uriSlot), any(), any(), any(), any()) } answers {
            cursorFor(uriSlot.captured)
        }
        every { resolver.openInputStream(any()) } answers {
            val docId = DocumentsContract.getDocumentId(firstArg())
            files[docId]?.let { ByteArrayInputStream(it.toByteArray()) }
        }
    }

    /**
     * Answers both query shapes discovery uses: a children listing, and the single-row display-name
     * read that names a picked folder.
     */
    private fun cursorFor(uri: Uri): Cursor? {
        val docId = DocumentsContract.getDocumentId(uri)
        val isChildrenQuery = uri.toString().endsWith("/children")
        if (!isChildrenQuery) {
            return MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)).apply {
                addRow(arrayOf<Any?>(docId.substringAfterLast('/')))
            }
        }
        val children = dirs[docId] ?: return null
        return MatrixCursor(
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
            )
        ).apply {
            children.forEach { (name, isDir) ->
                addRow(
                    arrayOf<Any?>(
                        "$docId/$name",
                        name,
                        if (isDir) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream",
                        null,
                        files["$docId/$name"]?.length?.toLong() ?: 4L,
                    )
                )
            }
        }
    }

    private fun dir(relative: String, vararg children: Pair<String, Boolean>) {
        dirs[d(relative)] = children.toList()
    }

    private fun file(relative: String, content: String) {
        files[d(relative)] = content
    }

    // A plain install: steam_settings directly under the game folder, with a saves/<appid> tree.
    private fun plainInstall(name: String = "Portal 2", appId: String = "620") {
        dir("$name", "steam_settings" to true, "saves" to true, "portal2.exe" to false)
        dir("$name/steam_settings", "steam_appid.txt" to false)
        file("$name/steam_settings/steam_appid.txt", appId)
        dir("$name/saves", appId to true)
        dir("$name/saves/$appId", "achievements.json" to false)
        file("$name/saves/$appId/achievements.json", "{}")
    }

    @Test
    fun `a picked folder is inspected and its saves-convention progress file resolved`() = runTest {
        plainInstall()

        val game = discovery.inspect(tree, d("Portal 2"))

        assertNotNull(game)
        assertEquals("620", game.appId)
        assertEquals("Portal 2", game.folderName)
        assertEquals(d("Portal 2/steam_settings"), game.settingsDirDocId)
        // The DLL folder — the save-redirect base and the kit's write target's parent.
        assertEquals(d("Portal 2"), game.settingsParentDocId)
        assertNotNull(game.achievementsUri)
        assertTrue(game.trackable)
    }

    @Test
    fun `steam_settings nested four levels down is still found — the Unity shape`() = runTest {
        dir("FF Pixel Remaster", "FF_Data" to true)
        dir("FF Pixel Remaster/FF_Data", "Plugins" to true)
        dir("FF Pixel Remaster/FF_Data/Plugins", "x86_64" to true)
        dir("FF Pixel Remaster/FF_Data/Plugins/x86_64", "steam_settings" to true, "saves" to true)
        dir("FF Pixel Remaster/FF_Data/Plugins/x86_64/steam_settings", "steam_appid.txt" to false)
        file("FF Pixel Remaster/FF_Data/Plugins/x86_64/steam_settings/steam_appid.txt", "1173820")
        dir("FF Pixel Remaster/FF_Data/Plugins/x86_64/saves")

        val game = discovery.inspect(tree, d("FF Pixel Remaster"))

        assertNotNull(game)
        assertEquals("1173820", game.appId)
        // The anchor is the folder holding steam_settings, NOT the game folder — that is where the
        // DLL is and where the redirect resolves from.
        assertEquals(d("FF Pixel Remaster/FF_Data/Plugins/x86_64"), game.settingsParentDocId)
        assertTrue(game.trackable, "the saves folder exists, so the game is trackable at 0%")
    }

    @Test
    fun `the emu's own local_save_path redirect wins over the saves convention`() = runTest {
        dir("Hades", "steam_settings" to true, "GSE Saves" to true, "saves" to true)
        dir("Hades/steam_settings", "steam_appid.txt" to false, "configs.user.ini" to false)
        file("Hades/steam_settings/steam_appid.txt", "1145360")
        file("Hades/steam_settings/configs.user.ini", "[user::saves]\nlocal_save_path=./GSE Saves\n")
        dir("Hades/GSE Saves", "1145360" to true)
        dir("Hades/GSE Saves/1145360", "achievements.json" to false)
        file("Hades/GSE Saves/1145360/achievements.json", "{}")
        // A decoy: the convention folder exists too, and must NOT be the one that is read.
        dir("Hades/saves", "1145360" to true)
        dir("Hades/saves/1145360", "achievements.json" to false)
        file("Hades/saves/1145360/achievements.json", "{\"decoy\":true}")

        val game = discovery.inspect(tree, d("Hades"))

        assertNotNull(game)
        assertTrue(
            DocumentsContract.getDocumentId(game.achievementsUri!!).contains("GSE Saves"),
            "the emu's configured path is the source of truth, not the fallback convention",
        )
    }

    @Test
    fun `a folder with a DLL but no steam_settings anchors on the DLL folder`() = runTest {
        dir("Unmarked Game", "bin" to true)
        dir("Unmarked Game/bin", "steam_api64.dll" to false, "game.exe" to false)

        // No marker anywhere, so there is nothing to inspect...
        assertNull(discovery.inspect(tree, d("Unmarked Game")))
        // ...but it IS a Steam build, and the DLL folder is where a kit would be written.
        val anchor = discovery.anchor(tree, d("Unmarked Game"))
        assertNotNull(anchor)
        assertEquals(d("Unmarked Game/bin"), anchor.settingsParentDocId)
        assertNull(anchor.settingsDirDocId)
        assertNull(anchor.markerAppId)
    }

    @Test
    fun `a 32-bit only game still identifies as a Steam build`() = runTest {
        dir("Old Game", "steam_api.dll" to false, "game.exe" to false)

        val anchor = discovery.anchor(tree, d("Old Game"))

        // The emu swap will report NoTargetDll for it, but saying "not a Steam build" would be wrong.
        assertNotNull(anchor)
        assertEquals(d("Old Game"), anchor.settingsParentDocId)
    }

    @Test
    fun `a folder with neither a marker nor a DLL is not a Steam build`() = runTest {
        dir("Notepad", "notepad.exe" to false, "readme.txt" to false)

        assertNull(discovery.inspect(tree, d("Notepad")))
        assertNull(discovery.anchor(tree, d("Notepad")))
    }

    @Test
    fun `a marker present with no DLL still anchors — the folder's own word is authoritative`() = runTest {
        plainInstall(name = "Portable Game", appId = "440")

        val anchor = discovery.anchor(tree, d("Portable Game"))

        assertNotNull(anchor)
        assertEquals("440", anchor.markerAppId)
        assertEquals(d("Portable Game/steam_settings"), anchor.settingsDirDocId)
    }

    @Test
    fun `a non-numeric marker is ignored rather than trusted`() = runTest {
        dir("Broken", "steam_settings" to true, "saves" to true)
        dir("Broken/steam_settings", "steam_appid.txt" to false)
        file("Broken/steam_settings/steam_appid.txt", "not-an-appid")
        dir("Broken/saves")

        assertNull(discovery.inspect(tree, d("Broken")))
        // The anchor still exists (steam_settings is there), it simply declares no usable id.
        assertNull(discovery.anchor(tree, d("Broken"))!!.markerAppId)
    }

    @Test
    fun `scanFolder inspects every child of a picked parent and skips the ones that do not qualify`() = runTest {
        dir("", "Portal 2" to true, "Notepad" to true, "import" to true)
        plainInstall()
        dir("Notepad", "notepad.exe" to false)
        // The export drop-folder is never a game folder.
        dir("import", "somegame.steam" to false)

        val games = discovery.scanFolder(tree, root)

        assertEquals(listOf("Portal 2"), games.map { it.folderName })
    }

    @Test
    fun `an unmarked folder with a marker written into it inspects on the next read`() = runTest {
        plainInstall(name = "Late Marker", appId = "620")

        assertEquals("620", discovery.inspect(tree, d("Late Marker"))?.appId)

        // The registry read goes through the same inspect, so registering then looking up by app id
        // must agree with what a direct inspect says.
        coEvery { registry.getByAppId("620") } returns
            com.playfieldportal.core.data.database.entity.LocalSteamFolderEntity(
                appId = "620",
                folderName = "Late Marker",
                treeUri = tree,
                folderDocId = d("Late Marker"),
                settingsParentDocId = d("Late Marker"),
                appIdSource = "MARKER",
                lastSeenAt = 1L,
            )

        val found = discovery.findByAppId("620")
        assertNotNull(found)
        assertEquals("Late Marker", found.folderName)
        // A registered folder is trackable by definition — the user pointed at it.
        assertTrue(found.trackable)
    }

    @Test
    fun `a registered folder resolves even with no marker — the row IS the identity`() = runTest {
        // The regression: re-deriving the app id from steam_appid.txt on every sync made a
        // registered game resolvable only while that file existed. A folder identified by TITLE while
        // the marker write was not permitted (tracking off) linked once and then failed every later
        // sync with "no emu game folder for appid ...".
        dir("Unmarked FF", "FF_Data" to true)
        dir("Unmarked FF/FF_Data", "steam_settings" to true, "saves" to true, "steam_api64.dll" to false)
        // steam_settings exists with the kit, but carries NO steam_appid.txt.
        dir("Unmarked FF/FF_Data/steam_settings", "achievements.json" to false)
        file("Unmarked FF/FF_Data/steam_settings/achievements.json", "[]")
        dir("Unmarked FF/FF_Data/saves", "1173810" to true)
        dir("Unmarked FF/FF_Data/saves/1173810", "achievements.json" to false)
        file("Unmarked FF/FF_Data/saves/1173810/achievements.json", "{}")

        // A pick-time inspection finds nothing, because there is no id written down...
        assertNull(discovery.inspect(tree, d("Unmarked FF")))

        // ...but the registry recorded the id when it was established, so the sync still resolves.
        coEvery { registry.getByAppId("1173810") } returns
            com.playfieldportal.core.data.database.entity.LocalSteamFolderEntity(
                appId = "1173810",
                folderName = "Unmarked FF",
                treeUri = tree,
                folderDocId = d("Unmarked FF"),
                settingsDirDocId = d("Unmarked FF/FF_Data/steam_settings"),
                settingsParentDocId = d("Unmarked FF/FF_Data"),
                hasSchema = true,
                appIdSource = "TITLE_MATCH",
                lastSeenAt = 1L,
            )

        val found = discovery.findByAppId("1173810")
        assertNotNull(found)
        assertEquals("1173810", found.appId)
        assertEquals("Unmarked FF", found.folderName)
        assertTrue(found.hasSchema, "the kit is there, re-read from disk")
        assertNotNull(found.achievementsUri, "and so is the progress file")
        assertTrue(found.trackable)
    }

    @Test
    fun `a registered folder's progress file is re-resolved on every read`() = runTest {
        // Played for the first time since the folder was registered: the row recorded no progress
        // file, and the sync has to notice the one that exists now.
        dir("Portal 2", "steam_settings" to true, "saves" to true)
        dir("Portal 2/steam_settings", "steam_appid.txt" to false, "achievements.json" to false)
        file("Portal 2/steam_settings/steam_appid.txt", "620")
        file("Portal 2/steam_settings/achievements.json", "[]")
        dir("Portal 2/saves", "620" to true)
        dir("Portal 2/saves/620", "achievements.json" to false)
        file("Portal 2/saves/620/achievements.json", "{}")

        coEvery { registry.getByAppId("620") } returns
            com.playfieldportal.core.data.database.entity.LocalSteamFolderEntity(
                appId = "620",
                folderName = "Portal 2",
                treeUri = tree,
                folderDocId = d("Portal 2"),
                settingsDirDocId = d("Portal 2/steam_settings"),
                settingsParentDocId = d("Portal 2"),
                progressDocId = null,   // nothing had been played when it was registered
                hasSchema = true,
                appIdSource = "MARKER",
                lastSeenAt = 1L,
            )

        assertNotNull(discovery.findByAppId("620")?.achievementsUri)
    }

    @Test
    fun `a registered folder that cannot be read is unknown, and its row is left alone`() = runTest {
        coEvery { registry.getByAppId("620") } returns
            com.playfieldportal.core.data.database.entity.LocalSteamFolderEntity(
                appId = "620",
                folderName = "Moved Game",
                treeUri = tree,
                folderDocId = d("Moved Game"),
                settingsParentDocId = d("Moved Game"),
                appIdSource = "MARKER",
                lastSeenAt = 1L,
            )
        // Nothing is registered in the fake tree under that doc id, so the read fails.

        // Null is UNKNOWN. The sync layer must never read it as "nothing earned", and the row stays
        // so the user can re-pick instead of starting over.
        assertNull(discovery.findByAppId("620"))
        io.mockk.coVerify(exactly = 0) { registry.deleteByAppId(any()) }
    }
}
