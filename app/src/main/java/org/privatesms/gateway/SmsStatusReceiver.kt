package org.privatesms.gateway

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.update

/** Receives the "sent" and "delivered" callbacks from Android's SMS stack. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(SmsSender.EXTRA_KEY) ?: return
        val id = intent.getStringExtra(SmsSender.EXTRA_ID).orEmpty()
        val total = intent.getIntExtra(SmsSender.EXTRA_TOTAL, 1)
        val ok = resultCode == Activity.RESULT_OK
        val app = context.applicationContext
        when (intent.action) {
            SmsSender.ACTION_SENT -> SmsTracker.onSent(app, key, id, total, ok, errorText(resultCode))
            SmsSender.ACTION_DELIVERED -> SmsTracker.onDelivered(app, key, id, total, ok)
        }
    }

    private fun errorText(code: Int) = when (code) {
        1 -> "generic failure (check SIM balance and signal)"
        2 -> "radio off (airplane mode?)"
        3 -> "null PDU"
        4 -> "no service"
        5 -> "sending limit exceeded"
        6 -> "FDN check failure"
        7 -> "short code not allowed"
        8 -> "short code never allowed"
        else -> "error code $code"
    }
}

/**
 * Combines the per-part callbacks of a (possibly multipart) message into one result,
 * then queues it for the server and updates the counters.
 */
object SmsTracker {
    private class Track(val total: Int) {
        var ok = 0
        var bad = 0
        var error: String? = null
    }

    private val sent = HashMap<String, Track>()
    private val delivered = HashMap<String, Track>()

    fun onSent(ctx: Context, key: String, id: String, total: Int, ok: Boolean, err: String) {
        val done = synchronized(this) {
            val t = sent.getOrPut(key) { Track(total) }
            if (ok) t.ok++ else { t.bad++; t.error = err }
            if (t.ok + t.bad >= t.total) { sent.remove(key); t } else null
        } ?: return

        if (done.bad > 0) {
            GatewayState.failed.update { it + 1 }
            AppLog.w("Failed ${labelFor(id)}: ${done.error}")
            Reporter.enqueue(ctx, id, "failed", done.error)
        } else {
            GatewayState.sent.update { it + 1 }
            AppLog.i("Sent ${labelFor(id)}")
            Reporter.enqueue(ctx, id, "sent", null)
        }
    }

    fun onDelivered(ctx: Context, key: String, id: String, total: Int, ok: Boolean) {
        val done = synchronized(this) {
            val t = delivered.getOrPut(key) { Track(total) }
            if (ok) t.ok++ else t.bad++
            if (t.ok + t.bad >= t.total) { delivered.remove(key); t } else null
        } ?: return

        if (done.bad > 0) {
            AppLog.w("Delivery failed ${labelFor(id)}")
            Reporter.enqueue(ctx, id, "failed", "delivery failed")
        } else {
            GatewayState.delivered.update { it + 1 }
            AppLog.i("Delivered ${labelFor(id)}")
            Reporter.enqueue(ctx, id, "delivered", null)
        }
    }
}
