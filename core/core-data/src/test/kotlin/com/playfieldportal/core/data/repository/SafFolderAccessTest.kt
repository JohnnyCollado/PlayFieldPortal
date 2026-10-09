package com.playfieldportal.core.data.repository

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "Permission lost" must mean the grant is gone, not that the folder could not be read a moment
 * ago. Right after a reboot an SD card's folder is unreadable until the volume mounts while its
 * persisted grant is still held; that is [FolderAccess.NOT_READY], which nothing reports.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SafFolderAccessTest {

    private val root = "content://com.android.externalstorage.documents/tree/1234-5678%3AMusic"
    private val sub = "content://com.android.externalstorage.documents/tree/1234-5678%3AMusic/document/1234-5678%3AMusic%2FAlbums"

    @Test
    fun `a held grant on a readable folder is readable`() {
        assertEquals(FolderAccess.READABLE, SafGrants.access(root, setOf(root), rootReadable = true))
    }

    @Test
    fun `a held grant on an unreadable folder is not ready, never lost`() {
        assertEquals(FolderAccess.NOT_READY, SafGrants.access(root, setOf(root), rootReadable = false))
    }

    @Test
    fun `no grant is lost, readable or not`() {
        assertEquals(FolderAccess.NO_GRANT, SafGrants.access(root, emptySet(), rootReadable = false))
        assertEquals(FolderAccess.NO_GRANT, SafGrants.access(root, setOf("content://other/tree/x"), rootReadable = true))
    }

    @Test
    fun `a subfolder rides its root's grant`() {
        assertEquals(FolderAccess.NOT_READY, SafGrants.access(sub, setOf(root), rootReadable = false))
        assertEquals(FolderAccess.NO_GRANT, SafGrants.access(sub, emptySet(), rootReadable = false))
    }

    @Test
    fun `an unparseable uri is lost`() {
        assertEquals(FolderAccess.NO_GRANT, SafGrants.access("not a uri", setOf("not a uri"), rootReadable = false))
    }
}
