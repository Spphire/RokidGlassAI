package com.example.rokidphone.service

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class PhoneAIServiceAutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        if (!shouldAutoStartForIntent(intent)) {
            Log.d(TAG, "Auto start ignored: $action")
            return
        }

        Log.d(TAG, "Auto start requested: $action")
        PhoneAIService.startIfAutoRunEnabled(context, action.ifBlank { "system broadcast" })
    }

    private companion object {
        private const val TAG = "PhoneAIServiceAutoStart"
    }
}

internal fun shouldAutoStartForIntent(intent: Intent?): Boolean {
    return when (intent?.action) {
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_LOCKED_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
        BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
        BluetoothDeviceActions.ACTION_ACL_CONNECTED,
        BluetoothDeviceActions.ACTION_ACL_DISCONNECTED -> true
        BluetoothAdapter.ACTION_STATE_CHANGED -> {
            intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) ==
                BluetoothAdapter.STATE_ON
        }
        else -> false
    }
}

private object BluetoothDeviceActions {
    const val ACTION_ACL_CONNECTED = "android.bluetooth.device.action.ACL_CONNECTED"
    const val ACTION_ACL_DISCONNECTED = "android.bluetooth.device.action.ACL_DISCONNECTED"
}
