package com.playfieldportal.core.data.database.seeder

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.playfieldportal.core.data.database.dao.ThemeDao
import com.playfieldportal.core.data.database.entity.ThemeEntity
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.data.repository.CategoryRepositoryImpl
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_DB_SEEDED     = booleanPreferencesKey("db_seeded_v1")
private val KEY_THEMES_SEEDED = booleanPreferencesKey("themes_seeded_v1")

/** Built-in theme seeded separately from the main DB seed so it can be added to existing installs. */
private val BUILTIN_CLASSIC_BLUE = ThemeEntity(
    id               = "builtin_classic_blue",
    name             = "Classic PSP Blue",
    author           = "Play Field Portal",
    version          = "1.0",
    waveColor        = 0xFF0055AAL,
    waveOpacity      = 0.7f,
    waveSpeed        = 1.0f,
    waveAmplitude    = 1.0f,
    accentColor      = 0xFFFFFFFFL,
    textColor        = 0xFFFFFFFFL,
    backgroundUri    = null,
    fontKey          = "system_default",
    hasBootAnimation = false,
    bootAnimationUri = null,
    soundPackUri     = null,
    packagePath      = null,
    isBuiltIn        = true,
    isActive         = true,
)

@Singleton
class DatabaseInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val platformSeeder: PlatformSeeder,
    private val categoryRepository: CategoryRepositoryImpl,
    private val themeDao: ThemeDao,
) {
    // Called once from PFPApplication after DI is ready.
    // Safe to call multiple times — guarded by DataStore flags and INSERT OR IGNORE.
    suspend fun initialize() {
        seedMainDb()
        seedThemes()
    }

    private suspend fun seedMainDb() {
        val prefs = context.pfpDataStore.data.first()
        if (prefs[KEY_DB_SEEDED] == true) {
            Timber.d("DB already seeded — skipping")
            return
        }

        Timber.i("First launch — seeding database")
        platformSeeder.seed()
        categoryRepository.seedBuiltInCategories()

        context.pfpDataStore.edit { it[KEY_DB_SEEDED] = true }
        Timber.i("Database seed complete")
    }

    private suspend fun seedThemes() {
        val prefs = context.pfpDataStore.data.first()
        if (prefs[KEY_THEMES_SEEDED] == true) {
            Timber.d("Themes already seeded — skipping")
            return
        }

        Timber.i("Seeding built-in themes")
        themeDao.insertAll(listOf(BUILTIN_CLASSIC_BLUE))

        context.pfpDataStore.edit { it[KEY_THEMES_SEEDED] = true }
        Timber.i("Theme seed complete")
    }
}
