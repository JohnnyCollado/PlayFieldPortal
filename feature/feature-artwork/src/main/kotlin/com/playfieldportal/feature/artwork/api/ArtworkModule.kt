package com.playfieldportal.feature.artwork.api

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.crossfade
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import com.playfieldportal.feature.artwork.BuildConfig
import com.playfieldportal.feature.artwork.match.FileTitleSearchStore
import com.playfieldportal.feature.artwork.match.TitleSearchStore
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okio.Path.Companion.toOkioPath
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

// ScreenScraper authenticates via query parameters, so a logged request line would expose the
// user's password (and our dev password) in debug logcat. Redact them before anything is written.
private val SECRET_QUERY_PARAMS = Regex("(sspassword|devpassword|apikey)=[^&\\s]*", RegexOption.IGNORE_CASE)

internal fun redactSecretQueryParams(message: String): String =
    SECRET_QUERY_PARAMS.replace(message) { "${it.groupValues[1]}=REDACTED" }

@Module
@InstallIn(SingletonComponent::class)
object ArtworkModule {

    // OkHttp engine, not Android's: cancelling a browse while its body is still downloading used
    // to abort through the Android engine's HttpURLConnection stream, which threw from
    // Job.cancel() on the main thread. OkHttp cancels via Call.cancel(), which is thread-safe.
    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        // No defaults: every request keeps the engine's 15 s below. Installed so a request can ask
        // for its own longer wait with timeout {} (ScreenScraper's name search does), which the
        // OkHttp engine applies to a client built for that timeout.
        install(HttpTimeout)
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        // Release logs nothing (no request lines that could carry a key in a query string); debug
        // logs headers but with the Authorization token redacted so an API secret never lands in a
        // log even on a dev machine.
        install(Logging) {
            level = if (BuildConfig.DEBUG) LogLevel.HEADERS else LogLevel.NONE
            logger = object : Logger {
                override fun log(message: String) = Timber.tag("Ktor").d(redactSecretQueryParams(message))
            }
            sanitizeHeader { header -> header.equals(HttpHeaders.Authorization, ignoreCase = true) }
        }
        engine {
            // OkHttp's engine config has no connectTimeout/socketTimeout properties (unlike the
            // Android engine's), so the same 15 s ceiling is set on the OkHttp client builder.
            // config {} appends to Ktor's defaults, which already keep OkHttp's own redirect
            // handling off (HttpRedirect drives that) and retryOnConnectionFailure on.
            config {
                connectTimeout(15, TimeUnit.SECONDS)
                // The read timeout is the socket-inactivity equivalent of the Android engine's
                // socketTimeout: ScreenScraper's single request slot is freed by a failed request.
                readTimeout(15, TimeUnit.SECONDS)
            }
        }
    }

    // Title searches kept between Artwork Studio opens (AD-21). In the cache, not filesDir: every
    // entry can be asked for again, so the system is free to clear it.
    @Provides
    @Singleton
    fun provideTitleSearchStore(@ApplicationContext context: Context): TitleSearchStore =
        FileTitleSearchStore(context.cacheDir.resolve("match-searches"))

    @Provides
    @Singleton
    fun provideCoilImageLoader(@ApplicationContext context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                // Coil 3 dropped the context argument from the builder; the percentage
                // helper now takes it instead, since it reads the device memory class.
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("artwork_cache").toOkioPath())
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            // Animated GIF / animated WebP support (user-supplied motion wallpapers route
            // GIF/animated-WebP through AsyncImage). AnimatedImageDecoder is the API 28+
            // ImageDecoder-backed factory — fine, minSdk is 29; the slower GifDecoder fallback
            // for older APIs is unnecessary here. Registered so the ImageLoader "automatically
            // detects any GIFs using their file headers"; stills are unaffected.
            .components {
                add(coil3.gif.AnimatedImageDecoder.Factory())
            }
            .build()
}
