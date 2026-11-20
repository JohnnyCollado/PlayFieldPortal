package com.playfieldportal.core.data.update

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.datastore.pfpDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class AppUpdateCheckerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val downloader = mockk<AppUpdateDownloader>()

    private var now = 1_000_000_000_000L
    private var installed: String? = "1.3.3-lite"
    private var served: ReleaseListFetch = ReleaseListFetch.Failure("unset")

    private val day = 24L * 60 * 60 * 1000

    @Before
    fun clean() = runBlocking<Unit> {
        context.pfpDataStore.edit { it.clear() }
        coEvery { downloader.fetchReleases() } answers { served }
    }

    private fun checker() = AppUpdateChecker(
        context = context,
        downloader = downloader,
        clock = { now },
        installedVersionName = { installed },
    )

    private fun serve(vararg tags: String) {
        served = ReleaseListFetch.Text(
            tags.joinToString(",", "[", "]") { tag ->
                """{"tag_name":"$tag","draft":false,"prerelease":false,"published_at":"2026-11-01T00:00:00Z",
                    "html_url":"https://github.com/JohnnyCollado/PlayFieldPortal/releases/tag/$tag",
                    "body":"## Summary\n- Improved system performance","assets":[]}"""
            },
        )
    }

    private fun snapshot() = runBlocking { checker().snapshot.first() }

    private fun fetches(exactly: Int) = coVerify(exactly = exactly) { downloader.fetchReleases() }

    // ── Checking ──────────────────────────────────────────────────────────────

    @Test
    fun `a newer release is reported as available`() = runBlocking<Unit> {
        serve("1.3.3", "1.3.4")

        assertEquals(AppUpdateCheckResult.UpdateAvailable, checker().check(manual = true))

        val s = snapshot()
        assertEquals("1.3.4", s.available?.version)
        assertEquals(listOf("1.3.4", "1.3.3"), s.releases.map { it.version })
        assertEquals(listOf("Improved system performance"), s.releases.first().summary)
        assertEquals(now, s.lastCheckAt)
    }

    @Test
    fun `the installed release is up to date whatever its flavor suffix`() = runBlocking<Unit> {
        installed = "1.3.4-lite-debug"
        serve("1.3.3", "1.3.4")

        assertEquals(AppUpdateCheckResult.UpToDate, checker().check(manual = true))
        assertNull(snapshot().available)
    }

    @Test
    fun `a build newer than every release is up to date`() = runBlocking<Unit> {
        installed = "1.4.0"
        serve("1.3.4")

        assertEquals(AppUpdateCheckResult.UpToDate, checker().check(manual = true))
        assertNull(snapshot().available)
    }

    @Test
    fun `an automatic check runs at most once a day`() = runBlocking<Unit> {
        serve("1.3.4")
        assertEquals(AppUpdateCheckResult.UpdateAvailable, checker().check(manual = false))

        now += day - 1
        assertEquals(AppUpdateCheckResult.Skipped, checker().check(manual = false))
        fetches(exactly = 1)

        now += 1
        assertEquals(AppUpdateCheckResult.UpdateAvailable, checker().check(manual = false))
        fetches(exactly = 2)
    }

    @Test
    fun `a manual check ignores the daily limit`() = runBlocking<Unit> {
        serve("1.3.4")
        checker().check(manual = false)

        assertEquals(AppUpdateCheckResult.UpdateAvailable, checker().check(manual = true))
        fetches(exactly = 2)
    }

    @Test
    fun `being offline keeps the last known releases and does not start the daily wait`() = runBlocking<Unit> {
        serve("1.3.4")
        checker().check(manual = true)
        val checkedAt = now

        now += day
        served = ReleaseListFetch.Failure("UnknownHostException")
        assertEquals(AppUpdateCheckResult.Offline, checker().check(manual = false))

        val s = snapshot()
        assertEquals("1.3.4", s.available?.version)
        assertEquals(checkedAt, s.lastCheckAt)
        // Still due: the next resume tries again rather than waiting a day on a failed attempt.
        assertEquals(AppUpdateCheckResult.Offline, checker().check(manual = false))
        fetches(exactly = 3)
    }

    @Test
    fun `an unreadable response keeps the last known releases`() = runBlocking<Unit> {
        serve("1.3.4")
        checker().check(manual = true)

        served = ReleaseListFetch.Text("""{"message":"API rate limit exceeded"}""")
        assertEquals(AppUpdateCheckResult.InvalidResponse, checker().check(manual = true))

        assertEquals("1.3.4", snapshot().available?.version)
    }

    @Test
    fun `an update that has been installed is no longer available`() = runBlocking<Unit> {
        serve("1.3.4")
        checker().check(manual = true)

        installed = "1.3.4"

        assertNull(snapshot().available)
    }

    // ── Reminders ─────────────────────────────────────────────────────────────

    @Test
    fun `a found update is reminded once, then at most once a day`() = runBlocking<Unit> {
        serve("1.3.4")
        checker().check(manual = true)

        assertEquals("1.3.4", checker().takeReminder()?.version)
        assertNull(checker().takeReminder(), "the same return to the menu must not remind twice")

        now += day - 1
        assertNull(checker().takeReminder())

        now += 1
        assertEquals("1.3.4", checker().takeReminder()?.version)
    }

    @Test
    fun `a newer update reminds straight away`() = runBlocking<Unit> {
        serve("1.3.4")
        checker().check(manual = true)
        assertNotNull(checker().takeReminder())

        serve("1.3.4", "1.3.5")
        checker().check(manual = true)

        assertEquals("1.3.5", checker().takeReminder()?.version)
    }

    @Test
    fun `a reminded update that has been installed is reported once`() = runBlocking<Unit> {
        serve("1.3.4")
        checker().check(manual = true)
        assertNull(checker().takeInstalledReminder(), "nothing was reminded yet")

        assertNotNull(checker().takeReminder())
        assertNull(checker().takeInstalledReminder(), "still on the old build")

        installed = "1.3.4-lite"

        assertEquals("1.3.4", checker().takeInstalledReminder())
        assertNull(checker().takeInstalledReminder(), "the reminder was settled, so it is not reported again")
    }

    @Test
    fun `no reminder while update notifications are off or nothing is available`() = runBlocking<Unit> {
        assertNull(checker().takeReminder(), "nothing checked yet")

        serve("1.3.4")
        checker().check(manual = true)
        checker().setNotificationsEnabled(false)
        assertNull(checker().takeReminder())

        // Turning it back on reminds at once: the off period never used up the day's reminder.
        checker().setNotificationsEnabled(true)
        assertEquals("1.3.4", checker().takeReminder()?.version)
    }
}
