package com.playfieldportal.feature.xmb.di

import com.playfieldportal.feature.xmb.gamepad.ControllerMappingRepository
import com.playfieldportal.feature.xmb.gamepad.GamepadInputHandler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object XMBModule {

    @Provides
    @Singleton
    fun provideGamepadInputHandler(
        mappingRepository: ControllerMappingRepository,
    ): GamepadInputHandler = GamepadInputHandler(mappingRepository)
}
