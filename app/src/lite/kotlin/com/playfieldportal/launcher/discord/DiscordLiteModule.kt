package com.playfieldportal.launcher.discord

import androidx.activity.ComponentActivity
import com.playfieldportal.core.domain.discord.DiscordSessionActivator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/** Lite flavor: Discord bootstrap does nothing (the SDK isn't in this build). */
class NoOpDiscordBootstrap @Inject constructor() : DiscordBootstrap {
    override fun onCreate(activity: ComponentActivity) { /* Discord SDK excluded from the lite build. */ }
    override fun onResume() {}
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DiscordLiteModule {
    @Binds
    abstract fun bindDiscordBootstrap(impl: NoOpDiscordBootstrap): DiscordBootstrap

    @Binds
    abstract fun bindSessionActivator(impl: NoOpDiscordSessionActivator): DiscordSessionActivator
}
