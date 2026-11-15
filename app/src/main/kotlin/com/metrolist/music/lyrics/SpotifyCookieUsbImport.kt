/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import java.io.File

/** Reads the Spotify session cookie from a text file at the root of a USB key, to spare typing it on a head unit. */
object SpotifyCookieUsbImport {
    const val FILE_NAME = "spotify_sp_dc.txt"
    private const val MAX_FILE_BYTES = 4096L

    // Removable volumes show up under /storage; platform-signed builds can also read the raw mount point.
    private val volumeParents = listOf("/storage", "/mnt/media_rw")

    fun read(): String? =
        volumeParents
            .flatMap { File(it).listFiles().orEmpty().toList() }
            .filter { it.isDirectory && it.name != "emulated" && it.name != "self" }
            .firstNotNullOfOrNull { volume ->
                runCatching {
                    File(volume, FILE_NAME)
                        .takeIf { it.isFile && it.length() in 1..MAX_FILE_BYTES }
                        ?.readText()
                        ?.trim()
                        ?.removePrefix("sp_dc=")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                }.getOrNull()
            }
}
