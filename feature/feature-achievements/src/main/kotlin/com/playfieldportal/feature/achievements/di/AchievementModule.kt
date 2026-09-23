package com.playfieldportal.feature.achievements.di

import com.playfieldportal.feature.achievements.AchievementController
import com.playfieldportal.feature.achievements.AchievementRepository
import com.playfieldportal.feature.achievements.sync.AchievementClock
import com.playfieldportal.feature.achievements.sync.AchievementDetailFetcher
import com.playfieldportal.feature.achievements.sync.AchievementSyncScope
import com.playfieldportal.feature.achievements.sync.DefaultAchievementDetailFetcher
import com.playfieldportal.feature.achievements.sync.LocalSteamCheckStrategy
import com.playfieldportal.feature.achievements.sync.LocalSteamReturnListener
import com.playfieldportal.feature.achievements.sync.ProviderCheckStrategy
import com.playfieldportal.feature.achievements.sync.RaCheckStrategy
import com.playfieldportal.feature.achievements.sync.SteamCheckStrategy
import com.playfieldportal.feature.achievements.sync.VitaTrophyCheckStrategy
import com.playfieldportal.feature.launcher.GameSessionReturnListener
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/** Binds the coin-system Controller to its repository, and the selective-sync collaborators. */
@Module
@InstallIn(SingletonComponent::class)
interface AchievementModule {

    @Binds
    fun bindAchievementController(impl: AchievementRepository): AchievementController

    @Binds
    fun bindDetailFetcher(impl: DefaultAchievementDetailFetcher): AchievementDetailFetcher

    // One strategy per provider; the coordinator runs every one it is given.
    @Binds @IntoSet fun raStrategy(impl: RaCheckStrategy): ProviderCheckStrategy
    @Binds @IntoSet fun steamStrategy(impl: SteamCheckStrategy): ProviderCheckStrategy
    @Binds @IntoSet fun localSteamStrategy(impl: LocalSteamCheckStrategy): ProviderCheckStrategy
    @Binds @IntoSet fun vitaStrategy(impl: VitaTrophyCheckStrategy): ProviderCheckStrategy

    @Binds @IntoSet fun localSteamReturn(impl: LocalSteamReturnListener): GameSessionReturnListener
}

@Module
@InstallIn(SingletonComponent::class)
object AchievementSyncProvidesModule {
    @Provides
    fun provideAchievementClock(): AchievementClock = AchievementClock { System.currentTimeMillis() }

    // App-lifetime: an update or a clear must finish even after the screen that started it closes.
    @Provides
    @Singleton
    @AchievementSyncScope
    fun provideAchievementSyncScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
