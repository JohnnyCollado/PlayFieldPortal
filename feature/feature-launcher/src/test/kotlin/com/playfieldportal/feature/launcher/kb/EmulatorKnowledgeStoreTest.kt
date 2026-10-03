package com.playfieldportal.feature.launcher.kb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbDecode
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbDecoder
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbDocument
import com.playfieldportal.core.domain.model.emulatorkb.KbSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The layer files, their atomic writes, and the effective KB they produce. */
@RunWith(RobolectricTestRunner::class)
class EmulatorKnowledgeStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.filesDir, "emulator_kb")
    private val officialFile get() = File(root, "official/emulators.json")

    @Before
    fun clean() {
        root.deleteRecursively()
    }

    private fun newStore() = EmulatorKnowledgeStore(context, Dispatchers.Unconfined)

    private fun emulatorJson(id: String, pkg: String, name: String = "Emu $id") = """
        {"id":"$id","name":"$name","packageNames":["$pkg"],"platformIds":["psx"],
         "launch":{"intentType":"ACTION_VIEW","action":"android.intent.action.VIEW"}}
    """.trimIndent()

    private fun kbText(version: Long, vararg emulators: String) = """
        {"format":"pfp-emulator-kb","schemaVersion":1,"version":$version,"label":"t",
         "emulators":[${emulators.joinToString(",")}],"platforms":[]}
    """.trimIndent()

    private fun decode(text: String): EmulatorKbDocument =
        (EmulatorKbDecoder.decode(text) as EmulatorKbDecode.Decoded).document

    private fun installOfficial(store: EmulatorKnowledgeStore, text: String): Boolean =
        runBlocking { store.installOfficial(text.toByteArray(), decode(text)) }

    private fun reloaded(): EmulatorKnowledgeStore = newStore().also { runBlocking { it.current() } }

    @Test
    fun `fresh install is the built-in layer`() = runBlocking {
        val effective = newStore().current()
        assertTrue(effective.emulators.isNotEmpty())
        assertTrue(effective.emulators.all { it.source == KbSource.BuiltIn && it.replaced == null })
        assertFalse(effective.officialApplied)
    }

    @Test
    fun `effective flow publishes the loaded state`() = runBlocking {
        val store = newStore()
        val loaded = store.current()
        assertEquals(loaded, store.effective.value)
    }

    @Test
    fun `a valid official file overrides the built-in layer`() = runBlocking {
        val store = newStore()
        val builtIn = store.current().emulators.first().emulator
        val text = kbText(2, emulatorJson(builtIn.id, "com.example.official", "Official Name"))
        assertTrue(installOfficial(store, text))

        val entry = store.current().emulators.single { it.emulator.id == builtIn.id }
        assertEquals("Official Name", entry.emulator.name)
        assertEquals(KbSource.Official, entry.source)
        assertEquals(KbSource.BuiltIn, entry.replaced)
        assertTrue(store.current().officialApplied)
        // Survives a restart.
        assertTrue(newStore().current().officialApplied)
    }

    @Test
    fun `an official file with a refused entry is not installed`() = runBlocking {
        val store = newStore()
        val text = kbText(2, emulatorJson("badone", "com.google.android.evil"))
        assertFalse(installOfficial(store, text))
        assertFalse(officialFile.exists())
        assertFalse(store.current().officialApplied)
    }

    @Test
    fun `a tampered official file is ignored`() = runBlocking {
        installOfficial(newStore(), kbText(2, emulatorJson("goodone", "com.example.good")))
        assertTrue(officialFile.exists())
        // Tampered on disk: now carries a package the validator refuses.
        officialFile.writeText(
            kbText(2, emulatorJson("goodone", "com.example.good"), emulatorJson("evil", "android.system")),
        )

        val effective = newStore().current()
        assertFalse(effective.officialApplied)
        assertTrue(effective.emulators.none { it.emulator.id == "goodone" })
    }

    @Test
    fun `a corrupt official file is ignored`() = runBlocking {
        officialFile.parentFile!!.mkdirs()
        officialFile.writeText("{ not json")
        assertFalse(newStore().current().officialApplied)
    }

    @Test
    fun `a user file survives a new store instance`() = runBlocking {
        val store = newStore()
        val id = store.addUserFile("mine.json", decode(kbText(0, emulatorJson("mine", "com.example.mine"))))

        val after = reloaded()
        val entry = after.current().emulators.single { it.emulator.id == "mine" }
        assertEquals(KbSource.User(id), entry.source)
        val info = after.userFiles.value.single()
        assertEquals(id, info.id)
        assertEquals("mine.json", info.displayName)
        assertFalse(info.unreadable)
        assertEquals(1, info.emulatorCount)
    }

    @Test
    fun `user files are applied in import order`() = runBlocking {
        val store = newStore()
        store.addUserFile("a", decode(kbText(0, emulatorJson("shared", "com.example.a", "First"))))
        val second = store.addUserFile("b", decode(kbText(0, emulatorJson("shared", "com.example.b", "Second"))))

        val entry = newStore().current().emulators.single { it.emulator.id == "shared" }
        assertEquals("Second", entry.emulator.name)
        assertEquals(KbSource.User(second), entry.source)
    }

    @Test
    fun `refused entries in a user file are dropped and the rest stay`() = runBlocking {
        val store = newStore()
        store.addUserFile(
            "mixed",
            decode(kbText(0, emulatorJson("okone", "com.example.ok"), emulatorJson("badone", "android.sys"))),
        )
        val ids = newStore().current().emulators.map { it.emulator.id }
        assertTrue("okone" in ids)
        assertFalse("badone" in ids)
    }

    @Test
    fun `a corrupt user file is skipped and its index entry is kept as unreadable`() = runBlocking {
        val store = newStore()
        val id = store.addUserFile("mine", decode(kbText(0, emulatorJson("mine", "com.example.mine"))))
        File(root, "user/$id.json").writeText("garbage")

        val after = reloaded()
        assertTrue(after.current().emulators.none { it.emulator.id == "mine" })
        val info = after.userFiles.value.single()
        assertEquals(id, info.id)
        assertTrue(info.unreadable)
    }

    @Test
    fun `a missing user file is unreadable`() = runBlocking {
        val store = newStore()
        val id = store.addUserFile("mine", decode(kbText(0, emulatorJson("mine", "com.example.mine"))))
        File(root, "user/$id.json").delete()
        assertTrue(reloaded().userFiles.value.single().unreadable)
    }

    @Test
    fun `removing a user file deletes it`() = runBlocking {
        val store = newStore()
        val id = store.addUserFile("mine", decode(kbText(0, emulatorJson("mine", "com.example.mine"))))
        assertTrue(File(root, "user/$id.json").exists())

        assertTrue(store.removeUserFile(id))
        assertFalse(File(root, "user/$id.json").exists())
        assertTrue(store.current().emulators.none { it.emulator.id == "mine" })
        assertTrue(store.userFiles.value.isEmpty())
        assertTrue(reloaded().userFiles.value.isEmpty())
        assertFalse(store.removeUserFile("nope"))
    }

    @Test
    fun `reset deletes the official file and every user file`() = runBlocking {
        val store = newStore()
        installOfficial(store, kbText(2, emulatorJson("goodone", "com.example.good")))
        val id = store.addUserFile("mine", decode(kbText(0, emulatorJson("mine", "com.example.mine"))))

        store.resetToBuiltIn()
        assertFalse(officialFile.exists())
        assertFalse(File(root, "user/$id.json").exists())
        val effective = store.current()
        assertFalse(effective.officialApplied)
        assertTrue(effective.emulators.all { it.source == KbSource.BuiltIn })
        assertTrue(newStore().current().emulators.all { it.source == KbSource.BuiltIn })
    }

    @Test
    fun `a crash mid-write leaves the previous official file intact`() = runBlocking {
        installOfficial(newStore(), kbText(2, emulatorJson("goodone", "com.example.good", "Good")))
        // The crash: a half-written temp file next to the real one.
        val temp = File(officialFile.parentFile, "emulators.json.tmp")
        temp.writeText("{\"format\":\"pfp-emu")

        val store = newStore()
        assertTrue(store.current().emulators.any { it.emulator.id == "goodone" })
        // The next write recovers over the stale temp file.
        assertTrue(installOfficial(store, kbText(3, emulatorJson("goodone", "com.example.good", "Better"))))
        assertEquals("Better", newStore().current().emulators.single { it.emulator.id == "goodone" }.emulator.name)
        assertFalse(temp.exists())
    }

    @Test
    fun `a stale temp file next to a user file does not disturb it`() = runBlocking {
        val store = newStore()
        val id = store.addUserFile("mine", decode(kbText(0, emulatorJson("mine", "com.example.mine"))))
        File(root, "user/$id.json.tmp").writeText("garbage")
        val after = reloaded()
        assertTrue(after.current().emulators.any { it.emulator.id == "mine" })
        assertEquals(1, after.userFiles.value.size)
    }

    @Test
    fun `a 33rd user file is refused`() = runBlocking {
        val store = newStore()
        repeat(32) { store.addUserFile("f$it", decode(kbText(0, emulatorJson("e$it", "com.example.e$it")))) }

        val failure = runCatching { store.addUserFile("extra", decode(kbText(0, emulatorJson("x", "com.example.x")))) }

        assertTrue(failure.exceptionOrNull() is java.io.IOException)
        assertEquals(32, store.userFiles.value.size)
    }

    @Test
    fun `a stored user file carries the current schema version`() = runBlocking {
        val id = newStore().addUserFile("mine", decode(kbText(0, emulatorJson("mine", "com.example.mine"))))

        val text = File(root, "user/$id.json").readText()

        assertTrue(text.contains("\"schemaVersion\":${EmulatorKbDecoder.SCHEMA_VERSION}"))
    }
}
