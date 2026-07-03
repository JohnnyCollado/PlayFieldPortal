package com.playfieldportal.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

// The pure link-status rule that drives every Root Access section (SafGrants).
class FolderAccessStatusTest {

    private val romRoot = "content://com.android.externalstorage.documents/tree/primary%3ARoms"
    private val music   = "content://com.android.externalstorage.documents/tree/primary%3AMusic"

    @Test
    fun `stored uri with a live persisted grant is LINKED`() {
        assertEquals(FolderLinkStatus.LINKED, SafGrants.linkStatus(romRoot, setOf(romRoot, music)))
    }

    @Test
    fun `stored uri missing from persisted grants is ACCESS_LOST`() {
        assertEquals(FolderLinkStatus.ACCESS_LOST, SafGrants.linkStatus(romRoot, setOf(music)))
    }

    @Test
    fun `no persisted grants at all - as after a restore - is ACCESS_LOST`() {
        assertEquals(FolderLinkStatus.ACCESS_LOST, SafGrants.linkStatus(romRoot, emptySet()))
    }
}
