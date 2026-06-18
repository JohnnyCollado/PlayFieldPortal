package com.playfieldportal.launcher.debug

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.entity.toEntity
import com.playfieldportal.core.data.datastore.pfpDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_DEBUG_SEEDED = booleanPreferencesKey("debug_seeded_v1")

@Singleton
class DebugSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gameDao: GameDao,
    private val debugController: DebugController,
) {
    // Populates the real Room DB with fake games on first debug launch.
    // Safe to call multiple times — guarded by DataStore flag.
    suspend fun seedIfNeeded() {
        val prefs = context.pfpDataStore.data.first()
        if (prefs[KEY_DEBUG_SEEDED] == true) {
            Timber.d("Debug data already seeded — skipping")
            return
        }

        val games = DebugGameFactory.gamesForScenario(DebugScenario.FULL_LIBRARY)
        gameDao.insertAll(games.map { it.toEntity() })

        context.pfpDataStore.edit { it[KEY_DEBUG_SEEDED] = true }
        Timber.i("Debug seeder: inserted ${games.size} fake games into Room DB")
    }

    // Called from the debug menu — wipes and re-seeds with a chosen scenario
    suspend fun reseed(scenario: DebugScenario) {
        Timber.i("Debug reseed: clearing games, seeding scenario=${scenario.name}")

        // Clear existing debug games (manual entries only — won't touch real scanned ROMs)
        // In a full implementation we'd track debug-seeded IDs separately
        val games = DebugGameFactory.gamesForScenario(scenario)
        gameDao.insertAll(games.map { it.toEntity() })

        // Reset the seeded flag so next launch re-seeds if needed
        context.pfpDataStore.edit { it[KEY_DEBUG_SEEDED] = true }
        debugController.setScenario(scenario)

        Timber.i("Debug reseed complete: ${games.size} games for scenario ${scenario.name}")
    }
}
