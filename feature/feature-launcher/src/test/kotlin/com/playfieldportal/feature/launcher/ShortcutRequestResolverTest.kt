package com.playfieldportal.feature.launcher

import com.playfieldportal.core.data.repository.CollectionRepository
import com.playfieldportal.core.data.repository.PendingShortcutRequest
import com.playfieldportal.core.data.repository.PendingShortcutRequestStore
import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationKind
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.core.ui.notification.BackgroundTaskCenter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The shortcut confirmation that used to live in the shade's Add / Ignore buttons: a request waits
 * in the store with an unread tray row, and the user's choice in the launcher resolves both.
 */
class ShortcutRequestResolverTest {

    private val store = mockk<PendingShortcutRequestStore>(relaxed = true)
    private val games = mockk<GameRepository>(relaxed = true)
    private val collections = mockk<CollectionRepository>(relaxed = true)
    private val importer = mockk<PcShortcutImporter>(relaxed = true)
    private val tasks = mockk<BackgroundTaskCenter>(relaxed = true)
    private val resolver = ShortcutRequestResolver(store, games, collections, importer, tasks)

    private val request = PendingShortcutRequest(
        id = "1a2b", name = "Gmail", intentUri = "intent:#Intent;end", hostPackage = "com.android.chrome",
        hostLabel = "Chrome", requestedAt = 0,
    )

    @Test
    fun `a request waits in the store with an unread row that opens the review`() = runTest {
        resolver.enqueue(request)

        coVerify { store.enqueue(request) }
        verify {
            tasks.report(
                "shortcut:1a2b", "Shortcut request: Gmail (opens Chrome)", any(), NotificationSeverity.INFO,
                NotificationKind.SYSTEM, NotificationAction.ReviewShortcut("1a2b"), any(), false,
            )
        }
    }

    @Test
    fun `ignore drops the request and settles its row as read`() = runTest {
        coEvery { store.get("1a2b") } returns request
        resolver.ignore("1a2b")

        coVerify { store.remove("1a2b") }
        verify { tasks.report("shortcut:1a2b", "Ignored shortcut: Gmail", any(), any(), any(), any(), any(), true) }
        coVerify(exactly = 0) { games.upsert(any()) }
    }

    @Test
    fun `add for an ordinary app files the shortcut under a collection named for the app`() = runTest {
        coEvery { store.take(request) } returns request
        every { importer.isPcLauncher("com.android.chrome") } returns false
        coEvery { games.getByIntentUri(any()) } returns null
        coEvery { games.upsert(any()) } returns 42L
        coEvery { collections.getAll() } returns emptyList()
        coEvery { collections.create("Chrome") } returns 7L

        resolver.add(request)

        coVerify { collections.addGame(7L, 42L) }
        verify { tasks.report("shortcut:1a2b", "Added shortcut: Gmail", any(), NotificationSeverity.SUCCESS, any(), any(), any(), true) }
    }

    @Test
    fun `a request that is already gone does nothing`() = runTest {
        coEvery { store.take(request) } returns null
        resolver.add(request)

        coVerify(exactly = 0) { games.upsert(any()) }
        verify(exactly = 0) { tasks.report(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `add files nothing when the queued request changed after it was shown`() = runTest {
        // take() refuses: the entry under this id is no longer the request on screen.
        coEvery { store.take(request) } returns null

        resolver.add(request)

        coVerify(exactly = 0) { games.upsert(any()) }
        coVerify(exactly = 0) { importer.importLegacyShortcut(any(), any(), any()) }
        coVerify(exactly = 0) { collections.addGame(any(), any()) }
    }
}
