/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The MG4 head unit sometimes stops sending anything to its hotspot for a few minutes while the link
 * stays up, and a Wi-Fi reconnect clears it in seconds. While music plays this probes the gateway and
 * reconnects when it stops answering. A refused connection counts as an answer: only silence is a fault.
 */
class WifiWatchdog(
    context: Context,
    private val isPlaying: () -> Boolean,
) {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var job: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch { watch() }
    }

    fun stop() {
        job?.cancel()
        job = null
        releaseLock()
    }

    private suspend fun watch() {
        var misses = 0
        var lastRecoveryAt = 0L
        var reassociated = false
        while (true) {
            delay(PROBE_INTERVAL_MS)
            if (!isPlaying() || !onWifi()) {
                misses = 0
                reassociated = false
                releaseLock()
                continue
            }
            acquireLock()
            val gateway = gatewayAddress() ?: continue
            if (withContext(Dispatchers.IO) { answers(gateway) }) {
                misses = 0
                reassociated = false
                continue
            }
            misses++
            Timber.tag(TAG).i("gateway %s silent, miss %d", gateway, misses)
            val now = System.currentTimeMillis()
            if (misses < MISSES_BEFORE_RECOVERY || now - lastRecoveryAt < RECOVERY_COOLDOWN_MS) continue
            lastRecoveryAt = now
            misses = 0
            recover(escalate = reassociated)
            reassociated = true
        }
    }

    @Suppress("DEPRECATION")
    private fun recover(escalate: Boolean) {
        Timber.tag(TAG).w("recovering Wi-Fi, escalate=%b", escalate)
        runCatching {
            if (escalate) {
                wifiManager.disconnect()
                wifiManager.reconnect()
            } else {
                wifiManager.reassociate()
            }
        }.onFailure { Timber.tag(TAG).w(it, "Wi-Fi recovery refused") }
    }

    private fun onWifi(): Boolean {
        val capabilities =
            connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    @Suppress("DEPRECATION")
    private fun gatewayAddress(): String? {
        val gateway = wifiManager.dhcpInfo?.gateway ?: return null
        if (gateway == 0) return null
        // DhcpInfo stores the address little-endian.
        return "${gateway and 0xff}.${gateway shr 8 and 0xff}.${gateway shr 16 and 0xff}.${gateway shr 24 and 0xff}"
    }

    private fun answers(gateway: String): Boolean =
        try {
            Socket().use { it.connect(InetSocketAddress(gateway, PROBE_PORT), PROBE_TIMEOUT_MS) }
            true
        } catch (_: ConnectException) {
            true
        } catch (_: SocketTimeoutException) {
            false
        } catch (_: Exception) {
            // No route or unreachable network: the link is down, which the system already knows about.
            false
        }

    @Suppress("DEPRECATION")
    private fun acquireLock() {
        if (wifiLock?.isHeld == true) return
        wifiLock =
            wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseLock() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    private companion object {
        const val TAG = "WifiWatchdog"
        const val PROBE_PORT = 80
        const val PROBE_TIMEOUT_MS = 2_000
        const val PROBE_INTERVAL_MS = 8_000L
        const val MISSES_BEFORE_RECOVERY = 3
        const val RECOVERY_COOLDOWN_MS = 60_000L
    }
}
