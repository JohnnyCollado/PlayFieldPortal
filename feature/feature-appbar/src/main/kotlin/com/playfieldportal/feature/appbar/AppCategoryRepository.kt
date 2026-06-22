package com.playfieldportal.feature.appbar

import android.graphics.drawable.Drawable
import com.playfieldportal.core.data.database.dao.AppOverrideDao
import com.playfieldportal.core.data.database.dao.CategoryDao
import com.playfieldportal.core.data.database.entity.AppOverrideEntity
import com.playfieldportal.core.data.database.entity.CategoryItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// An installed app resolved into a specific XMB category, with its display label and pin state.
data class CategorizedApp(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val pinned: Boolean,
    val isEmulator: Boolean,
)

private const val ITEM_TYPE_APP = "app"

// Resolves installed apps into XMB categories and applies user customizations. The contract:
//   - An app with NO customization is placed by AppClassifier (automatic default).
//   - As soon as the user moves/adds/removes/pins it, the app becomes "customized" and its
//     placement comes solely from category_items rows — automatic classification no longer
//     applies, so scans and package updates never overwrite the user's choice.
//   - Hidden apps never appear; renamed apps use their custom label.
@Singleton
class AppCategoryRepository @Inject constructor(
    private val installedAppRepository: InstalledAppRepository,
    private val classifier: AppClassifier,
    private val categoryDao: CategoryDao,
    private val appOverrideDao: AppOverrideDao,
) {
    @Volatile private var cache: List<InstalledApp> = emptyList()

    // Emits whenever assignment or override state changes, so the XMB can re-resolve.
    fun changes(): Flow<Unit> =
        combine(categoryDao.observeAppItems(), appOverrideDao.observeAll()) { _, _ -> Unit }

    suspend fun ensureLoaded() {
        if (cache.isEmpty()) cache = installedAppRepository.getInstalledApps()
    }

    suspend fun refresh() {
        cache = installedAppRepository.getInstalledApps()
    }

    private suspend fun installedApps(): List<InstalledApp> {
        ensureLoaded()
        return cache
    }

    // Public accessor for the installed-app picker (Android Library / Video / Music "add apps").
    suspend fun allInstalledApps(): List<InstalledApp> = installedApps()

    private fun appByPackage(pkg: String): InstalledApp? = cache.firstOrNull { it.packageName == pkg }

    // ── Resolution ───────────────────────────────────────────────────────────────

    suspend fun appsForCategory(categoryId: String): List<CategorizedApp> {
        val apps      = installedApps()
        val overrides = appOverrideDao.getAll().associateBy { it.packageName }
        val itemsByPkg = categoryDao.getAppItems().groupBy { it.itemId }

        return apps.mapNotNull { app ->
            val ov = overrides[app.packageName]
            if (ov?.isHidden == true) return@mapNotNull null

            val pinned: Boolean
            if (ov?.customized == true) {
                val row = itemsByPkg[app.packageName].orEmpty().firstOrNull { it.categoryId == categoryId }
                    ?: return@mapNotNull null
                pinned = row.pinned
            } else {
                if (categoryId !in classifier.defaultCategories(app)) return@mapNotNull null
                pinned = false
            }

            CategorizedApp(
                packageName = app.packageName,
                label       = ov?.customLabel?.takeIf { it.isNotBlank() } ?: app.label,
                icon        = app.icon,
                pinned      = pinned,
                isEmulator  = app.isEmulator,
            )
        }.sortedWith(compareByDescending<CategorizedApp> { it.pinned }.thenBy { it.label.lowercase() })
    }

    // ── User customization ─────────────────────────────────────────────────────────

    suspend fun moveToCategory(pkg: String, categoryId: String) {
        markCustomized(pkg)
        categoryDao.removeAppFromAllCategories(pkg)
        categoryDao.addItem(CategoryItemEntity(categoryId, pkg, ITEM_TYPE_APP))
        Timber.i("App $pkg moved to category $categoryId")
    }

    suspend fun addToCategory(pkg: String, categoryId: String) {
        materialize(pkg)
        categoryDao.addItem(CategoryItemEntity(categoryId, pkg, ITEM_TYPE_APP))
        Timber.i("App $pkg added to category $categoryId")
    }

    suspend fun removeFromCategory(pkg: String, categoryId: String) {
        materialize(pkg)
        categoryDao.removeItem(categoryId, pkg)
        Timber.i("App $pkg removed from category $categoryId")
    }

    suspend fun pinToCategory(pkg: String, categoryId: String) {
        materialize(pkg)
        if (categoryDao.getCategoriesForApp(pkg).none { it.categoryId == categoryId }) {
            categoryDao.addItem(CategoryItemEntity(categoryId, pkg, ITEM_TYPE_APP))
        }
        categoryDao.setItemPinned(categoryId, pkg, true)
    }

    suspend fun setHidden(pkg: String, hidden: Boolean) {
        val ov = appOverrideDao.getByPackage(pkg) ?: AppOverrideEntity(pkg)
        appOverrideDao.upsert(ov.copy(isHidden = hidden))
    }

    suspend fun rename(pkg: String, label: String?) {
        val ov = appOverrideDao.getByPackage(pkg) ?: AppOverrideEntity(pkg)
        appOverrideDao.upsert(ov.copy(customLabel = label?.trim()?.takeIf { it.isNotBlank() }))
    }

    fun launch(pkg: String) = installedAppRepository.launchApp(pkg)

    // Converts an app's automatic placement into explicit rows the first time the user edits
    // it, so subsequent automatic classification never overrides the user's choice.
    private suspend fun materialize(pkg: String) {
        val ov = appOverrideDao.getByPackage(pkg)
        if (ov?.customized == true) return
        val autoCats = appByPackage(pkg)?.let { classifier.defaultCategories(it) } ?: emptySet()
        autoCats.forEach { categoryDao.addItem(CategoryItemEntity(it, pkg, ITEM_TYPE_APP)) }
        markCustomized(pkg)
    }

    private suspend fun markCustomized(pkg: String) {
        val ov = appOverrideDao.getByPackage(pkg) ?: AppOverrideEntity(pkg)
        if (!ov.customized) appOverrideDao.upsert(ov.copy(customized = true))
    }
}
