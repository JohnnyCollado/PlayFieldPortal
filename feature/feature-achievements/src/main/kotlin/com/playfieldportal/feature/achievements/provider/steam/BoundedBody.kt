package com.playfieldportal.feature.achievements.provider.steam

import okhttp3.ResponseBody

/**
 * Reads this (streamed) body as UTF-8 only if it fits in [maxBytes]; null when it is larger. Closes
 * the body either way. The limit is enforced while buffering, so it holds for the gzip + chunked
 * responses whose Content-Length OkHttp strips after decompressing.
 */
internal fun ResponseBody.readUtf8Capped(maxBytes: Long): String? = use {
    val source = it.source()
    if (source.request(maxBytes + 1)) null else source.buffer.readUtf8()
}
