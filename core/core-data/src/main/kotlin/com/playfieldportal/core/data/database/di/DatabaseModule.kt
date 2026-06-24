package com.playfieldportal.core.data.database.di

import android.content.Context
import androidx.room.Room
import com.playfieldportal.core.data.database.PFPDatabase
import com.playfieldportal.core.data.database.dao.AppOverrideDao
import com.playfieldportal.core.data.database.dao.CategoryDao
import com.playfieldportal.core.data.database.dao.CollectionDao
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.dao.LibrarySourceDao
import com.playfieldportal.core.data.database.dao.MemoryCardDao
import com.playfieldportal.core.data.database.dao.PlaySessionDao
import com.playfieldportal.core.data.database.dao.PlatformDao
import com.playfieldportal.core.data.database.dao.ThemeDao
import com.playfieldportal.core.data.database.dao.UnmatchedRomDao
import com.playfieldportal.core.data.repository.CategoryRepositoryImpl
import com.playfieldportal.core.data.repository.GameRepositoryImpl
import com.playfieldportal.core.domain.repository.GameRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun providePFPDatabase(@ApplicationContext context: Context): PFPDatabase =
        Room.databaseBuilder(
            context,
            PFPDatabase::class.java,
            PFPDatabase.DATABASE_NAME,
        )
        // Never use fallbackToDestructiveMigration — users would lose their entire library
        .addMigrations(
            PFPDatabase.MIGRATION_1_2,
            PFPDatabase.MIGRATION_2_3,
            PFPDatabase.MIGRATION_3_4,
            PFPDatabase.MIGRATION_4_5,
            PFPDatabase.MIGRATION_5_6,
            PFPDatabase.MIGRATION_6_7,
            PFPDatabase.MIGRATION_7_8,
            PFPDatabase.MIGRATION_8_9,
            PFPDatabase.MIGRATION_9_10,
        )
        .build()

    @Provides fun provideGameDao(db: PFPDatabase): GameDao = db.gameDao()
    @Provides fun providePlatformDao(db: PFPDatabase): PlatformDao = db.platformDao()
    @Provides fun provideCategoryDao(db: PFPDatabase): CategoryDao = db.categoryDao()
    @Provides fun providePlaySessionDao(db: PFPDatabase): PlaySessionDao = db.playSessionDao()
    @Provides fun provideLibrarySourceDao(db: PFPDatabase): LibrarySourceDao = db.librarySourceDao()
    @Provides fun provideUnmatchedRomDao(db: PFPDatabase): UnmatchedRomDao = db.unmatchedRomDao()
    @Provides fun provideThemeDao(db: PFPDatabase): ThemeDao = db.themeDao()
    @Provides fun provideMemoryCardDao(db: PFPDatabase): MemoryCardDao = db.memoryCardDao()
    @Provides fun provideAppOverrideDao(db: PFPDatabase): AppOverrideDao = db.appOverrideDao()
    @Provides fun provideCollectionDao(db: PFPDatabase): CollectionDao = db.collectionDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindGameRepository(impl: GameRepositoryImpl): GameRepository
}
