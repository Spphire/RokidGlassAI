package com.example.rokidphone.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class PhoneKeepAliveOverlay(
    private val context: Context
) {
    private val windowManager by lazy {
        context.getSystemService(WindowManager::class.java)
    }
    private var overlayView: View? = null

    fun showIfAllowed() {
        if (overlayView != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Keep-alive overlay not shown: draw-over-apps permission is missing")
            ServiceBridge.updateCompanionStatus("Companion: overlay permission missing")
            PhoneAIServiceRuntimeState.record(context, "keep alive overlay missing permission")
            return
        }

        val view = View(context).apply {
            alpha = 0.01f
            contentDescription = "Rokid Photo AI background bridge"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            title = "Rokid Photo AI bridge"
        }

        runCatching {
            windowManager.addView(view, params)
            overlayView = view
            Log.d(TAG, "Keep-alive overlay shown")
            PhoneAIServiceRuntimeState.record(context, "keep alive overlay shown")
        }.onFailure { error ->
            Log.w(TAG, "Unable to show keep-alive overlay", error)
            PhoneAIServiceRuntimeState.record(
                context,
                "keep alive overlay failed",
                mapOf("error" to (error.message ?: error::class.java.simpleName))
            )
        }
    }

    fun hide() {
        val view = overlayView ?: return
        overlayView = null
        runCatching {
            windowManager.removeView(view)
            Log.d(TAG, "Keep-alive overlay hidden")
            PhoneAIServiceRuntimeState.record(context, "keep alive overlay hidden")
        }.onFailure { error ->
            Log.w(TAG, "Unable to hide keep-alive overlay", error)
        }
    }

    private companion object {
        private const val TAG = "PhoneKeepAliveOverlay"
    }
}
