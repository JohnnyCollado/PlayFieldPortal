package com.playfieldportal.feature.artwork.store

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ArtworkStoreModule {

    // Writes only to the user's linked artwork folder; when it is not ready a write returns null and
    // reports on ArtworkFolderStatus. Internal storage is a read/migration source, never a fallback.
    @Binds
    abstract fun bindArtworkStore(impl: RoutingArtworkStore): ArtworkStore
}
