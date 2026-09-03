package com.playfieldportal.feature.artwork.credentials

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the credential source. Swapping in a proxied adapter later is a one-line change here
 * rather than an edit to every client of the API.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CredentialModule {

    @Binds
    @Singleton
    abstract fun bindMetadataCredentialSource(
        impl: BundledDevPairCredentialSource,
    ): MetadataCredentialSource
}
