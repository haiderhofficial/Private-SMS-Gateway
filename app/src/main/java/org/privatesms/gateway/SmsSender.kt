package org.privatesms.gateway

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

object SmsSender {
    const val ACTION_SENT = "org.privatesms.gateway.SMS_SENT"
    const val ACTION_DELIVERED = "org.privatesms.gateway.SMS_DELIVERED"
    const val EXTRA_KEY = "key"
    const val EXTRA_ID = "id"
    const val EXTRA_TOTAL = "total"

    // Seeded from the clock so request codes from a previous process never collide with new ones.
    private val counter = AtomicInteger((System.currentTimeMillis() / 1000).toInt())

    /** Hands the message to Android. Success/failure/delivery arrive later via [SmsStatusReceiver]. */
    fun send(ctx: Context, id: String, to: String, body: String, slot: Int, reportDelivery: Boolean) {
        val sm = smsManager(ctx, slot)
        val parts = sm.divideMessage(body)
        val key = if (id.isNotEmpty()) id else UUID.randomUUID().toString()

        val sentIntents = ArrayList<PendingIntent>()
        val deliveryIntents = ArrayList<PendingIntent>()
        for (i in parts.indices) {
            sentIntents.add(pending(ctx, ACTION_SENT, key, id, parts.size))
            if (reportDelivery) deliveryIntents.add(pending(ctx, ACTION_DELIVERED, key, id, parts.size))
        }

        if (parts.size == 1) {
            sm.sendTextMessage(to, null, parts[0], sentIntents[0], deliveryIntents.firstOrNull())
        } else {
            sm.sendMultipartTextMessage(to, null, parts, sentIntents, if (reportDelivery) deliveryIntents else null)
        }
    }

    private fun pending(ctx: Context, action: String, key: String, id: String, total: Int): PendingIntent {
        val intent = Intent(ctx, SmsStatusReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_KEY, key)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_TOTAL, total)
        return PendingIntent.getBroadcast(
            ctx, counter.incrementAndGet(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    @Suppress("DEPRECATION")
    private fun smsManager(ctx: Context, slot: Int): SmsManager {
        val default: SmsManager =
            if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        if (slot <= 0) return default
        val subId = subscriptionIdForSlot(ctx, slot - 1)
            ?: throw IllegalStateException("SIM $slot not found (is the phone permission granted?)")
        return if (Build.VERSION.SDK_INT >= 31) default.createForSubscriptionId(subId)
        else SmsManager.getSmsManagerForSubscriptionId(subId)
    }

    private fun hasPhoneState(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun subscriptions(ctx: Context): List<SubscriptionInfo> =
        if (!hasPhoneState(ctx)) emptyList()
        else runCatching {
            ctx.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList
        }.getOrNull().orEmpty()

    private fun subscriptionIdForSlot(ctx: Context, slotIndex: Int): Int? =
        subscriptions(ctx).firstOrNull { it.simSlotIndex == slotIndex }?.subscriptionId

    /** ("0","Default"), ("1","SIM 1 (Carrier)"), ("2","SIM 2") ... for the settings screen. */
    fun simOptions(ctx: Context): List<Pair<String, String>> {
        val infos = subscriptions(ctx)
        val out = mutableListOf("0" to "Default")
        for (slot in 1..2) {
            val name = infos.firstOrNull { it.simSlotIndex == slot - 1 }?.displayName?.toString()
            val label = if (name.isNullOrBlank()) "SIM $slot" else "SIM $slot ($name)"
            out.add(slot.toString() to label)
        }
        return out
    }
}
