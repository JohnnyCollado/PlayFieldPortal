package com.playfieldportal.feature.artwork.store

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ArtworkStoreModule {

    // Internal storage is the only backend today; the portable SAF provider (plan M3) becomes a
    // runtime-selected delegate behind this same binding.
    @Binds
    abstract fun bindArtworkStore(impl: InternalArtworkStore): ArtworkStore
}
