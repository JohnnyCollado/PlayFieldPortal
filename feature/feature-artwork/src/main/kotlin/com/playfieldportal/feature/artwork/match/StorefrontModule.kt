package com.playfieldportal.feature.artwork.match

import com.playfieldportal.feature.artwork.api.GogStorefrontApi
import com.playfieldportal.feature.artwork.api.SteamStorefrontApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The storefront resolver's object graph (C23 T6).
 *
 * The provider list is stated here and nowhere else: GOG joined it as one entry in
 * [provideProviders], with no change to the scorer or the normalizer. That is the test of whether
 * the shared architecture actually is shared, and it is the reason Steam was finished before a
 * second store was written.
 *
 * Each provider gets its OWN queue and cache. A shared one would let a Steam rate limit slow a GOG
 * lookup down, which is precisely the coupling Phase 15's failure isolation exists to prevent.
 */
@Module
@InstallIn(SingletonComponent::class)
object StorefrontModule {

    @Provides
    @Singleton
    fun provideSteamProvider(api: SteamStorefrontApi): SteamMetadataProvider =
        SteamMetadataProvider(
            api = api,
            queue = StorefrontRequestQueue(),
            searchCache = StorefrontSearchCache(),
        )

    @Provides
    @Singleton
    fun provideGogProvider(api: GogStorefrontApi): GogMetadataProvider =
        GogMetadataProvider(
            api = api,
            queue = StorefrontRequestQueue(),
            searchCache = StorefrontSearchCache(),
        )

    /**
     * The resolver's clock. Provided rather than defaulted because Hilt cannot use a Kotlin
     * default argument — an `@Inject` constructor must have a binding for every parameter.
     */
    @Provides
    fun provideTimeSource(): StorefrontMetadataResolver.TimeSource =
        StorefrontMetadataResolver.TimeSource.Default

    @Provides
    @Singleton
    fun provideProviders(steam: SteamMetadataProvider, gog: GogMetadataProvider): StorefrontProviders =
        // The order is display order — picker tabs, preview columns — not who is asked: every
        // available provider always is. Steam first because it is the validated one and the one
        // achievements depend on. Epic is absent on purpose: it has no public catalog to search,
        // so there is no provider to write, and Store Match leaves it off.
        StorefrontProviders(listOf(steam, gog))
}
