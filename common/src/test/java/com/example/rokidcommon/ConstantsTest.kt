package com.example.rokidcommon

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for Constants object.
 * Verifies that shared constants have expected values.
 */
class ConstantsTest {

    @Test
    fun `Bluetooth UUIDs are not null`() {
        // Test: BT UUIDs are defined
        assertThat(Constants.BT_SERVICE_UUID).isNotNull()
    }

    @Test
    fun `connection timing constants are positive`() {
        // Test: connection-related timeouts
        assertThat(Constants.HEARTBEAT_INTERVAL_MS).isGreaterThan(0)
    }

    @Test
    fun `notification constants are non-blank`() {
        // Test: notification channel is defined
        assertThat(Constants.NOTIFICATION_CHANNEL_ID).isNotEmpty()
        assertThat(Constants.NOTIFICATION_ID).isGreaterThan(0)
    }
}
