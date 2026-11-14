/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyLyricsApiTest {
    @Test
    fun `line synced lyrics are converted to lrc`() {
        val result =
            SpotifyLyricsApi.lyricsToLrc(
                """{"lyrics":{"syncType":"LINE_SYNCED","lines":[{"startTimeMs":"0","words":"First line"},{"startTimeMs":"1250","words":"Second line"}]}}""",
            )

        assertEquals("[00:00.00]First line\n[00:01.25]Second line", result)
    }

    @Test
    fun `unsynced lyrics remain plain text`() {
        val result =
            SpotifyLyricsApi.lyricsToLrc(
                """{"lyrics":{"syncType":"UNSYNCED","lines":[{"words":"First line"},{"words":"Second line"}]}}""",
            )

        assertEquals("First line\nSecond line", result)
    }
}