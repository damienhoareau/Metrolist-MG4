/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import timber.log.Timber

/**
 * Tells the head unit that Metrolist is the online music source, the way its stock apps tag the
 * Bluetooth and USB sources: the car's audio policy reads `key_car_source_type` from the focus
 * request, and the launcher, status card and cluster then show the media session of that source.
 * Both calls are vendor additions to the platform, reached by reflection, so every failure leaves
 * playback untouched. Only active once the user has opened Metrolist, like [CarWheelKeys].
 */
object CarMediaSource {
    private const val TAG = "CarMediaSource"
    private const val KEY_CAR_SOURCE_TYPE = "key_car_source_type"

    /** `SOURCE_TYPE_ONLINE_MUSIC` of the car's media service. */
    private const val SOURCE_ONLINE_MUSIC = 14

    @Volatile
    var enabled = false
        private set

    /** Called when the user opens the app. */
    fun enable() {
        enabled = true
    }

    /** Adds the source tag to [builder] when enabled and the vendor API exists. */
    fun tag(builder: AudioAttributes.Builder): AudioAttributes.Builder {
        if (!enabled) return builder
        return try {
            val bundle = Bundle().apply { putInt(KEY_CAR_SOURCE_TYPE, SOURCE_ONLINE_MUSIC) }
            builder.javaClass.getMethod("addBundle", Bundle::class.java).invoke(builder, bundle)
            builder
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "AudioAttributes.addBundle unavailable")
            builder
        }
    }

    /** Declares the source after the focus was granted, and logs what the car makes of it. */
    fun claim(context: Context) {
        if (!enabled) return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val before = describe(audioManager)
        val set =
            runCatching {
                audioManager.javaClass
                    .getMethod("setLastSource", Int::class.javaPrimitiveType)
                    .invoke(audioManager, SOURCE_ONLINE_MUSIC)
            }
        Timber.tag(TAG).i("claim online music: setLastSource=%s; before: %s; after: %s", if (set.isSuccess) "ok" else set.exceptionOrNull(), before, describe(audioManager))
    }

    private fun describe(audioManager: AudioManager): String {
        val last = runCatching { audioManager.javaClass.getMethod("getLastSource").invoke(audioManager) }
        val current =
            runCatching {
                val attributes = audioManager.javaClass.getMethod("getCurrentSource").invoke(audioManager)
                (attributes?.javaClass?.getMethod("getBundle")?.invoke(attributes) as? Bundle)
                    ?.takeIf { it.containsKey(KEY_CAR_SOURCE_TYPE) }
                    ?.getInt(KEY_CAR_SOURCE_TYPE)
            }
        return "lastSource=${last.getOrNull() ?: "n/a"} currentSource=${current.getOrNull() ?: "n/a"}"
    }
}
