/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import com.metrolist.music.extensions.togglePlayPause
import com.metrolist.music.playback.MusicService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Drives the player from the MG4 steering-wheel keys. The head unit reports them as a broadcast that
 * only its own media apps listen to, so the receiver is registered here while [MusicService] runs,
 * but only once the user has opened Metrolist (the stock music app starts the service on its own).
 *
 * Wheel codes follow the DiAuto project, which observed them on the car. Next, previous and mute (87,
 * 88, 164) are logged only: the car's media layer already carries them to the current audio source.
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

    // Main thread only.
    private var service: MusicService? = null
    private var enabled = false
    private var receiver: BroadcastReceiver? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Called when the user opens the app. */
    fun enable() {
        enabled = true
        sync()
    }

    fun attach(service: MusicService) {
        this.service = service
        sync()
    }

    fun detach(service: MusicService) {
        if (this.service !== service) return
        sync(active = false)
        this.service = null
    }

    private fun sync(active: Boolean = enabled && service != null) {
        val owner = service ?: return
        if (active && receiver == null) {
            val newReceiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) = onKey(owner, intent)
                }
            ContextCompat.registerReceiver(
                owner,
                newReceiver,
                IntentFilter(ACTION_HARDKEY),
                ContextCompat.RECEIVER_EXPORTED,
            )
            receiver = newReceiver
            Timber.tag(TAG).i("wheel keys on; %s", audioSources(owner))
            // The first car connection takes longer than a key press should wait.
            scope.launch(Dispatchers.IO) { wheelFunction(owner) }
        } else if (!active) {
            receiver?.let { runCatching { owner.unregisterReceiver(it) } }
            receiver = null
        }
    }

    private fun onKey(
        owner: MusicService,
        intent: Intent,
    ) {
        val keyCode = intent.getIntExtra(EXTRA_KEYCODE, -1)
        val isDown = intent.getBooleanExtra(EXTRA_DOWN, false)
        val isLong = intent.getBooleanExtra(EXTRA_LONGPRESS, false)
        Timber.tag(TAG).i("key %d down=%b long=%b; %s", keyCode, isDown, isLong, audioSources(owner))
        if (!isDown || isLong || keyCode !in WHEEL_LEFT..WHEEL_OK) return

        scope.launch {
            // The wheel drives the instrument cluster menus unless its function is 0.
            val function = withContext(Dispatchers.IO) { wheelFunction(owner) }
            if (function != 0) {
                Timber.tag(TAG).i("key %d left to the cluster, wheel function %s", keyCode, function)
                return@launch
            }
            if (!owner.isPlayerReady.value) return@launch
            Timber.tag(TAG).i("key %d drives the player", keyCode)
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

    // Vendor additions to AudioManager that say which source the car thinks is playing.
    private fun audioSources(context: Context): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val last = runCatching { audioManager.javaClass.getMethod("getLastSource").invoke(audioManager) }
        return "lastSource=${last.getOrNull() ?: "n/a"}"
    }
}
