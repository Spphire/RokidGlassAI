package com.example.rokidphone.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.example.rokidcommon.Constants

object NotificationChannels {
    fun ensureServiceChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            Constants.NOTIFICATION_CHANNEL_ID,
            "Rokid Photo AI",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the glasses photo AI bridge running."
            setShowBadge(false)
        }

        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }
}
