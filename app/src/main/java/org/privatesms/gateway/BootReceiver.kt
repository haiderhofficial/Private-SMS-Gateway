package org.privatesms.gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Restarts the gateway after a reboot or app update if it was switched on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val store = SettingsStore.get(context)
        if (store.enabled && store.loadPublic().startOnBoot) {
            ContextCompat.startForegroundService(context, Intent(context, GatewayService::class.java))
        }
    }
}
