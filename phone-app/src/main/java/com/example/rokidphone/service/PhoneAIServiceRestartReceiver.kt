package com.example.rokidphone.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

class PhoneAIServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "Restart requested")
        PhoneAIService.startIfAutoRunEnabled(context, "scheduled restart")
    }

    companion object {
        private const val TAG = "PhoneAIServiceRestart"
        private const val RESTART_DELAY_MS = 1_000L

        fun scheduleRestartIfAutoRunEnabled(
            context: Context,
            delayMs: Long = RESTART_DELAY_MS
        ) {
            if (!PhoneAIServiceRunPolicy.isAutoRunEnabled(context)) {
                Log.d(TAG, "Restart not scheduled because auto-run is disabled")
                return
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, PhoneAIServiceRestartReceiver::class.java),
                PendingIntent.FLAG_ONE_SHOT or
                    PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val triggerAt = SystemClock.elapsedRealtime() + delayMs
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager?.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } else {
                alarmManager?.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
        }
    }
}
