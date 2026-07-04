package com.playfieldportal.core.data.discord

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import javax.inject.Qualifier
import javax.inject.Singleton

/** Distinguishes the Discord auth HttpClient from any other Ktor client the app may add later. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DiscordHttpClient

/**
 * Ktor client for Discord OAuth2. Deliberately installs **no logging plugin** — the device code,
 * user code, and tokens must never reach logs. JSON parsing tolerates Discord's extra fields.
 */
@Module
@InstallIn(SingletonComponent::class)
object DiscordNetworkModule {

    @Provides
    @Singleton
    @DiscordHttpClient
    fun provideDiscordHttpClient(): HttpClient = HttpClient(Android) {
        // Fail-soft on non-2xx: the device grant uses 400 + { "error": ... } for pending/slow_down,
        // which we inspect by hand rather than treating as thrown failures.
        expectSuccess = false
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                },
            )
        }
    }
}
