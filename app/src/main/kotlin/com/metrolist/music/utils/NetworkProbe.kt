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
 * stays up. While music plays this probes the gateway and logs when it stops answering, to diagnose
 * those outages. It never touches the Wi-Fi connection. A refused connection counts as an answer: only
 * silence is a fault.
 */
class NetworkProbe(
    context: Context,
    private val isPlaying: () -> Boolean,
) {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch { watch() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun watch() {
        var misses = 0
        while (true) {
            delay(PROBE_INTERVAL_MS)
            if (!isPlaying() || !onWifi()) {
                misses = 0
                continue
            }
            val gateway = gatewayAddress() ?: continue
            if (withContext(Dispatchers.IO) { answers(gateway) }) {
                if (misses > 0) Timber.tag(TAG).i("gateway %s answers again after %d misses", gateway, misses)
                misses = 0
                continue
            }
            misses++
            Timber.tag(TAG).i("gateway %s silent, miss %d", gateway, misses)
        }
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

    private companion object {
        const val TAG = "NetworkProbe"
        const val PROBE_PORT = 80
        const val PROBE_TIMEOUT_MS = 2_000
        const val PROBE_INTERVAL_MS = 8_000L
    }
}
