package org.privatesms.gateway

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.Calendar

data class PollResult(val ok: Boolean, val text: String, val count: Int = 0)

/** One poll cycle: ask the server for messages, validate them, hand them to the SMS sender. */
object GatewayEngine {
    private val sendTimes = ArrayDeque<Long>()
    private val lock = Any()

    /** Why polling should be skipped right now, or null if it is fine to poll. */
    fun blockReason(ctx: Context, cfg: Config): String? {
        if (cfg.wifiOnly && !onWifi(ctx)) return "Waiting for Wi-Fi"
        if (cfg.quietEnabled && inQuietHours(cfg)) return "Quiet hours"
        if (!canSend(cfg)) return "Hourly send limit reached"
        return null
    }

    suspend fun pollOnce(ctx: Context, cfg: Config, force: Boolean = false): PollResult {
        if (cfg.pollUrl.isBlank()) return PollResult(false, "Poll URL is not set")
        if (!force) blockReason(ctx, cfg)?.let { return PollResult(true, it) }

        val res = try {
            withContext(Dispatchers.IO) { Api.poll(cfg) }
        } catch (e: Exception) {
            return PollResult(false, "Network error: ${e.message ?: e.javaClass.simpleName}")
        }

        if (res.code == 204 || (res.code == 200 && res.body.isBlank())) return PollResult(true, "No messages")
        if (res.code !in 200..299) return PollResult(false, "Server answered HTTP ${res.code}")

        val jobs = try {
            MessageParser.parse(res.body, cfg)
        } catch (e: Exception) {
            return PollResult(false, "Unreadable response: ${e.message}")
        }
        if (jobs.isEmpty()) return PollResult(true, "No messages")

        var handled = 0
        for (job in jobs) {
            handle(ctx, cfg, job)
            handled++
            if (handled < jobs.size) delay(cfg.sendDelaySec.coerceAtLeast(0) * 1000L)
        }
        return PollResult(true, "Handled $handled message(s)", handled)
    }

    private fun handle(ctx: Context, cfg: Config, job: SmsJob) {
        val id = job.id
        fun fail(reason: String) {
            AppLog.w("Rejected ${labelFor(id)}: $reason")
            GatewayState.failed.update { it + 1 }
            Reporter.enqueue(ctx, id, "failed", reason)
        }

        val to = normalize(job.to, cfg.defaultCountryCode) ?: return fail("invalid phone number")
        if (job.body.isBlank()) return fail("empty message body")
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return fail("SMS permission not granted on the phone")
        }
        if (!canSend(cfg)) return fail("hourly send limit reached")

        try {
            SmsSender.send(ctx, id, to, job.body, job.sim ?: cfg.simSlot, cfg.reportDelivery)
            recordSend()
            AppLog.i("Sending ${labelFor(id)} to ${maskNumber(to)}")
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Returns an E.164-style number, or null if it doesn't look like a phone number. */
    fun normalize(raw: String, countryCode: String): String? {
        var s = raw.trim().replace(Regex("[\\s\\-().]"), "")
        if (s.startsWith("00")) {
            s = "+" + s.drop(2)
        } else if (s.startsWith("0") && countryCode.isNotBlank()) {
            s = "+" + countryCode.trim().removePrefix("+") + s.drop(1)
        }
        return if (Regex("^\\+?[0-9]{5,15}$").matches(s)) s else null
    }

    private fun canSend(cfg: Config): Boolean {
        if (cfg.maxPerHour <= 0) return true
        synchronized(lock) {
            val cutoff = System.currentTimeMillis() - 3_600_000L
            while (sendTimes.isNotEmpty() && sendTimes.first() < cutoff) sendTimes.removeFirst()
            return sendTimes.size < cfg.maxPerHour
        }
    }

    private fun recordSend() {
        synchronized(lock) { sendTimes.addLast(System.currentTimeMillis()) }
    }

    private fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun inQuietHours(cfg: Config): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val s = cfg.quietStartHour
        val e = cfg.quietEndHour
        return when {
            s == e -> false
            s < e -> h in s until e
            else -> h >= s || h < e
        }
    }
}
