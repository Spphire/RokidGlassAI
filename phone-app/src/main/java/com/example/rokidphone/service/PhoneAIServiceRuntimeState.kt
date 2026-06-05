package com.example.rokidphone.service

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object PhoneAIServiceRuntimeState {
    private const val FILE_NAME = "phone_ai_service_state.txt"

    fun record(
        context: Context,
        event: String,
        details: Map<String, Any?> = emptyMap()
    ) {
        runCatching {
            val now = Date()
            val formattedTime = synchronized(DATE_FORMAT) {
                DATE_FORMAT.format(now)
            }
            val lines = buildList {
                add("wallTime=$formattedTime")
                add("elapsedMs=${SystemClock.elapsedRealtime()}")
                add("event=$event")
                details.forEach { (key, value) ->
                    add("$key=${value ?: "null"}")
                }
            }
            File(context.filesDir, FILE_NAME).writeText(lines.joinToString("\n") + "\n")
        }
    }

    private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
}
