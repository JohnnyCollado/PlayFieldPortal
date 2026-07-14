package com.playfieldportal.feature.achievements.di

import com.playfieldportal.feature.achievements.AchievementController
import com.playfieldportal.feature.achievements.AchievementRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds the coin-system Controller to its repository implementation. */
@Module
@InstallIn(SingletonComponent::class)
interface AchievementModule {

    @Binds
    fun bindAchievementController(impl: AchievementRepository): AchievementController
}
