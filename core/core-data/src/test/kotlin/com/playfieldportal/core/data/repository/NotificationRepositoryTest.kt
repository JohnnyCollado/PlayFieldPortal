package com.playfieldportal.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.database.PFPDatabase
import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationKind
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.domain.repository.NotificationRetention
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class NotificationRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        PFPDatabase::class.java,
    ).allowMainThreadQueries().build()

    /** A clock the test drives, so "older than 30 days" is an assertion and not a wait. */
    private var clock = 1_000_000L

    private var maxRows = NotificationRetention.DEFAULT_MAX_ROWS
    private var autoClearDays = NotificationRetention.DEFAULT_AUTO_CLEAR_DAYS

    private val retention = object : NotificationRetention {
        override suspend fun autoClearDays(): Int = autoClearDays
        override suspend fun maxRows(): Int = maxRows
    }

    private val repository =
        NotificationRepositoryImpl(db.notificationDao(), retention) { clock }

    @After fun tearDown() = db.close()

    private suspend fun post(
        title: String,
        sourceKey: String? = null,
        severity: NotificationSeverity = NotificationSeverity.INFO,
        action: NotificationAction = NotificationAction.None,
    ) = repository.post(
        kind = NotificationKind.SCAN,
        severity = severity,
        title = title,
        sourceKey = sourceKey,
        action = action,
    )

    // ── Dedupe ────────────────────────────────────────────────────────────────

    @Test
    fun `a repeat post on the same source key replaces the row instead of stacking`() = runTest {
        post("Scan failed", sourceKey = "scan:psx")
        clock += 5_000
        post("Scan failed again", sourceKey = "scan:psx")

        val rows = repository.observeAll().first()
        assertEquals(1, rows.size, "a card that fails twice is one row, not two")
        assertEquals("Scan failed again", rows.single().title)
        assertEquals(clock, rows.single().createdAt, "the repeat resets the timestamp")
    }

    @Test
    fun `a dedupe replacing a read row makes it unread again`() = runTest {
        val id = post("Scan failed", sourceKey = "scan:psx")
        repository.markRead(id)
        assertEquals(0, repository.observeUnreadCount().first())

        post("Scan failed again", sourceKey = "scan:psx")

        assertEquals(1, repository.observeUnreadCount().first())
        assertNull(repository.observeAll().first().single().readAt)
    }

    @Test
    fun `keyless posts always append`() = runTest {
        post("One")
        post("Two")
        assertEquals(2, repository.observeAll().first().size)
    }

    // ── The cap ───────────────────────────────────────────────────────────────

    @Test
    fun `the cap evicts read rows before unread ones`() = runTest {
        maxRows = 3
        val oldestRead = post("read and oldest", sourceKey = "a")
        clock += 1_000
        post("unread and old", sourceKey = "b")
        clock += 1_000
        post("unread and newer", sourceKey = "c")
        repository.markRead(oldestRead)

        clock += 1_000
        post("the one that pushes us over", sourceKey = "d")

        val titles = repository.observeAll().first().map { it.title }
        assertEquals(3, titles.size)
        assertTrue(
            "read and oldest" !in titles,
            "the read row should go first even though an older unread rule would keep it",
        )
        assertTrue("unread and old" in titles, "an unread row must outlive a read one")
    }

    @Test
    fun `nothing is evicted while the history is inside the cap`() = runTest {
        maxRows = 10
        repeat(4) { post("row $it", sourceKey = "k$it") }
        assertEquals(4, repository.observeAll().first().size)
    }

    // ── The age window ────────────────────────────────────────────────────────

    @Test
    fun `rows older than the auto-clear window are dropped on the next post`() = runTest {
        autoClearDays = 30
        post("ancient", sourceKey = "old")

        clock += 31L * 24 * 60 * 60 * 1000
        post("fresh", sourceKey = "new")

        assertEquals(listOf("fresh"), repository.observeAll().first().map { it.title })
    }

    @Test
    fun `an auto-clear window of zero never drops anything by age`() = runTest {
        autoClearDays = 0
        post("ancient", sourceKey = "old")

        clock += 3650L * 24 * 60 * 60 * 1000
        post("fresh", sourceKey = "new")

        assertEquals(2, repository.observeAll().first().size)
    }

    // ── Read state and clearing ───────────────────────────────────────────────

    @Test
    fun `the unread count tracks markRead markUnread and markAllRead`() = runTest {
        val first = post("one", sourceKey = "a")
        post("two", sourceKey = "b")
        assertEquals(2, repository.observeUnreadCount().first())

        repository.markRead(first)
        assertEquals(1, repository.observeUnreadCount().first())

        repository.markUnread(first)
        assertEquals(2, repository.observeUnreadCount().first())

        repository.markAllRead()
        assertEquals(0, repository.observeUnreadCount().first())
    }

    @Test
    fun `clearRead spares unread rows`() = runTest {
        val read = post("seen", sourceKey = "a")
        post("unseen", sourceKey = "b")
        repository.markRead(read)

        repository.clearRead()

        assertEquals(listOf("unseen"), repository.observeAll().first().map { it.title })
    }

    @Test
    fun `clearAll empties the history`() = runTest {
        post("one", sourceKey = "a")
        post("two", sourceKey = "b")

        repository.clearAll()

        assertEquals(emptyList(), repository.observeAll().first())
        assertEquals(0, repository.observeUnreadCount().first())
    }

    @Test
    fun `delete removes exactly one row`() = runTest {
        val first = post("one", sourceKey = "a")
        post("two", sourceKey = "b")

        repository.delete(first)

        assertEquals(listOf("two"), repository.observeAll().first().map { it.title })
    }

    // ── Shape ─────────────────────────────────────────────────────────────────

    @Test
    fun `history reads back newest first`() = runTest {
        post("oldest", sourceKey = "a")
        clock += 1_000
        post("middle", sourceKey = "b")
        clock += 1_000
        post("newest", sourceKey = "c")

        assertEquals(
            listOf("newest", "middle", "oldest"),
            repository.observeAll().first().map { it.title },
        )
    }

    @Test
    fun `an action survives the round-trip through the row`() = runTest {
        post("Scan failed", sourceKey = "a", action = NotificationAction.OpenMemoryCard("psx"))

        val row = repository.observeAll().first().single()
        assertEquals(NotificationAction.OpenMemoryCard("psx"), row.action)
        assertNotNull(row.sourceKey)
    }
}
