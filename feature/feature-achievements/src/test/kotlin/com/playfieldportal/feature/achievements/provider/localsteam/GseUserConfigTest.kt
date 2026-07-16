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
    fun `savePathSegments walks relative redirects only`() {
        assertEquals(listOf("GSE Saves"), GseUserConfig.savePathSegments("./GSE Saves "))
        assertEquals(listOf("saves", "gse"), GseUserConfig.savePathSegments(".\\saves\\gse"))
        assertEquals(listOf("saves"), GseUserConfig.savePathSegments("saves/"))

        // Absolute or escaping paths point outside the granted tree: unreachable by design.
        assertNull(GseUserConfig.savePathSegments("C:\\Users\\me\\saves"))
        assertNull(GseUserConfig.savePathSegments("/absolute/path"))
        assertNull(GseUserConfig.savePathSegments("../outside"))
        assertNull(GseUserConfig.savePathSegments("."))
    }

    @Test
    fun `the generated redirect ini round-trips through the reader`() {
        // The writer's template and the reader must stay compatible: what the generator writes,
        // discovery must resolve to the saves folder beside the DLL.
        val ini = GseUserConfig.savesRedirectIni()

        val redirect = GseUserConfig.localSavePath(ini)
        assertEquals("./saves", redirect)
        assertEquals(listOf("saves"), GseUserConfig.savePathSegments(redirect!!))
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
