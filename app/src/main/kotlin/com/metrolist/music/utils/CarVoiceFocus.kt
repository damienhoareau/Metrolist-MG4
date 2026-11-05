/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.IBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Asks the head unit to free its microphone for a voice session, the way the stock Android Auto and
 * CarPlay apps do: an assistant audio focus plus the car's own voice-command app focus
 * (android.car.CarAppFocusManager). android.car is not in the public SDK, so it is reached by
 * reflection and every failure leaves the app working without it.
 */
object CarVoiceFocus {
    private const val TAG = "CarVoiceFocus"
    private const val APP_FOCUS_VOICE_COMMAND = 2
    private const val APP_FOCUS_REQUEST_SUCCEEDED = 1
    private const val CONNECT_TIMEOUT_MS = 1_500L
    private const val OWNERSHIP_CALLBACK = "android.car.CarAppFocusManager\$OnAppFocusOwnershipCallback"

    private val lock = Any()
    private var car: Any? = null
    private var appFocusManager: Any? = null
    private var ownershipCallback: Any? = null

    private val audioFocusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            ).setOnAudioFocusChangeListener { }
            .build()
    }

    suspend fun acquire(context: Context) {
        val appContext = context.applicationContext
        val granted = audioManager(appContext).requestAudioFocus(audioFocusRequest)
        val carGranted = withContext(Dispatchers.IO) { synchronized(lock) { requestAppFocus(appContext) } }
        Timber.tag(TAG).i("voice focus: audio=%d car=%b", granted, carGranted)
    }

    fun release(context: Context) {
        audioManager(context.applicationContext).abandonAudioFocusRequest(audioFocusRequest)
        synchronized(lock) { abandonAppFocus() }
    }

    private fun audioManager(context: Context) = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun requestAppFocus(context: Context): Boolean {
        val manager = connect(context) ?: return false
        return try {
            val callbackClass = Class.forName(OWNERSHIP_CALLBACK)
            val callback = ownershipCallback ?: newOwnershipCallback(callbackClass).also { ownershipCallback = it }
            val result =
                manager.javaClass
                    .getMethod("requestAppFocus", Int::class.javaPrimitiveType, callbackClass)
                    .invoke(manager, APP_FOCUS_VOICE_COMMAND, callback) as? Int
            result == APP_FOCUS_REQUEST_SUCCEEDED
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "requestAppFocus failed")
            false
        }
    }

    private fun abandonAppFocus() {
        val manager = appFocusManager ?: return
        val callback = ownershipCallback ?: return
        try {
            manager.javaClass
                .getMethod("abandonAppFocus", Class.forName(OWNERSHIP_CALLBACK), Int::class.javaPrimitiveType)
                .invoke(manager, callback, APP_FOCUS_VOICE_COMMAND)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "abandonAppFocus failed")
        }
    }

    // The car service keeps callbacks in a map, so the Object methods of the proxy must answer properly.
    private fun newOwnershipCallback(callbackClass: Class<*>): Any =
        Proxy.newProxyInstance(CarVoiceFocus::class.java.classLoader, arrayOf(callbackClass)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "toString" -> "CarVoiceFocus.callback"
                else -> null
            }
        }

    /** A car manager by service name, or null when the car service is unavailable. */
    internal fun carManager(
        context: Context,
        name: String,
    ): Any? =
        synchronized(lock) {
            val connected = ensureCar(context) ?: return null
            try {
                connected.javaClass.getMethod("getCarManager", String::class.java).invoke(connected, name)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "car manager %s unavailable", name)
                null
            }
        }

    private fun connect(context: Context): Any? {
        appFocusManager?.let { return it }
        return try {
            val service = Class.forName("android.car.Car").getField("APP_FOCUS_SERVICE").get(null) as String
            carManager(context, service).also { appFocusManager = it }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "car app focus unavailable")
            null
        }
    }

    private fun ensureCar(context: Context): Any? {
        car?.let { return it }
        return try {
            val carClass = Class.forName("android.car.Car")
            val connected = CountDownLatch(1)
            val connection =
                object : ServiceConnection {
                    override fun onServiceConnected(
                        name: ComponentName?,
                        service: IBinder?,
                    ) = connected.countDown()

                    override fun onServiceDisconnected(name: ComponentName?) {
                        synchronized(lock) {
                            appFocusManager = null
                            ownershipCallback = null
                        }
                    }
                }
            carClass
                .getMethod("createCar", Context::class.java, ServiceConnection::class.java)
                .invoke(null, context, connection)
                .also {
                    car = it
                    runCatching { carClass.getMethod("connect").invoke(it) }
                    connected.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "car service unavailable")
            null
        }
    }
}
