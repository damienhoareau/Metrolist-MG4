/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.saicmotor.onlinemedia.service

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Parcel
import android.os.RemoteCallbackList
import android.os.RemoteException
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.metrolist.music.extensions.currentMetadata
import com.metrolist.music.extensions.togglePlayPause
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.MusicService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Stands in for the Amazon Music service the MG4 head unit binds to
 * (`com.saicmotor.service.media` -> `com.saicmotor.onlinemedia.service.PlayService`), exposing
 * Metrolist's player through the same hand-written binder contract.
 */
class PlayService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val callbacks = RemoteCallbackList<PlayEventListener>()

    private var player: Player? = null

    // Binder threads cannot touch the player, so they read these snapshots instead.
    @Volatile private var currentTrack: MediaMetadata? = null

    @Volatile private var playing = false

    private val pendingActions = mutableListOf<Player.() -> Unit>()

    private val binder = PlayBinder()

    private val playerListener =
        object : Player.Listener {
            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int,
            ) = publishTrack()

            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) = publishTrack()

            override fun onIsPlayingChanged(isPlaying: Boolean) = refreshPlaying()

            override fun onPlayWhenReadyChanged(
                playWhenReady: Boolean,
                reason: Int,
            ) = refreshPlaying()

            override fun onPlaybackStateChanged(playbackState: Int) = refreshPlaying()
        }

    private val progressTicker =
        object : Runnable {
            override fun run() {
                val position = player?.currentPosition ?: return
                broadcast(EVENT_PROGRESS) { writeInt(position.toInt()) }
                mainHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
            }
        }

    private val musicServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                val musicService = (service as? MusicService.MusicBinder)?.service ?: return
                scope.launch {
                    musicService.isPlayerReady.first { it }
                    attach(musicService.player)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) = detach()
        }

    override fun onCreate() {
        super.onCreate()
        bindService(Intent(this, MusicService::class.java), musicServiceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        detach()
        unbindService(musicServiceConnection)
        callbacks.kill()
        scope.cancel()
        super.onDestroy()
    }

    private fun attach(newPlayer: Player) {
        player = newPlayer
        newPlayer.addListener(playerListener)
        publishTrack()
        refreshPlaying()
        pendingActions.toList().also { pendingActions.clear() }.forEach { newPlayer.it() }
    }

    private fun detach() {
        player?.removeListener(playerListener)
        player = null
        publishPlaying(false)
    }

    private fun publishTrack() {
        val track = player?.currentMetadata
        if (track == currentTrack) return
        currentTrack = track
        broadcast(EVENT_TRACK_CHANGED) {
            if (track != null) {
                writeInt(1)
                writeOnLineMusic(track)
            } else {
                writeInt(0)
            }
        }
        // The car builds a fresh track record without a play state on each change and only fills it on the next start event.
        if (playing) broadcast(EVENT_START) {}
    }

    // A stalled network or a track load stops isPlaying, but the car must keep showing "playing" until the user pauses.
    private fun refreshPlaying() {
        val p = player
        publishPlaying(p != null && p.playWhenReady && p.playbackState != Player.STATE_IDLE && p.playbackState != Player.STATE_ENDED)
    }

    private fun publishPlaying(isPlaying: Boolean) {
        if (isPlaying == playing) return
        playing = isPlaying
        mainHandler.removeCallbacks(progressTicker)
        if (isPlaying) {
            broadcast(EVENT_START) {}
            mainHandler.post(progressTicker)
        } else {
            broadcast(EVENT_PAUSE) {}
        }
    }

    private fun broadcast(
        event: Int,
        writeArguments: Parcel.() -> Unit,
    ) {
        val count = callbacks.beginBroadcast()
        try {
            for (i in 0 until count) {
                try {
                    callbacks.getBroadcastItem(i).send(event, writeArguments)
                } catch (_: RemoteException) {
                    // RemoteCallbackList drops the listener when its process dies.
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    // The car can call right after boot, before MusicService has built its player: replay those calls once it is attached.
    private fun onPlayer(action: Player.() -> Unit) {
        mainHandler.post {
            val p = player
            if (p != null) {
                p.action()
            } else {
                pendingActions += action
            }
        }
    }

    private inner class PlayBinder : Binder() {
        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(SERVICE_DESCRIPTOR)
                return true
            }
            if (code !in CALL_PLAY..CALL_UNUSED_B) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(SERVICE_DESCRIPTOR)
            if (code <= CALL_NEXT) Timber.tag(TAG).i("car call %d, player attached=%b", code, player != null)
            when (code) {
                CALL_PLAY ->
                    onPlayer {
                        if (!playWhenReady && playbackState == Player.STATE_IDLE) prepare()
                        playWhenReady = true
                    }
                CALL_PLAY_PAUSE -> onPlayer { togglePlayPause() }
                CALL_PAUSE, CALL_STOP -> onPlayer { playWhenReady = false }
                CALL_SEEK_TO -> {
                    val positionMs = data.readInt().toLong()
                    onPlayer { seekTo(positionMs) }
                }
                CALL_PREVIOUS -> onPlayer { seekToPrevious() }
                CALL_NEXT -> onPlayer { seekToNext() }
                CALL_ADD_LISTENER -> callbacks.register(PlayEventListener(data.readStrongBinder()))
                CALL_REMOVE_LISTENER -> callbacks.unregister(PlayEventListener(data.readStrongBinder()))
            }
            reply?.writeNoException()
            when (code) {
                CALL_GET_MUSIC -> {
                    val track = currentTrack
                    if (track != null) {
                        reply?.writeInt(1)
                        reply?.writeOnLineMusic(track)
                    } else {
                        reply?.writeInt(0)
                    }
                }
                // Amazon reports its player's inner state: 4 started, 5 paused, 0 nothing loaded.
                CALL_GET_STATUS ->
                    reply?.writeInt(
                        when {
                            playing -> STATUS_STARTED
                            currentTrack != null -> STATUS_PAUSED
                            else -> STATUS_IDLE
                        },
                    )
                CALL_UNUSED_A, CALL_UNUSED_B -> reply?.writeLong(0L)
            }
            return true
        }
    }

    private class PlayEventListener(
        private val remote: IBinder,
    ) : IInterface {
        override fun asBinder() = remote

        fun send(
            event: Int,
            writeArguments: Parcel.() -> Unit,
        ) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(EVENT_DESCRIPTOR)
                data.writeArguments()
                remote.transact(event, data, reply, 0)
                reply.readException()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    private companion object {
        const val TAG = "PlayService"
        const val SERVICE_DESCRIPTOR = "com.saicmotor.onlinemedia.IPlayService"
        const val EVENT_DESCRIPTOR = "com.saicmotor.onlinemedia.OnPlayEvent"

        const val CALL_PLAY = 2
        const val CALL_PLAY_PAUSE = 3
        const val CALL_PAUSE = 4
        const val CALL_STOP = 5
        const val CALL_SEEK_TO = 6
        const val CALL_PREVIOUS = 7
        const val CALL_NEXT = 8
        const val CALL_GET_MUSIC = 9
        const val CALL_ADD_LISTENER = 10
        const val CALL_REMOVE_LISTENER = 11
        const val CALL_GET_STATUS = 12
        const val CALL_UNUSED_A = 13
        const val CALL_UNUSED_B = 14

        const val EVENT_TRACK_CHANGED = 1
        const val EVENT_START = 2
        const val EVENT_PAUSE = 3
        const val EVENT_PROGRESS = 4

        const val STATUS_IDLE = 0
        const val STATUS_STARTED = 4
        const val STATUS_PAUSED = 5

        const val PROGRESS_INTERVAL_MS = 1000L
    }
}
