package com.playfieldportal.launcher.debug

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// Provides debug-only singletons — this file only compiles in debug builds
@Module
@InstallIn(SingletonComponent::class)
object DebugModule {

    @Provides
    @Singleton
    fun provideDebugController(): DebugController = DebugController()
}
