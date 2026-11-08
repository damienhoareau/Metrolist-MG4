/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.musixmatch.Musixmatch

/** Uses a short-lived token that [Musixmatch] fetches and renews by itself. */
object MusixmatchLyricsProvider : LyricsProvider {
    override val name = "Musixmatch"

    override fun isEnabled(context: Context): Boolean = true

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = Musixmatch.getLyrics(title, artist, duration, album)
}
