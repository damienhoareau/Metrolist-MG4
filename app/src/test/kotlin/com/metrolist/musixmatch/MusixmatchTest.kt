/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.musixmatch

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MusixmatchTest {
    private val minute = 60_000L
    private var now = 1_000_000L
    private var tokenRequests = 0
    private var lyricsRequests = 0
    private var tokenRequest: HttpRequestData? = null
    private val tokensSeenByLyrics = mutableListOf<String>()

    /** Decides how the fake server answers; swapped per test. */
    private var onToken: MockRequestHandleScope.() -> HttpResponseData = { json(tokenBody("token-$tokenRequests")) }
    private var onLyrics: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { json(lyricsBody(subtitles = SUBTITLES)) }

    @Before
    fun setUp() {
        now = 1_000_000L
        tokenRequests = 0
        lyricsRequests = 0
        tokenRequest = null
        tokensSeenByLyrics.clear()
        Musixmatch.resetTokenState()
        Musixmatch.clock = { now }
        Musixmatch.client =
            HttpClient(
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("token.get") -> {
                            tokenRequests++
                            tokenRequest = request
                            onToken()
                        }
                        else -> {
                            lyricsRequests++
                            tokensSeenByLyrics += request.url.parameters["usertoken"].orEmpty()
                            onLyrics(request)
                        }
                    }
                },
            ) { expectSuccess = true }
    }

    @After
    fun tearDown() {
        Musixmatch.resetTokenState()
        Musixmatch.clock = System::currentTimeMillis
    }

    private fun lyrics() = runBlocking { Musixmatch.getLyrics("Title", "Artist", 200, null) }

    @Test
    fun `token is obtained from the Musixmatch desktop endpoint`() {
        assertTrue(lyrics().isSuccess)

        val request = requireNotNull(tokenRequest)
        assertEquals("apic-desktop.musixmatch.com", request.url.host)
        assertEquals("/ws/1.1/token.get", request.url.encodedPath)
        assertEquals("json", request.url.parameters["format"])
        assertEquals("web-desktop-app-v1.0", request.url.parameters["app_id"])
        assertEquals("en", request.url.parameters["user_language"])
        assertTrue(!request.url.parameters["guid"].isNullOrBlank())
        assertEquals(listOf("token-1"), tokensSeenByLyrics)
        assertTrue(tokensSeenByLyrics.single().any { it != '0' })
    }

    @Test
    fun `token is fetched once and reused while it is fresh`() {
        assertTrue(lyrics().isSuccess)
        now += 9 * minute
        assertTrue(lyrics().isSuccess)

        assertEquals(1, tokenRequests)
        assertEquals(listOf("token-1", "token-1"), tokensSeenByLyrics)
    }

    @Test
    fun `token is fetched again once ten minutes have passed`() {
        lyrics()
        now += 10 * minute + 1
        lyrics()

        assertEquals(2, tokenRequests)
        assertEquals(listOf("token-1", "token-2"), tokensSeenByLyrics)
    }

    @Test
    fun `placeholder token is refused and not retried right away`() {
        onToken = { json(tokenBody("0".repeat(56))) }

        assertTrue(lyrics().isFailure)
        assertTrue(lyrics().isFailure)

        assertEquals(1, tokenRequests)
        assertEquals(0, lyricsRequests)
    }

    @Test
    fun `placeholder token is requested again after the back-off`() {
        onToken = { json(tokenBody("0".repeat(56))) }
        lyrics()
        now += 5 * minute + 1
        onToken = { json(tokenBody("real-token")) }

        assertTrue(lyrics().isSuccess)
        assertEquals(2, tokenRequests)
        assertEquals(listOf("real-token"), tokensSeenByLyrics)
    }

    @Test
    fun `token request answered with an error status backs off`() {
        onToken = { respond("", HttpStatusCode.ServiceUnavailable) }

        assertTrue(lyrics().isFailure)
        assertTrue(lyrics().isFailure)

        assertEquals(1, tokenRequests)
        assertEquals(0, lyricsRequests)
    }

    @Test
    fun `token request with a failing status code in the body is refused`() {
        onToken = { json(tokenBody("token", statusCode = 401)) }

        assertTrue(lyrics().isFailure)
        assertEquals(0, lyricsRequests)
    }

    @Test
    fun `rejected token in the response is renewed and the call is retried once`() {
        onLyrics = { if (lyricsRequests == 1) json(lyricsBody(statusCode = 401)) else json(lyricsBody(subtitles = SUBTITLES)) }

        val result = lyrics()

        assertTrue(result.isSuccess)
        assertEquals(2, tokenRequests)
        assertEquals(listOf("token-1", "token-2"), tokensSeenByLyrics)
    }

    @Test
    fun `HTTP 401 on the lyrics call renews the token and retries once`() {
        onLyrics = { if (lyricsRequests == 1) respond("", HttpStatusCode.Unauthorized) else json(lyricsBody(subtitles = SUBTITLES)) }

        assertTrue(lyrics().isSuccess)
        assertEquals(2, tokenRequests)
        assertEquals(2, lyricsRequests)
    }

    @Test
    fun `a token rejected twice in a row gives up instead of looping`() {
        onLyrics = { respond("", HttpStatusCode.Forbidden) }

        assertTrue(lyrics().isFailure)
        assertEquals(2, tokenRequests)
        assertEquals(2, lyricsRequests)
    }

    @Test
    fun `other HTTP errors on the lyrics call keep the token`() {
        onLyrics = { respond("", HttpStatusCode.InternalServerError) }

        assertTrue(lyrics().isFailure)
        assertEquals(1, tokenRequests)
        assertEquals(1, lyricsRequests)
    }

    @Test
    fun `richsync is converted to word timed lyrics and preferred over subtitles`() {
        onLyrics = { json(lyricsBody(subtitles = SUBTITLES, richsync = RICHSYNC)) }

        assertEquals("[00:01.00]Hel lo\n<Hel:1.0:1.5|lo:1.5:3.0>", lyrics().getOrThrow())
    }

    @Test
    fun `subtitles are used when there is no richsync`() {
        assertEquals("[00:12.34]Hello\n[00:15.00]World", lyrics().getOrThrow())
    }

    @Test
    fun `a song without any synced lyrics is a failure`() {
        onLyrics = { json(lyricsBody()) }

        assertTrue(lyrics().isFailure)
    }

    private fun MockRequestHandleScope.json(body: JsonObject) =
        respond(
            content = body.toString(),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )

    private fun tokenBody(
        token: String,
        statusCode: Int = 200,
    ) = buildJsonObject {
        put(
            "message",
            buildJsonObject {
                put("header", buildJsonObject { put("status_code", statusCode) })
                put("body", buildJsonObject { put("user_token", token) })
            },
        )
    }

    private fun lyricsBody(
        statusCode: Int = 200,
        subtitles: String? = null,
        richsync: String? = null,
    ) = buildJsonObject {
        put(
            "message",
            buildJsonObject {
                put("header", buildJsonObject { put("status_code", statusCode) })
                put(
                    "body",
                    buildJsonObject {
                        put(
                            "macro_calls",
                            buildJsonObject {
                                put("track.subtitles.get", call(subtitles?.let { subtitleList(it) }))
                                if (richsync != null) put("track.richsync.get", call(richsyncBody(richsync)))
                            },
                        )
                    },
                )
            },
        )
    }

    private fun call(body: JsonObject?) = buildJsonObject { put("message", buildJsonObject { put("body", body ?: JsonNull) }) }

    private fun subtitleList(subtitleBody: String) =
        buildJsonObject {
            put(
                "subtitle_list",
                kotlinx.serialization.json.JsonArray(
                    listOf(buildJsonObject { put("subtitle", buildJsonObject { put("subtitle_body", JsonPrimitive(subtitleBody)) }) }),
                ),
            )
        }

    private fun richsyncBody(richsyncBody: String) = buildJsonObject { put("richsync", buildJsonObject { put("richsync_body", richsyncBody) }) }

    private companion object {
        const val SUBTITLES = """[{"text":"Hello","time":{"total":12.34}},{"text":"World","time":{"total":15.0}}]"""
        const val RICHSYNC = """[{"ts":1.0,"te":3.0,"l":[{"c":"Hel","o":0.0},{"c":"lo","o":0.5}]}]"""
    }
}
