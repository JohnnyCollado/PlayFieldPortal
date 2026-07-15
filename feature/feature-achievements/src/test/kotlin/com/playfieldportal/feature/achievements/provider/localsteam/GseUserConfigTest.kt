package com.playfieldportal.feature.achievements.provider.localsteam

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GseUserConfigTest {

    @Test
    fun `extracts the redirect from the live device config shape`() {
        // Mirrors the file observed on the Thor — note the trailing space after the value.
        val ini = """
            [user::general]
            account_name=WolfStrikerGX
            language=english
            account_steamid=76561197960287930
            [user::saves]
        """.trimIndent() + "\nlocal_save_path=./GSE Saves \n"

        assertEquals("./GSE Saves", GseUserConfig.localSavePath(ini))
    }

    @Test
    fun `missing key or empty value yields null`() {
        assertNull(GseUserConfig.localSavePath(""))
        assertNull(GseUserConfig.localSavePath("[user::general]\naccount_name=x"))
        assertNull(GseUserConfig.localSavePath("local_save_path=   "))
        assertNull(GseUserConfig.localSavePath("x".repeat(GseUserConfig.MAX_BYTES + 1)))
    }

    @Test
    fun `commented lines and lookalike keys are ignored`() {
        val ini = """
            # local_save_path=./commented-out
            ; local_save_path=./also-commented
            not_local_save_path=./wrong
            LOCAL_SAVE_PATH=./case-insensitive
        """.trimIndent()

        assertEquals("./case-insensitive", GseUserConfig.localSavePath(ini))
    }
}
