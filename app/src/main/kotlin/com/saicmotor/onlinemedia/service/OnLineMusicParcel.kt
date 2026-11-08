/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.saicmotor.onlinemedia.service

import android.os.Parcel
import com.metrolist.music.models.MediaMetadata

/**
 * Writes a track the way the head unit's `com.saicmotor.onlinemedia.modle.OnLineMusic` reads it.
 * Only the title, artist, cover and duration are consumed; the other fields keep the order the
 * reader expects so it never goes out of step.
 */
internal fun Parcel.writeOnLineMusic(metadata: MediaMetadata) {
    val cover = metadata.thumbnailUrl.orEmpty()
    writeByte(0) // no database id
    writeString("") // music url
    writeString(metadata.artists.joinToString { it.name })
    writeString("") // artist url
    writeString("") // artist image
    writeString(metadata.title)
    writeString("") // track url
    writeLong(metadata.duration * 1000L)
    writeString("") // formatted time
    writeString(cover) // image url, used by onMusicChange
    writeString(metadata.album?.title.orEmpty())
    writeString(cover) // album url, used as the cover by getOnLineMusic
    writeInt(0)
    writeString("")
    writeInt(0)
    writeLong(System.currentTimeMillis())
    writeString("")
    writeString("")
}
