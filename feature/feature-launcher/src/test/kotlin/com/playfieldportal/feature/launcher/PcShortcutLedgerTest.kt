package com.playfieldportal.feature.launcher

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.datastore.pfpDataStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ledger is what stops the pinned-shortcut sweep from resurrecting a game the user removed:
 * a pin PFP has already turned into a library row stays handled until the host republishes it —
 * which is exactly what pressing "Add to Desktop" again does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PcShortcutLedgerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var ledger: PcShortcutLedger

    @Before
    fun setUp() {
        runBlocking { context.pfpDataStore.edit { it.clear() } }
        ledger = PcShortcutLedger(context)
    }

    @Test
    fun `a pin PFP has never imported is unhandled`() = runTest {
        assertFalse(ledger.isHandled("app.gamenative", "game_1984270", changedAt = 1_000L))
    }

    @Test
    fun `a pin is handled once marked at the same change stamp`() = runTest {
        ledger.markHandled("app.gamenative", "game_1984270", changedAt = 1_000L)

        assertTrue(ledger.isHandled("app.gamenative", "game_1984270", changedAt = 1_000L))
    }

    @Test
    fun `re-pressing Add to Desktop republishes the shortcut, which unhandles it`() = runTest {
        ledger.markHandled("app.gamenative", "game_1984270", changedAt = 1_000L)

        // The host bumps lastChangedTimestamp when it republishes an already-pinned shortcut.
        assertFalse(ledger.isHandled("app.gamenative", "game_1984270", changedAt = 1_001L))
    }

    @Test
    fun `a stale stamp never unhandles an already-imported pin`() = runTest {
        ledger.markHandled("app.gamenative", "game_1984270", changedAt = 2_000L)

        assertTrue(ledger.isHandled("app.gamenative", "game_1984270", changedAt = 1_000L))
    }

    @Test
    fun `a later mark supersedes the earlier one`() = runTest {
        ledger.markHandled("app.gamenative", "game_1984270", changedAt = 1_000L)
        ledger.markHandled("app.gamenative", "game_1984270", changedAt = 2_000L)

        assertTrue(ledger.isHandled("app.gamenative", "game_1984270", changedAt = 2_000L))
        assertFalse(ledger.isHandled("app.gamenative", "game_1984270", changedAt = 2_001L))
    }

    @Test
    fun `marks are scoped to one host and one shortcut id`() = runTest {
        ledger.markHandled("app.gamenative", "game_1984270", changedAt = 1_000L)

        assertFalse(ledger.isHandled("com.winlator", "game_1984270", changedAt = 1_000L))
        assertFalse(ledger.isHandled("app.gamenative", "game_42", changedAt = 1_000L))
    }

    @Test
    fun `hosts and shortcut ids cannot collide into one mark`() = runTest {
        // Shortcut ids are arbitrary host-chosen strings (live case: "NieR:Automata™").
        ledger.markHandled("com.xiaoji.egggame", "a:b", changedAt = 1_000L)

        assertFalse(ledger.isHandled("com.xiaoji.egggame:a", "b", changedAt = 1_000L))
    }
}
