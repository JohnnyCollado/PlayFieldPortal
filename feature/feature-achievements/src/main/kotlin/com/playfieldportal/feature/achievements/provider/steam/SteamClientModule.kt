package com.playfieldportal.feature.achievements.provider.steam

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Retrofit stack for the Steam islands. The shared OkHttpClient is deliberately bare — timeouts
 * only, **no logging interceptor** — because the user's Steam API key travels in the `key` query
 * parameter and must never reach logcat or a log file (the same rule the RA client follows with
 * api-kotlin's `debugging = false`). One Retrofit instance per host: the Web API, the storefront,
 * the community site, and Steam Hunters.
 */
@Module
@InstallIn(SingletonComponent::class)
object SteamClientModule {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideSteamWebApi(client: OkHttpClient): SteamWebApi =
        retrofit(client, "https://api.steampowered.com/").create(SteamWebApi::class.java)

    @Provides
    @Singleton
    fun provideSteamStoreApi(client: OkHttpClient): SteamStoreApi =
        retrofit(client, "https://store.steampowered.com/").create(SteamStoreApi::class.java)

    @Provides
    @Singleton
    fun provideSteamCommunityApi(client: OkHttpClient): SteamCommunityApi =
        retrofit(client, "https://steamcommunity.com/").create(SteamCommunityApi::class.java)

    @Provides
    @Singleton
    fun provideSteamHuntersApi(client: OkHttpClient): SteamHuntersApi =
        retrofit(client, "https://steamhunters.com/").create(SteamHuntersApi::class.java)

    private fun retrofit(client: OkHttpClient, baseUrl: String): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
}
