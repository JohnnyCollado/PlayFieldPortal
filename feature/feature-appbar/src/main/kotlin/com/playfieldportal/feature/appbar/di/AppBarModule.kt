package com.playfieldportal.feature.appbar.di

import android.content.Context
import com.playfieldportal.feature.appbar.InstalledAppRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppBarModule {

    @Provides
    @Singleton
    fun provideInstalledAppRepository(
        @ApplicationContext context: Context,
    ): InstalledAppRepository = InstalledAppRepository(context)
}
