package com.playfieldportal.feature.achievements.api

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

/** Distinguishes the achievement HttpClient from the app's other Ktor clients. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AchievementsHttpClient

/**
 * Ktor client for the RetroAchievements and Steam Web APIs. Both carry the user's API key in a
 * query parameter (Steam `key`, RA `y`), so — like the Discord client — it installs **no logging
 * plugin**: no request line is ever written, and a key can never leak into logcat or the log file.
 * The APIs are read-only; [expectSuccess] is off so error bodies ("Profile is not public", bad key)
 * are inspected by hand rather than thrown with the URL attached.
 */
@Module
@InstallIn(SingletonComponent::class)
object AchievementNetworkModule {

    @Provides
    @Singleton
    @AchievementsHttpClient
    fun provideAchievementsHttpClient(): HttpClient = HttpClient(Android) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                },
            )
        }
        engine {
            connectTimeout = 15_000
            socketTimeout = 15_000
        }
    }
}
