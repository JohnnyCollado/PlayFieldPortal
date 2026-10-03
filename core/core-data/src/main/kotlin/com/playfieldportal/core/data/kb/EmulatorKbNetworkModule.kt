package com.playfieldportal.core.data.kb

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import javax.inject.Qualifier
import javax.inject.Singleton

/** Distinguishes the emulator knowledge-base update client from every other HTTP client. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EmulatorKbHttpClient

/**
 * Ktor client for the knowledge-base update fetch. Deliberately minimal: **no logging plugin**, no
 * cookies, no auth. Redirects keep Ktor's default (followed, never downgraded from HTTPS to HTTP),
 * which GitHub's asset CDN hop needs. The OkHttp engine matches [com.playfieldportal.core.data.discord.DiscordNetworkModule].
 */
@Module
@InstallIn(SingletonComponent::class)
object EmulatorKbNetworkModule {

    @Provides
    @Singleton
    @EmulatorKbHttpClient
    fun provideEmulatorKbHttpClient(): HttpClient = HttpClient(OkHttp) { configureEmulatorKbClient() }
}

/** The knowledge-base client's configuration, shared with tests so they exercise what ships. */
internal fun <T : HttpClientEngineConfig> HttpClientConfig<T>.configureEmulatorKbClient() {
    // Non-2xx comes back as a response the downloader turns into a typed failure.
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 15_000
        requestTimeoutMillis = 30_000
    }
}
