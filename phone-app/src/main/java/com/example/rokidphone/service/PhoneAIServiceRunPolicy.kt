package com.example.rokidphone.service

import android.content.Context

object PhoneAIServiceRunPolicy {
    private const val PREFS_NAME = "rokid_phone_ai_service"
    private const val KEY_AUTO_RUN_ENABLED = "auto_run_enabled"

    fun isAutoRunEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_RUN_ENABLED, true)
    }

    fun setAutoRunEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_RUN_ENABLED, enabled)
            .apply()
    }
}
