package com.playfieldportal.core.data.discord

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DiscordDeviceAuthClientTest {

    private fun clientReturning(status: HttpStatusCode, body: String): DiscordDeviceAuthClient {
        val engine = MockEngine {
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val http = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
        return DiscordDeviceAuthClient(http)
    }

    @Test
    fun `requestDeviceCode parses the device authorization response`() = runTest {
        val json = """
            {"device_code":"DEV123","user_code":"WXYZ-1234",
             "verification_uri":"https://discord.com/activate",
             "verification_uri_complete":"https://discord.com/activate?user_code=WXYZ-1234",
             "expires_in":300,"interval":5}
        """.trimIndent()

        val challenge = clientReturning(HttpStatusCode.OK, json).requestDeviceCode()

        assertEquals("WXYZ-1234", challenge.userCode)
        assertEquals("DEV123", challenge.deviceCode)
        assertEquals("https://discord.com/activate?user_code=WXYZ-1234", challenge.verificationUriComplete)
        assertEquals(300, challenge.expiresInSeconds)
        assertEquals(5, challenge.pollIntervalSeconds)
    }

    @Test
    fun `requestDeviceCode synthesizes verification_uri_complete when omitted`() = runTest {
        val json = """
            {"device_code":"DEV123","user_code":"WXYZ-1234",
             "verification_uri":"https://discord.com/activate","expires_in":300,"interval":5}
        """.trimIndent()

        val challenge = clientReturning(HttpStatusCode.OK, json).requestDeviceCode()

        assertEquals(
            "https://discord.com/activate?user_code=WXYZ-1234",
            challenge.verificationUriComplete,
        )
    }

    @Test
    fun `pollForToken maps authorization_pending to Pending`() = runTest {
        val result = clientReturning(HttpStatusCode.BadRequest, """{"error":"authorization_pending"}""")
            .pollForToken("DEV123")
        assertIs<TokenPollResult.Pending>(result)
    }

    @Test
    fun `pollForToken maps slow_down to SlowDown`() = runTest {
        val result = clientReturning(HttpStatusCode.BadRequest, """{"error":"slow_down"}""")
            .pollForToken("DEV123")
        assertIs<TokenPollResult.SlowDown>(result)
    }

    @Test
    fun `pollForToken maps expired_token to Expired`() = runTest {
        val result = clientReturning(HttpStatusCode.BadRequest, """{"error":"expired_token"}""")
            .pollForToken("DEV123")
        assertIs<TokenPollResult.Expired>(result)
    }

    @Test
    fun `pollForToken maps access_denied to Denied`() = runTest {
        val result = clientReturning(HttpStatusCode.BadRequest, """{"error":"access_denied"}""")
            .pollForToken("DEV123")
        assertIs<TokenPollResult.Denied>(result)
    }

    @Test
    fun `pollForToken maps a successful grant to Approved with tokens`() = runTest {
        val json = """
            {"access_token":"AT-abc","refresh_token":"RT-xyz","token_type":"Bearer",
             "expires_in":604800,"scope":"openid sdk.social_layer"}
        """.trimIndent()

        val result = clientReturning(HttpStatusCode.OK, json).pollForToken("DEV123")

        val approved = assertIs<TokenPollResult.Approved>(result)
        assertEquals("AT-abc", approved.tokens.accessToken)
        assertEquals("RT-xyz", approved.tokens.refreshToken)
        assertEquals(604800, approved.tokens.expiresInSeconds)
        assertEquals("openid sdk.social_layer", approved.tokens.scopes)
    }
}
