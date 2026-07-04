package com.playfieldportal.discord

import com.playfieldportal.core.domain.discord.DiscordSessionActivator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds the native session activator so `core-data`'s DiscordAuthRepository can inject it. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DiscordNativeModule {
    @Binds
    abstract fun bindSessionActivator(impl: DiscordNativeSessionActivator): DiscordSessionActivator
}
