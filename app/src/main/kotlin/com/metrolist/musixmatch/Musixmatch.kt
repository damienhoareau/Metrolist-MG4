/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.musixmatch

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
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
 * Musixmatch lyrics through the API its desktop client uses, with a user token the user supplies
 * (never obtained by the app). Word-by-word "richsync" lyrics are converted to Metrolist's extended
 * LRC; songs without them fall back to line-synced subtitles.
 */
object Musixmatch {
    private const val ENDPOINT = "https://apic-desktop.musixmatch.com/ws/1.1/macro.subtitles.get"
    private const val APP_ID = "web-desktop-app-v1.0"
    private const val USER_AGENT = "Metrolist"

    private val json = Json { ignoreUnknownKeys = true }

    private val client =
        HttpClient {
            expectSuccess = true
            install(HttpTimeout) {
                requestTimeoutMillis = 7_000
                connectTimeoutMillis = 5_000
            }
        }

    suspend fun getLyrics(
        token: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> =
        runCatching {
            val response =
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
            val calls =
                json
                    .parseToJsonElement(response)
                    .jsonObject["message"]
                    ?.jsonObject
                    ?.get("body")
                    ?.let { it as? JsonObject }
                    ?.get("macro_calls")
                    ?.jsonObject ?: throw IllegalStateException("Musixmatch refused the request")

            richsync(calls)?.takeIf { it.isNotBlank() }
                ?: subtitles(calls)?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("No Musixmatch synced lyrics for $title")
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
