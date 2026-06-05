package com.example.rokidcommon.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MessageTypeTest {
    @Test
    fun `simplified protocol exposes only photo ai message types`() {
        assertThat(MessageType.entries.map { it.code }).containsExactly(
            0x00,
            0x01,
            0x02,
            0x03,
            0x0F,
            0x20,
            0x2F,
            0x30,
            0x31,
            0x40,
            0x41,
            0x42,
            0x43,
            0x44,
            0x45,
            0x46,
            0x47
        )
    }

    @Test
    fun `important photo flow codes remain stable`() {
        assertThat(MessageType.HEARTBEAT.code).isEqualTo(0x02)
        assertThat(MessageType.HEARTBEAT_ACK.code).isEqualTo(0x03)
        assertThat(MessageType.AI_PROCESSING.code).isEqualTo(0x20)
        assertThat(MessageType.AI_ERROR.code).isEqualTo(0x2F)
        assertThat(MessageType.PHOTO_ANALYSIS_RESULT.code).isEqualTo(0x46)
        assertThat(MessageType.CAPTURE_PHOTO.code).isEqualTo(0x47)
    }

    @Test
    fun `photo packet control codes remain stable`() {
        assertThat(MessageType.PHOTO_START.code).isEqualTo(0x40)
        assertThat(MessageType.PHOTO_DATA.code).isEqualTo(0x41)
        assertThat(MessageType.PHOTO_END.code).isEqualTo(0x42)
        assertThat(MessageType.PHOTO_ACK.code).isEqualTo(0x43)
        assertThat(MessageType.PHOTO_RETRY.code).isEqualTo(0x44)
        assertThat(MessageType.PHOTO_CANCEL.code).isEqualTo(0x45)
    }

    @Test
    fun `fromCode resolves known codes and rejects removed ranges`() {
        assertThat(MessageType.fromCode(0x00)).isEqualTo(MessageType.HANDSHAKE)
        assertThat(MessageType.fromCode(0x02)).isEqualTo(MessageType.HEARTBEAT)
        assertThat(MessageType.fromCode(0x46)).isEqualTo(MessageType.PHOTO_ANALYSIS_RESULT)
        assertThat(MessageType.fromCode(0x47)).isEqualTo(MessageType.CAPTURE_PHOTO)

        assertThat(MessageType.fromCode(0x10)).isNull()
        assertThat(MessageType.fromCode(0x21)).isNull()
        assertThat(MessageType.fromCode(0x50)).isNull()
        assertThat(MessageType.fromCode(0xFF)).isNull()
    }

    @Test
    fun `no duplicate message type codes exist`() {
        assertThat(MessageType.entries.map { it.code }).containsNoDuplicates()
    }
}
