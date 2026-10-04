package com.playfieldportal.launcher.discord

import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.playfieldportal.core.data.discord.DiscordAuthRepository
import com.playfieldportal.core.data.discord.DiscordPresenceController
import com.playfieldportal.core.domain.discord.DiscordPumpPolicy
import com.playfieldportal.discord.DiscordNativeBridge
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Full-flavor Discord bootstrap: attaches the SDK engine on launch (so both a saved session and a
 * fresh QR login have an activity context) and restores/updates presence. Attaching only loads the
 * native lib and stores the activity reference — no client, network or mic until the user signs in.
 */
@Singleton
class FullDiscordBootstrap @Inject constructor(
    private val discordAuthRepository: DiscordAuthRepository,
    private val discordPresence: DiscordPresenceController,
) : DiscordBootstrap {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(activity: ComponentActivity) {
        DiscordNativeBridge.attachActivity(activity)
        // The SDK pump idles fast on screen and slow behind other apps or with the screen off;
        // calls and requests stay fast either way (DiscordPumpPolicy).
        activity.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START ->
                        DiscordNativeBridge.setIdlePumpInterval(DiscordPumpPolicy.idleIntervalMs(foreground = true))
                    Lifecycle.Event.ON_STOP ->
                        DiscordNativeBridge.setIdlePumpInterval(DiscordPumpPolicy.idleIntervalMs(foreground = false))
                    else -> Unit
                }
            },
        )
        activity.lifecycleScope.launch {
            if (discordAuthRepository.hasSession()) {
                discordAuthRepository.restoreSession()
                discordPresence.refresh()
            }
        }
    }

    override fun onResume() {
        scope.launch { discordPresence.clearCurrentGame() }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DiscordBootstrapModule {
    @Binds
    abstract fun bindDiscordBootstrap(impl: FullDiscordBootstrap): DiscordBootstrap
}
