/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.music.BuildConfig
import com.metrolist.musixmatch.Musixmatch

/** Only active when a user token is set as `MUSIXMATCH_TOKEN` in `local.properties`. */
object MusixmatchLyricsProvider : LyricsProvider {
    override val name = "Musixmatch"

    val configured: Boolean get() = BuildConfig.MUSIXMATCH_TOKEN.isNotBlank()

    override fun isEnabled(context: Context): Boolean = configured

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = Musixmatch.getLyrics(BuildConfig.MUSIXMATCH_TOKEN, title, artist, duration, album)
}
