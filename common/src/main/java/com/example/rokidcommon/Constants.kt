package com.example.rokidcommon

import java.util.UUID

/**
 * Shared constants
 */
object Constants {
    // Bluetooth UUID
    val BT_SERVICE_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    
    // Connection settings
    const val HEARTBEAT_INTERVAL_MS = 5000L
    
    // Notification Channel
    const val NOTIFICATION_CHANNEL_ID = "rokid_ai_service"
    const val NOTIFICATION_ID = 1001
}
