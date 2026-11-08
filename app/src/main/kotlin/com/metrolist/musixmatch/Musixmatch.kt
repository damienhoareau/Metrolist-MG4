/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.musixmatch

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Musixmatch lyrics through the API its desktop client uses. The short-lived desktop token is fetched
 * on demand and kept in memory for ten minutes. The app only ever presents itself as Metrolist, so
 * when Musixmatch answers with a placeholder token the provider stays out of the way. Word-by-word
 * "richsync" lyrics are converted to Metrolist's extended LRC; songs without them fall back to
 * line-synced subtitles.
 */
object Musixmatch {
    private const val ENDPOINT = "https://apic-desktop.musixmatch.com/ws/1.1/macro.subtitles.get"
    private const val APP_ID = "web-desktop-app-v1.0"
    private const val TOKEN_ENDPOINT = "https://apic-desktop.musixmatch.com/ws/1.1/token.get"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val TOKEN_TTL_MILLIS = 10 * 60 * 1000L
    private const val TOKEN_RETRY_BACKOFF_MILLIS = 5 * 60 * 1000L
    private const val STATUS_OK = 200
    private const val STATUS_TOKEN_REJECTED = 401

    private val json = Json { ignoreUnknownKeys = true }

    private val tokenMutex = Mutex()

    @Volatile private var cachedToken: String? = null

    @Volatile private var tokenExpiresAtMillis = 0L

    @Volatile private var tokenRetryAtMillis = 0L

    internal var clock: () -> Long = System::currentTimeMillis

    private class TokenRejectedException : Exception("Musixmatch rejected the token")

    // Replaceable so the token logic can be tested against a fake server.
    internal var client: HttpClient =
        HttpClient {
            expectSuccess = true
            install(HttpTimeout) {
                requestTimeoutMillis = 7_000
                connectTimeoutMillis = 5_000
            }
        }

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> =
        runCatching {
            val calls =
                try {
                    macroCalls(getToken(), title, artist, duration, album)
                } catch (_: TokenRejectedException) {
                    invalidateToken()
                    macroCalls(getToken(), title, artist, duration, album)
                }
            richsync(calls)?.takeIf { it.isNotBlank() }
                ?: subtitles(calls)?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("No Musixmatch synced lyrics for $title")
        }

    private suspend fun getToken(): String =
        tokenMutex.withLock {
            val now = clock()
            cachedToken?.takeIf { now < tokenExpiresAtMillis }?.let { return@withLock it }
            if (now < tokenRetryAtMillis) throw IllegalStateException("Musixmatch token unavailable, retrying later")

            val token =
                try {
                    requestToken()
                } catch (e: Exception) {
                    tokenRetryAtMillis = now + TOKEN_RETRY_BACKOFF_MILLIS
                    throw e
                }
            cachedToken = token
            tokenExpiresAtMillis = now + TOKEN_TTL_MILLIS
            token
        }

    internal fun resetTokenState() {
        cachedToken = null
        tokenExpiresAtMillis = 0L
        tokenRetryAtMillis = 0L
    }

    private fun invalidateToken() {
        cachedToken = null
        tokenExpiresAtMillis = 0L
    }

    private suspend fun requestToken(): String {
        val response =
            client
                .get(TOKEN_ENDPOINT) {
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    parameter("format", "json")
                    parameter("app_id", APP_ID)
                    parameter("guid", java.util.UUID.randomUUID().toString())
                    parameter("user_language", "en")
                }.body<String>()
        val message = json.parseToJsonElement(response).jsonObject["message"]?.jsonObject
        val status = message?.get("header")?.jsonObject?.get("status_code")?.jsonPrimitive?.intOrNull
        if (status != STATUS_OK) throw IllegalStateException("Musixmatch token request failed with status $status")

        val token =
            (message["body"] as? JsonObject)
                ?.get("user_token")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Musixmatch did not return a token")
        // A run of zeros is the placeholder Musixmatch hands to clients it does not serve.
        if (token.all { it == '0' }) throw IllegalStateException("Musixmatch returned a placeholder token")
        return token
    }

