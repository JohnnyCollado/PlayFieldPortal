package com.playfieldportal.core.data.database.seeder

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.datastore.pfpDataStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class StartupDataPrepTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val gameDao = mockk<GameDao>().also { coEvery { it.getAll() } returns emptyList() }
    private val prep = StartupDataPrep(context, gameDao)

    private val retiredMode = stringPreferencesKey("artwork_storage_mode")
    private val libraryUuid = stringPreferencesKey("artwork_library_uuid")
    private val treeUri = stringPreferencesKey("artwork_folder_tree_uri")

    @Test
    fun `a stored artwork storage mode is removed and other prefs are untouched`() = runTest {
        context.pfpDataStore.edit {
            it[retiredMode] = "portable"
            it[libraryUuid] = "uuid-1"
            it[treeUri] = "content://tree/primary%3AArt"
        }

        prep.run(7)

        val prefs = context.pfpDataStore.data.first()
        assertNull(prefs[retiredMode])
        assertEquals("uuid-1", prefs[libraryUuid])
        assertEquals("content://tree/primary%3AArt", prefs[treeUri])
    }

    @Test
    fun `running the cleanup again on an already prepped version stays harmless`() = runTest {
        prep.run(8)
        prep.run(8)

        assertNull(context.pfpDataStore.data.first()[retiredMode])
    }
}
