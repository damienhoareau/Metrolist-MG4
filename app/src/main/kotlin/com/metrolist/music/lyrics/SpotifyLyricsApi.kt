/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.util.Base64
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.accept
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SpotifyLyricsApi {
    private const val SPOTIFY_HOME = "https://open.spotify.com/"
    private const val TOKEN_URL = "https://open.spotify.com/api/token"
    private const val CLIENT_TOKEN_URL = "https://clienttoken.spotify.com/v1/clienttoken"
    private const val PARTNER_API_URL = "https://api-partner.spotify.com/pathfinder/v2/query"
    private const val LYRICS_URL = "https://spclient.wg.spotify.com/color-lyrics/v2/track/"
    private const val TOTP_SECRET_URL = "https://code.thetadev.de/ThetaDev/spotify-secrets/raw/branch/main/secrets/secretDict.json"
    private const val SEARCH_HASH = "b50ebd72524415b132ddaca04158fd7aca529da28be322c9924643c0633df5bd"
    private const val FALLBACK_CLIENT_VERSION = "1.2.46.25.g7f189073"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true }
    private val authMutex = Mutex()
    private val secretMutex = Mutex()
    private val client =
        HttpClient(OkHttp) {
            expectSuccess = false
            defaultRequest {
                header(HttpHeaders.UserAgent, USER_AGENT)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 15_000
            }
        }

    @Volatile private var cachedAuth: CachedAuth? = null
    @Volatile private var cachedTotpSecret: TotpSecret? = null

    suspend fun getLyrics(
        cookie: String,
        title: String,
        artist: String,
    ): Result<String> =
        try {
            Result.success(fetchLyrics(cookie, title, artist))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    internal fun lyricsToLrc(payload: String): String {
        val lyrics = json.parseToJsonElement(payload).jsonObject["lyrics"]?.jsonObject
            ?: throw IllegalStateException("Spotify returned no lyrics")
        val lines = lyrics["lines"]?.jsonArray ?: throw IllegalStateException("Spotify returned no lyric lines")
        val isSynced = lyrics["syncType"]?.jsonPrimitive?.contentOrNull == "LINE_SYNCED"
        val rendered =
            lines.mapNotNull { element ->
                val line = element.jsonObject
                val words = line["words"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (words.isBlank()) return@mapNotNull null
                if (!isSynced) return@mapNotNull words
                val startTime = line["startTimeMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    ?: return@mapNotNull words
                "[${formatTimestamp(startTime)}]$words"
            }
        return rendered.takeIf { it.isNotEmpty() }?.joinToString("\n")
            ?: throw IllegalStateException("Spotify returned empty lyrics")
    }

    internal fun formatTimestamp(milliseconds: Long): String {
        val centiseconds = milliseconds.coerceAtLeast(0L) / 10
        val minutes = centiseconds / 6_000
        val seconds = centiseconds / 100 % 60
        val fraction = centiseconds % 100
        return String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, fraction)
    }

    fun clearSession() {
        cachedAuth = null
    }

    private suspend fun fetchLyrics(
        cookie: String,
        title: String,
        artist: String,
    ): String {
        repeat(2) { attempt ->
            val auth = getAuth(cookie)
            try {
                val trackId = searchTrack(auth, title, artist)
                val response =
                    client.get(LYRICS_URL + trackId) {
                        header(HttpHeaders.Authorization, "Bearer ${auth.accessToken}")
                        header("App-Platform", "WebPlayer")
                        accept(ContentType.Application.Json)
                        parameter("format", "json")
                        parameter("market", "from_token")
                    }
                if (response.status == HttpStatusCode.Unauthorized) throw AuthExpiredException()
                if (response.status != HttpStatusCode.OK) {
                    throw IllegalStateException("Spotify lyrics request failed: HTTP ${response.status.value}")
                }
                return lyricsToLrc(response.bodyAsText())
            } catch (e: AuthExpiredException) {
                cachedAuth = null
                if (attempt == 1) throw e
            }
        }
        throw IllegalStateException("Spotify authentication failed")
    }

    private suspend fun getAuth(cookie: String): CachedAuth =
        authMutex.withLock {
            cachedAuth?.takeIf { it.cookie == cookie && System.currentTimeMillis() < it.expiresAt }?.let {
                return@withLock it
            }
            createAuth(cookie).also { cachedAuth = it }
        }

    private suspend fun createAuth(cookie: String): CachedAuth {
        val home =
            client.get(SPOTIFY_HOME) {
                cookie("sp_dc", cookie)
                accept(ContentType.Text.Html)
            }
        if (home.status != HttpStatusCode.OK) {
            throw IllegalStateException("Spotify session request failed: HTTP ${home.status.value}")
        }
        val version = extractClientVersion(home.bodyAsText())
        var deviceId = cookieValue(home.headers, "sp_t") ?: UUID.randomUUID().toString()
        val secret = getTotpSecret()
        val totp = generateTotp(secret.secret, System.currentTimeMillis())
        val accessResponse =
            client.get(TOKEN_URL) {
                cookie("sp_dc", cookie)
                accept(ContentType.Application.Json)
                parameter("reason", "init")
                parameter("productType", "web-player")
                parameter("totp", totp)
                parameter("totpVer", secret.version)
                parameter("totpServer", totp)
            }
        if (accessResponse.status != HttpStatusCode.OK) {
            throw IllegalStateException("Spotify authentication failed: HTTP ${accessResponse.status.value}")
        }
        val accessJson = json.parseToJsonElement(accessResponse.bodyAsText()).jsonObject
        val accessToken = accessJson["accessToken"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("Spotify did not return an access token")
        val clientId = accessJson["clientId"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("Spotify did not return a client id")
        deviceId = cookieValue(accessResponse.headers, "sp_t") ?: deviceId
        val clientToken = getClientToken(version, clientId, deviceId)
        val expiresAt = accessJson["accessTokenExpirationTimestampMs"]?.jsonPrimitive?.longOrNull
            ?: System.currentTimeMillis() + 30 * 60 * 1000L
        return CachedAuth(cookie, accessToken, clientToken, version, expiresAt)
    }

    private suspend fun getClientToken(
        version: String,
        clientId: String,
        deviceId: String,
    ): String {
        val body =
            buildJsonObject {
                putJsonObject("client_data") {
                    put("client_version", version)
                    put("client_id", clientId)
                    putJsonObject("js_sdk_data") {
                        put("device_brand", "unknown")
                        put("device_model", "unknown")
                        put("os", "windows")
                        put("os_version", "NT 10.0")
                        put("device_id", deviceId)
                        put("device_type", "computer")
                    }
                }
            }
        val response =
            client.post(CLIENT_TOKEN_URL) {
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                setBody(body.toString())
            }
        if (response.status != HttpStatusCode.OK) {
            throw IllegalStateException("Spotify client token request failed: HTTP ${response.status.value}")
        }
        val responseJson = json.parseToJsonElement(response.bodyAsText()).jsonObject
        if (responseJson["response_type"]?.jsonPrimitive?.contentOrNull != "RESPONSE_GRANTED_TOKEN_RESPONSE") {
            throw IllegalStateException("Spotify did not grant a client token")
        }
        return responseJson["granted_token"]?.jsonObject?.get("token")?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("Spotify did not return a client token")
    }

    private suspend fun searchTrack(
        auth: CachedAuth,
        title: String,
        artist: String,
    ): String {
        val query = "track:\"${title.replace("\"", "\\\"")}\" artist:\"${artist.replace("\"", "\\\"")}\""
        val variables =
            buildJsonObject {
                put("query", query)
                put("limit", 5)
                put("offset", 0)
                put("includeAuthors", false)
                put("includeAlbumPreReleases", false)
            }
        val payload =
            buildJsonObject {
                put("operationName", "searchSuggestions")
                put("variables", variables)
                putJsonObject("extensions") {
                    putJsonObject("persistedQuery") {
                        put("version", 1)
                        put("sha256Hash", SEARCH_HASH)
                    }
                }
            }
        val response =
            client.post(PARTNER_API_URL) {
                header(HttpHeaders.Authorization, "Bearer ${auth.accessToken}")
                header("Client-Token", auth.clientToken)
                header("Spotify-App-Version", auth.clientVersion)
                header(HttpHeaders.Origin, "https://open.spotify.com")
                header(HttpHeaders.Referrer, "https://open.spotify.com/")
                header(HttpHeaders.AcceptLanguage, "en")
                header("App-Platform", "WebPlayer")
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                setBody(payload.toString())
            }
        if (response.status == HttpStatusCode.Unauthorized || response.status.value == 412) {
            throw AuthExpiredException()
        }
        if (response.status != HttpStatusCode.OK) {
            throw IllegalStateException("Spotify track search failed: HTTP ${response.status.value}")
        }
        val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val items =
            root["data"]?.jsonObject
                ?.get("searchV2")?.jsonObject
                ?.get("topResultsV2")?.jsonObject
                ?.get("itemsV2") as? JsonArray
                ?: JsonArray(emptyList())
        return items.firstNotNullOfOrNull { item ->
            val track = item.jsonObject["item"]?.jsonObject?.get("data")?.jsonObject ?: return@firstNotNullOfOrNull null
            if (track["__typename"]?.jsonPrimitive?.contentOrNull != "Track") return@firstNotNullOfOrNull null
            track["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        } ?: throw IllegalStateException("Spotify found no matching track")
    }

    private fun extractClientVersion(html: String): String {
        val encoded = Regex("""<script id="appServerConfig" type="text/plain">([^<]+)</script>""")
            .find(html)?.groupValues?.getOrNull(1)
        val decoded = encoded?.let {
            runCatching { String(Base64.decode(it, Base64.DEFAULT), StandardCharsets.UTF_8) }.getOrNull()
        }
        val version = decoded?.let {
            runCatching { json.parseToJsonElement(it).jsonObject["clientVersion"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        }
        return version?.takeIf { it.isNotBlank() } ?: FALLBACK_CLIENT_VERSION
    }

    private fun cookieValue(
        headers: Headers,
        name: String,
    ): String? =
        headers.getAll(HttpHeaders.SetCookie).orEmpty().firstNotNullOfOrNull { header ->
            header.substringBefore(';').trim().takeIf { it.startsWith("$name=") }?.substringAfter('=')
        }

    private suspend fun getTotpSecret(): TotpSecret =
        secretMutex.withLock {
            cachedTotpSecret?.let { return@withLock it }
            val response = client.get(TOTP_SECRET_URL)
            if (response.status != HttpStatusCode.OK) {
                throw IllegalStateException("Could not load Spotify authentication data")
            }
            val data = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val version = data.keys.lastOrNull() ?: throw IllegalStateException("Spotify authentication data is empty")
            val encoded = data[version]?.jsonArray ?: throw IllegalStateException("Spotify authentication data is invalid")
            val secret =
                encoded.mapIndexed { index, value ->
                    value.jsonPrimitive.int xor ((index % 33) + 9)
                }.joinToString("").toByteArray(StandardCharsets.UTF_8)
            TotpSecret(secret, version).also { cachedTotpSecret = it }
        }

    private fun generateTotp(
        secret: ByteArray,
        timestamp: Long,
    ): String {
        val counter = timestamp / 1000 / 30
        val counterBytes = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(counter).array()
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val digest = mac.doFinal(counterBytes)
        val offset = digest.last().toInt() and 0x0f
        val binary =
            ((digest[offset].toInt() and 0x7f) shl 24) or
                ((digest[offset + 1].toInt() and 0xff) shl 16) or
                ((digest[offset + 2].toInt() and 0xff) shl 8) or
                (digest[offset + 3].toInt() and 0xff)
        return String.format(Locale.US, "%06d", binary % 1_000_000)
    }

    private data class CachedAuth(
        val cookie: String,
        val accessToken: String,
        val clientToken: String,
        val clientVersion: String,
        val expiresAt: Long,
    )

    private data class TotpSecret(
        val secret: ByteArray,
        val version: String,
    )

    private class AuthExpiredException : Exception()
}