package com.playfieldportal.feature.artwork.match

import com.playfieldportal.feature.artwork.api.SteamStorefrontApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The storefront resolver's object graph (C23 T6).
 *
 * The provider list is stated here and nowhere else, so adding GOG is one entry in [provideProviders]
 * and no change to the resolver, the scorer or the normalizer. That is the test of whether the
 * shared architecture actually is shared, and it is the reason Phase 22 says to finish Steam before
 * a second store is written.
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

    /**
     * The resolver's clock. Provided rather than defaulted because Hilt cannot use a Kotlin
     * default argument — an `@Inject` constructor must have a binding for every parameter.
     */
    @Provides
    fun provideTimeSource(): StorefrontMetadataResolver.TimeSource =
        StorefrontMetadataResolver.TimeSource.Default

    @Provides
    @Singleton
    fun provideProviders(steam: SteamMetadataProvider): StorefrontProviders =
        // Steam only, on purpose. GOG and Epic join this list when their providers are written;
        // the identity table, the normalizer, the scorer and the resolver already accommodate them.
        StorefrontProviders(listOf(steam))
}
