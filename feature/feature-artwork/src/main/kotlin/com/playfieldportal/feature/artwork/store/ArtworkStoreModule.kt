package com.playfieldportal.feature.artwork.store

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ArtworkStoreModule {

    // Routes to the user's portable media library when a folder is linked; falls back to
    // internal app storage otherwise. Callers never know the difference.
    @Binds
    abstract fun bindArtworkStore(impl: RoutingArtworkStore): ArtworkStore
}
