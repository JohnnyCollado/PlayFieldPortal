package com.playfieldportal.feature.appbar

import android.graphics.drawable.Drawable
import com.playfieldportal.core.data.database.dao.AppOverrideDao
import com.playfieldportal.core.data.database.dao.AppUsageDao
import com.playfieldportal.core.data.database.dao.CategoryDao
import com.playfieldportal.core.data.database.entity.AppOverrideEntity
import com.playfieldportal.core.data.database.entity.AppUsageEntity
import com.playfieldportal.core.data.database.entity.CategoryItemEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * App placement in XMB categories: what a launch records, how recency and install time reach the
 * list for the app sorts, and that a pin can be taken off again without moving the app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppCategoryRepositoryTest {

    private val icon: Drawable = mockk(relaxed = true)

    private fun app(pkg: String, label: String, lastUsedAt: Long = 0L, installedAt: Long = 0L) =
        InstalledApp(
            packageName = pkg,
            label = label,
            icon = icon,
            isGame = false,
            isEmulator = false,
            lastUsedAt = lastUsedAt,
            installedAt = installedAt,
        )

    private class Harness(scope: TestScope, apps: List<InstalledApp>) {
        val installed: InstalledAppRepository = mockk(relaxed = true) {
            coEvery { getInstalledApps() } returns apps
        }
        val classifier: AppClassifier = mockk { every { defaultCategories(any()) } returns setOf("videos") }
        val categoryDao: CategoryDao = mockk(relaxed = true) {
            coEvery { getAppItems() } returns emptyList()
        }
        val overrides: AppOverrideDao = mockk(relaxed = true) {
            coEvery { getAll() } returns emptyList()
            coEvery { getByPackage(any()) } returns null
        }
        val usage: AppUsageDao = mockk(relaxed = true) {
            coEvery { getAll() } returns emptyList()
        }
        val monitor: InstalledPackageMonitor = mockk {
            every { packageChanges } returns MutableSharedFlow()
        }
        val repo = AppCategoryRepository(installed, classifier, categoryDao, overrides, usage, monitor, scope.backgroundScope)
    }

    // ── Launch recency ────────────────────────────────────────────────────────

    @Test
    fun `launching an app records its use`() = runTest {
        val h = Harness(this, emptyList())
        every { h.installed.launchApp("com.netflix") } returns true

        h.repo.launch("com.netflix")
        runCurrent()

        coVerify(exactly = 1) { h.usage.recordLaunch("com.netflix", any()) }
    }

    @Test
    fun `an app that could not be launched is not recorded`() = runTest {
        val h = Harness(this, emptyList())
        every { h.installed.launchApp("com.gone") } returns false

        h.repo.launch("com.gone")
        runCurrent()

        coVerify(exactly = 0) { h.usage.recordLaunch(any(), any()) }
    }

    @Test
    fun `an app's last use is the newer of its PFP launch and its system usage`() = runTest {
        val h = Harness(
            this,
            listOf(
                app("com.netflix", "Netflix", lastUsedAt = 100, installedAt = 7),
                app("com.vlc", "VLC", lastUsedAt = 900, installedAt = 8),
            ),
        )
        coEvery { h.usage.getAll() } returns listOf(
            AppUsageEntity("com.netflix", lastLaunchedAt = 500, launchCount = 3),
            AppUsageEntity("com.vlc", lastLaunchedAt = 200, launchCount = 1),
        )

        val apps = h.repo.appsForCategory("videos").associateBy { it.packageName }

        assertEquals(500L, apps.getValue("com.netflix").lastUsedAt)
        assertEquals(900L, apps.getValue("com.vlc").lastUsedAt)
        assertEquals(7L, apps.getValue("com.netflix").installedAt)
    }

    // ── Pins ──────────────────────────────────────────────────────────────────

    @Test
    fun `unpinning clears the pin and leaves the app in the category`() = runTest {
        val h = Harness(this, listOf(app("com.netflix", "Netflix")))
        coEvery { h.overrides.getByPackage("com.netflix") } returns
            AppOverrideEntity("com.netflix", customized = true)

        h.repo.unpinFromCategory("com.netflix", "videos")

        coVerify(exactly = 1) { h.categoryDao.setItemPinned("videos", "com.netflix", false) }
        coVerify(exactly = 0) { h.categoryDao.removeItem(any(), any()) }
    }

    @Test
    fun `unpinning an app that was only auto-placed first makes its placement explicit`() = runTest {
        val h = Harness(this, listOf(app("com.netflix", "Netflix")))
        h.repo.ensureLoaded()

        h.repo.unpinFromCategory("com.netflix", "videos")

        coVerify { h.categoryDao.addItem(match<CategoryItemEntity> { it.categoryId == "videos" && it.itemId == "com.netflix" }) }
        coVerify { h.categoryDao.setItemPinned("videos", "com.netflix", false) }
    }
}