    private suspend fun macroCalls(
        token: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): JsonObject {
        val response =
            try {
                client
                    .get(ENDPOINT) {
                        header(HttpHeaders.UserAgent, USER_AGENT)
                        parameter("format", "json")
                        parameter("namespace", "lyrics_richsynched")
                        parameter("subtitle_format", "mxm")
                        parameter("optional_calls", "track.richsync")
                        parameter("app_id", APP_ID)
                        parameter("q_track", title)
                        parameter("q_artist", artist)
                        album?.let { parameter("q_album", it) }
                        if (duration > 0) parameter("q_duration", duration)
                        parameter("usertoken", token)
                    }.body<String>()
            } catch (e: ClientRequestException) {
                val code = e.response.status.value
                if (code == 401 || code == 403) throw TokenRejectedException()
                throw e
            }
        val message = json.parseToJsonElement(response).jsonObject["message"]?.jsonObject
        val status = message?.get("header")?.jsonObject?.get("status_code")?.jsonPrimitive?.intOrNull
        if (status == STATUS_TOKEN_REJECTED) throw TokenRejectedException()
        return (message?.get("body") as? JsonObject)?.get("macro_calls")?.jsonObject
            ?: throw IllegalStateException("Musixmatch refused the request (status $status)")
    }

    private fun body(
        calls: JsonObject,
        name: String,
    ): JsonObject? =
        (calls[name] as? JsonObject)
            ?.get("message")
            ?.jsonObject
            ?.get("body") as? JsonObject

    /** `[{"ts":start,"te":end,"l":[{"c":text,"o":offsetFromStart}]}]`, all in seconds. */
    private fun richsync(calls: JsonObject): String? {
        val raw =
            body(calls, "track.richsync.get")
                ?.get("richsync")
                ?.jsonObject
                ?.get("richsync_body")
                ?.jsonPrimitive
                ?.contentOrNull ?: return null
        val lines = json.parseToJsonElement(raw).jsonArray
        return buildString {
            for (line in lines) {
                val obj = line.jsonObject
                val start = obj.number("ts") ?: continue
                val end = obj.number("te") ?: continue
                val units =
                    (obj["l"] as? JsonArray).orEmpty().mapNotNull {
                        val text = it.jsonObject["c"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                        val offset = it.jsonObject.number("o") ?: return@mapNotNull null
                        if (text.isEmpty()) null else text.replace('|', '/') to start + offset
                    }
                if (units.isEmpty()) continue
                append(formatTime(start)).append(units.joinToString(" ") { it.first }).append('\n')
                append('<')
                append(
                    units.mapIndexed { index, unit ->
                        "${unit.first}:${unit.second}:${units.getOrNull(index + 1)?.second ?: end}"
                    }.joinToString("|"),
                )
                append(">\n")
            }
        }.trim()
    }

    /** `[{"text":"...","time":{"total":seconds}}]`. */
    private fun subtitles(calls: JsonObject): String? {
        val raw =
            body(calls, "track.subtitles.get")
                ?.get("subtitle_list")
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("subtitle")
                ?.jsonObject
                ?.get("subtitle_body")
                ?.jsonPrimitive
                ?.contentOrNull ?: return null
        return buildString {
            for (line in json.parseToJsonElement(raw).jsonArray) {
                val time = line.jsonObject["time"]?.jsonObject?.number("total") ?: continue
                append(formatTime(time)).append(line.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty()).append('\n')
            }
        }.trim()
    }

    private fun JsonObject.number(key: String): Double? =
        (get(key) as? JsonElement)?.jsonPrimitive?.let { it.doubleOrNull ?: it.intOrNull?.toDouble() }

    private fun formatTime(seconds: Double): String {
        val centis = (seconds * 100).toLong()
        return "[%02d:%02d.%02d]".format(centis / 6000, centis / 100 % 60, centis % 100)
    }
}
