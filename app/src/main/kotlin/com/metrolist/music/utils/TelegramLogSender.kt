/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.metrolist.music.BuildConfig
import com.metrolist.music.R
import org.json.JSONObject
import timber.log.Timber
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One-tap log upload for the head unit, where no share sheet or file manager is at hand. The bot
 * credentials are baked in at build time from `local.properties`; without them the action is hidden.
 */
object TelegramLogSender {
    private const val TAG = "TelegramLogSender"
    private const val MAX_LOG_BYTES = 4 * 1024 * 1024

    val available: Boolean get() = BuildConfig.TELEGRAM_BOT_TOKEN.isNotBlank() && BuildConfig.TELEGRAM_CHAT_ID.isNotBlank()

    fun send(context: Context) {
        val appContext = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Toast.makeText(appContext, R.string.send_logs_sending, Toast.LENGTH_SHORT).show()
        Thread({
            val sent = runCatching { upload(readLogcat()) }.onFailure { Timber.tag(TAG).w(it, "log upload failed") }.getOrDefault(false)
            main.post {
                Toast.makeText(appContext, if (sent) R.string.send_logs_sent else R.string.send_logs_failed, Toast.LENGTH_LONG).show()
            }
        }, "LogSend").start()
    }

    private fun readLogcat(): ByteArray {
        val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime"))
        val bytes = process.inputStream.use { it.readBytes() }
        process.waitFor()
        return if (bytes.size > MAX_LOG_BYTES) bytes.copyOfRange(bytes.size - MAX_LOG_BYTES, bytes.size) else bytes
    }

    private fun upload(log: ByteArray): Boolean {
        if (log.isEmpty()) return false
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val boundary = "MetrolistLog${System.currentTimeMillis()}"
        val connection =
            (URL("https://api.telegram.org/bot${BuildConfig.TELEGRAM_BOT_TOKEN}/sendDocument").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 25_000
                readTimeout = 45_000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
        DataOutputStream(connection.outputStream).use { out ->
            fun field(
                name: String,
                value: String,
            ) {
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n")
                out.write(value.toByteArray())
                out.writeBytes("\r\n")
            }
            field("chat_id", BuildConfig.TELEGRAM_CHAT_ID)
            field("caption", "Metrolist ${BuildConfig.VERSION_NAME} log $stamp")
            out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"document\"; filename=\"metrolist-$stamp.txt\"\r\n")
            out.writeBytes("Content-Type: text/plain; charset=utf-8\r\n\r\n")
            out.write(log)
            out.writeBytes("\r\n--$boundary--\r\n")
        }
        val code = connection.responseCode
        val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
        connection.disconnect()
        return code in 200..299 && JSONObject(body).optBoolean("ok", false)
    }
}
