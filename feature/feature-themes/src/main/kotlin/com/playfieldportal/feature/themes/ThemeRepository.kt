package com.playfieldportal.feature.themes

import android.content.Context
import android.net.Uri
import com.playfieldportal.core.data.database.dao.ThemeDao
import com.playfieldportal.core.data.database.entity.toDomain
import com.playfieldportal.core.domain.model.PFPTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

interface ThemeRepository {
    /** Emits the currently active theme, or null if none is set. */
    fun observeActiveTheme(): Flow<PFPTheme?>

    /** Emits all installed themes (built-in first, then user themes by name). */
    fun observeAll(): Flow<List<PFPTheme>>

    /** Sets the active theme by ID. All other themes are deactivated atomically. */
    suspend fun setActiveTheme(id: String)

    /**
     * Installs a .xmbtheme ZIP from a SAF URI.
     * Extracts assets to `filesDir/themes/{id}/` and upserts a [ThemeEntity] in the DB.
     */
    suspend fun installTheme(uri: Uri): ThemeLoadResult

    /**
     * Removes a user-installed theme from the DB and deletes its asset directory.
     * No-op for built-in themes (protected at the DAO level by is_built_in = 0 guard).
     */
    suspend fun uninstallTheme(id: String)
}

@Singleton
class ThemeRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val themeDao: ThemeDao,
    private val loader: XmbThemeLoader,
) : ThemeRepository {

    override fun observeActiveTheme(): Flow<PFPTheme?> =
        themeDao.observeAll().map { themes -> themes.firstOrNull { it.isActive }?.toDomain() }

    override fun observeAll(): Flow<List<PFPTheme>> =
        themeDao.observeAll().map { themes -> themes.map { it.toDomain() } }

    override suspend fun setActiveTheme(id: String) {
        themeDao.setActiveTheme(id)
        Timber.i("Active theme set: $id")
    }

    override suspend fun installTheme(uri: Uri): ThemeLoadResult =
        loader.loadFromUri(uri)

    override suspend fun uninstallTheme(id: String) {
        themeDao.deleteUserTheme(id)
        File(context.filesDir, "themes/$id").deleteRecursively()
        Timber.i("Theme uninstalled: $id")
    }
}
