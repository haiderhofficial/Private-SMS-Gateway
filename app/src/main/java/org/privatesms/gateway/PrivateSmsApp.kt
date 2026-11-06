package org.privatesms.gateway

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class PrivateSmsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        val channel = NotificationChannel(
            GatewayService.CHANNEL_ID, "Gateway service", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shown while the SMS gateway is running" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
