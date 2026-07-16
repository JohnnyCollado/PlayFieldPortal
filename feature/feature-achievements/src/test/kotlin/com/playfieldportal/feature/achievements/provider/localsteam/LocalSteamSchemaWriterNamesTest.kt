package com.playfieldportal.feature.achievements.provider.localsteam

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the XOR-obfuscated emu file names to their real values. Test sources never ship in the
 * app APK, so asserting the plaintext here is safe and guards against a bad edit to the encoded
 * byte arrays in [LocalSteamSchemaWriter].
 */
class LocalSteamSchemaWriterNamesTest {

    @Test
    fun `obfuscated names decode to the emulator file names`() {
        assertEquals("steam_api64.dll", LocalSteamSchemaWriter.EMU_DLL)
        assertEquals("steam_api64_o.dll", LocalSteamSchemaWriter.EMU_BACKUP_DLL)
    }
}
