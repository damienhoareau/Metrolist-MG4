/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpotifyLyricsLiveTest {
    @Test
    fun `Spotify returns lyrics for a known track`() {
        val cookie = findCookieFile()?.readText()?.trim().orEmpty()
        assumeTrue("Create spotify_cookie.txt in the repository root to run the live Spotify test", cookie.isNotBlank())

        val result = runBlocking {
            SpotifyLyricsApi.getLyrics(cookie, "Never Gonna Give You Up", "Rick Astley")
        }

        assertTrue("Spotify lyrics request failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertTrue(result.getOrThrow().isNotBlank())
    }

    private fun findCookieFile(): File? {
        var directory: File? = File(System.getProperty("user.dir"))
        repeat(8) {
            val candidate = directory?.resolve("spotify_cookie.txt") ?: return null
            if (candidate.isFile) return candidate
            directory = directory?.parentFile
        }
        return null
    }
}