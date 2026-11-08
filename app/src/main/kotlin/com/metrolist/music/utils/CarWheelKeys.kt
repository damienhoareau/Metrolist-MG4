/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.metrolist.music.extensions.togglePlayPause
import com.metrolist.music.playback.MusicService
import timber.log.Timber

/**
 * Drives the player from the MG4 steering-wheel keys the way Amazon Music's HardKeyManager does. The
 * head unit reports them as a broadcast that only its own media apps listen to, so the receiver is
 * registered from [com.metrolist.music.App] and acts on [MusicService] while it runs.
 *
 * Like Amazon, the media codes react on key release only and ignore long presses. The 299-301 wheel
 * codes, which this car reports on key press (as observed by the DiAuto project), also drive the
 * player unless the wheel is set to control the instrument cluster menus.
 */
object CarWheelKeys {
    private const val TAG = "CarWheelKeys"
    private const val ACTION_HARDKEY = "com.saic.keyevent.hardkey.report"
    private const val EXTRA_KEYCODE = "android.intent.extra.hardkey.keycode"
    private const val EXTRA_DOWN = "android.intent.extra.hardkey.down"
    private const val EXTRA_LONGPRESS = "android.intent.extra.hardkey.longpress"

    private const val WHEEL_LEFT = 299
    private const val WHEEL_RIGHT = 300
    private const val WHEEL_OK = 301

    private const val INSTRUMENT_CLUSTER_SERVICE = "vendorinstrumentcluster"
    private const val PROP_SWC_FUNCTION_CHANGE_SWA = 0x21407B14

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Main thread only.
    private var service: MusicService? = null

    /** Registered for the whole life of the process, as Amazon Music does from its Application. */
    fun init(context: Context) {
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) = onKey(intent)
            },
            IntentFilter(ACTION_HARDKEY),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    fun attach(service: MusicService) {
        this.service = service
    }

    fun detach(service: MusicService) {
        if (this.service === service) this.service = null
    }

    private fun onKey(intent: Intent) {
        val keyCode = intent.getIntExtra(EXTRA_KEYCODE, 0)
        val isDown = intent.getBooleanExtra(EXTRA_DOWN, false)
        val isLong = intent.getBooleanExtra(EXTRA_LONGPRESS, false)
        Timber.tag(TAG).i("key %d down=%b long=%b", keyCode, isDown, isLong)
        val owner = service ?: return
        if (keyCode in WHEEL_LEFT..WHEEL_OK) {
            if (isDown && !isLong) onWheelKey(owner, keyCode)
            return
        }
        // Amazon's canPlay: the keys only act while the app holds the audio focus, i.e. is the car's current source.
        if (isDown || isLong || !owner.hasAudioFocus || !owner.isPlayerReady.value) return

        val player = owner.player
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> player.play()
            KeyEvent.KEYCODE_MEDIA_NEXT -> player.seekToNext()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> player.seekToPrevious()
            KeyEvent.KEYCODE_VOLUME_MUTE -> player.togglePlayPause()
        }
    }

    private fun onWheelKey(
        owner: MusicService,
        keyCode: Int,
    ) {
        scope.launch {
            // The wheel drives the instrument cluster menus unless its function is 0.
            val function = withContext(Dispatchers.IO) { wheelFunction(owner) }
            if (function != 0) {
                Timber.tag(TAG).i("key %d left to the cluster, wheel function %s", keyCode, function)
                return@launch
            }
            if (!owner.isPlayerReady.value) return@launch
            val player = owner.player
            when (keyCode) {
                WHEEL_LEFT -> player.seekToPrevious()
                WHEEL_RIGHT -> player.seekToNext()
                WHEEL_OK -> player.togglePlayPause()
            }
        }
    }

    private fun wheelFunction(context: Context): Int? =
        try {
            CarVoiceFocus
                .carManager(context, INSTRUMENT_CLUSTER_SERVICE)
                ?.let { manager ->
                    manager.javaClass
                        .getMethod("getGlobalProperty", Class::class.java, Int::class.javaPrimitiveType)
                        .invoke(manager, Int::class.javaObjectType, PROP_SWC_FUNCTION_CHANGE_SWA) as? Int
                }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "steering wheel function unreadable")
            null
        }
}
