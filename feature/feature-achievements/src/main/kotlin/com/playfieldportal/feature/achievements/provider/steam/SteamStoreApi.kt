package com.playfieldportal.feature.achievements.provider.steam

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

/**
 * Steam's storefront search (store.steampowered.com) — a small per-query request, no key needed
 * (so there is nothing sensitive in these URLs). A browser-style User-Agent is sent because the
 * storefront rejects default library agents.
 */
interface SteamStoreApi {

    @Headers("User-Agent: Mozilla/5.0")
    @GET("api/storesearch/")
    suspend fun search(
        @Query("term") term: String,
        @Query("cc") countryCode: String = "us",
        @Query("l") language: String = "en",
    ): Response<StoreSearchResponse>
}

@Serializable
data class StoreSearchResponse(val items: List<StoreItem> = emptyList())

@Serializable
data class StoreItem(val id: Long = 0, val name: String = "", val type: String = "")
