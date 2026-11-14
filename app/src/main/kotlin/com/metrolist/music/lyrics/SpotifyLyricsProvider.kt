/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.music.constants.EnableSpotifyLyricsKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get

object SpotifyLyricsProvider : LyricsProvider {
    override val name = "Spotify"

    override fun isEnabled(context: Context): Boolean =
        (context.dataStore[EnableSpotifyLyricsKey] ?: false) && SpotifySessionCookieStore.isConfigured(context)

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> {
        val cookie = SpotifySessionCookieStore.retrieve(context)
            ?: return Result.failure(IllegalStateException("Spotify session cookie is not configured"))
        return SpotifyLyricsApi.getLyrics(cookie, title, artist)
    }
}