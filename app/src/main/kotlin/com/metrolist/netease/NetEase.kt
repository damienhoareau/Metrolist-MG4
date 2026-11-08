/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.netease

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

/**
 * Lyrics from NetEase Cloud Music's public web endpoints. Word-by-word timings (YRC) are converted
 * to Metrolist's extended LRC; songs without them fall back to plain line-synced LRC.
 */
object NetEase {
    private const val BASE_URL = "https://music.163.com/api"
    private const val USER_AGENT = "Metrolist"
    private const val SEARCH_LIMIT = 10
    private const val MAX_CANDIDATES = 4
    private const val DURATION_TOLERANCE_SECONDS = 8

    private val LRC_LINE = Regex("""\[\d+:\d+(\.\d+)?]\s*\S""")
    private val YRC_LINE = Regex("""\[(\d+),(\d+)](.*)""")
    private val YRC_WORD = Regex("""\((\d+),(\d+),-?\d+\)""")
    private val CREDIT = Regex("""^(作曲|作词|作詞|编曲|編曲|制作人|制作|监制|混音|录音|母带|和声|吉他|贝斯|鼓|弦乐|策划|出品|发行|词|曲)\s*[:：]""")

    private val json = Json { ignoreUnknownKeys = true }

    private val client =
        HttpClient {
            expectSuccess = true
            install(HttpTimeout) {
                requestTimeoutMillis = 7_000
                connectTimeoutMillis = 5_000
            }
        }

    private data class Candidate(
        val id: Long,
        val title: String,
        val artists: List<String>,
    )

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Int,
    ): Result<String> =
        runCatching {
            candidates(title, artist, duration).firstNotNullOfOrNull { fetchLyrics(it.id) }
                ?: throw IllegalStateException("No NetEase lyrics for $title")
        }

    suspend fun getAllLyrics(
        title: String,
        artist: String,
        duration: Int,
        callback: (String) -> Unit,
    ) {
        runCatching {
            candidates(title, artist, duration).forEach { candidate ->
                fetchLyrics(candidate.id)?.let(callback)
            }
        }
    }

    private suspend fun candidates(
        title: String,
        artist: String,
        duration: Int,
    ): List<Candidate> {
        val response =
            client
                .submitForm(
                    url = "$BASE_URL/search/get",
                    formParameters =
                        parameters {
                            append("s", "$title $artist")
                            append("type", "1")
                            append("limit", SEARCH_LIMIT.toString())
                            append("offset", "0")
                        },
                ) { header(HttpHeaders.UserAgent, USER_AGENT) }
                .body<String>()
        val songs =
            json
                .parseToJsonElement(response)
                .jsonObject["result"]
                ?.jsonObject
                ?.get("songs")
                ?.jsonArray ?: return emptyList()

        val wantedTitle = normalize(title)
        val wantedArtists = artist.split(',', '&', '/').map(::normalize).filter { it.isNotEmpty() }
        fun Candidate.artistMatches() = wantedArtists.count { wanted -> artists.any { it.contains(wanted) || wanted.contains(it) } }

        return songs
            .mapNotNull { it.jsonObject.toCandidate(duration) }
            .filter { matchesTitle(normalize(it.title), wantedTitle) }
            .filter { wantedArtists.isEmpty() || it.artistMatches() > 0 }
            .sortedByDescending { it.artistMatches() }
            .take(MAX_CANDIDATES)
    }

    private fun JsonObject.toCandidate(duration: Int): Candidate? {
        val songDurationSeconds = (get("duration")?.jsonPrimitive?.int ?: return null) / 1000
        if (duration != -1 && abs(songDurationSeconds - duration) > DURATION_TOLERANCE_SECONDS) return null
        return Candidate(
            id = get("id")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return null,
            title = get("name")?.jsonPrimitive?.contentOrNull ?: return null,
            artists =
                (get("artists") as? JsonArray)
                    .orEmpty()
                    .mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                    .map(::normalize),
        )
    }

    private fun matchesTitle(
        found: String,
        wanted: String,
    ) = found.isNotEmpty() && wanted.isNotEmpty() && (found.contains(wanted) || wanted.contains(found))

    private fun normalize(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    private suspend fun fetchLyrics(id: Long): String? {
        val response =
            client
                .get("$BASE_URL/song/lyric") {
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    parameter("id", id)
                    parameter("lv", 1)
                    parameter("kv", 1)
                    parameter("tv", -1)
                    parameter("yv", 1)
                }.body<String>()
        val root = json.parseToJsonElement(response).jsonObject
        if (root["nolyric"]?.jsonPrimitive?.contentOrNull == "true") return null

        val yrc = root["yrc"]?.jsonObject?.get("lyric")?.jsonPrimitive?.contentOrNull
        yrc?.let(::yrcToExtendedLrc)?.takeIf { it.isNotBlank() }?.let { return it }

        val lrc = root["lrc"]?.jsonObject?.get("lyric")?.jsonPrimitive?.contentOrNull ?: return null
        return lrc
            .lineSequence()
            .filterNot { isCreditLine(it.substringAfter(']', it)) }
            .joinToString("\n")
            .trim()
            .takeIf { LRC_LINE.containsMatchIn(it) }
    }

    /** `[lineStart,lineDuration](wordStart,wordDuration,0)word...` to `[mm:ss.cc]line` + `<word:start:end|...>`. */
    private fun yrcToExtendedLrc(yrc: String): String =
        buildString {
            yrc.lineSequence().forEach { line ->
                val header = YRC_LINE.matchEntire(line.trim()) ?: return@forEach
                val body = header.groupValues[3]
                val markers = YRC_WORD.findAll(body).toList()
                val words =
                    markers.mapIndexedNotNull { index, marker ->
                        val textEnd = markers.getOrNull(index + 1)?.range?.first ?: body.length
                        val text = body.substring(marker.range.last + 1, textEnd).trim().replace('|', '/')
                        if (text.isEmpty()) return@mapIndexedNotNull null
                        val start = marker.groupValues[1].toLong()
                        Triple(text, start / 1000.0, (start + marker.groupValues[2].toLong()) / 1000.0)
                    }
                val text = words.joinToString(" ") { it.first }
                if (words.isEmpty() || isCreditLine(text)) return@forEach
                append(formatTime(header.groupValues[1].toLong())).append(text).append('\n')
                append('<').append(words.joinToString("|") { "${it.first}:${it.second}:${it.third}" }).append(">\n")
            }
        }.trim()

    private fun formatTime(ms: Long): String = "[%02d:%02d.%02d]".format(ms / 60000, ms / 1000 % 60, ms % 1000 / 10)

    private fun isCreditLine(text: String) = CREDIT.containsMatchIn(text.trim())
}
