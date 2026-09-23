package com.playfieldportal.feature.launcher

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/** Declares the (possibly empty) set of [GameSessionReturnListener]s other modules contribute to. */
@Module
@InstallIn(SingletonComponent::class)
abstract class GameSessionReturnModule {
    @Multibinds
    abstract fun gameSessionReturnListeners(): Set<GameSessionReturnListener>
}
