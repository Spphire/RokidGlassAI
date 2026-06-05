package com.example.rokidcommon.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PhoneGlassesProtocolIntegrationTest {
    @Test
    fun `handshake and heartbeat exchange can roundtrip through json`() {
        val glassesHandshake = Message.handshake("Rokid-Glasses")
        val phoneReceivedHandshake = Message.fromJson(glassesHandshake.toJson())

        assertThat(phoneReceivedHandshake).isNotNull()
        assertThat(phoneReceivedHandshake!!.type).isEqualTo(MessageType.HANDSHAKE)
        assertThat(phoneReceivedHandshake.payload).isEqualTo("Rokid-Glasses")

        val phoneHeartbeat = Message.heartbeat()
        val glassesReceivedHeartbeat = Message.fromJson(phoneHeartbeat.toJson())

        assertThat(glassesReceivedHeartbeat).isNotNull()
        assertThat(glassesReceivedHeartbeat!!.type).isEqualTo(MessageType.HEARTBEAT)
    }

    @Test
    fun `phone can request capture and glasses can receive analysis status`() {
        val capture = Message.capturePhoto()
        val processing = Message.aiProcessing("Analyzing photo...")
        val result = Message.photoAnalysisResult("B. The graph indicates the correct option.")

        val captureParsed = Message.fromJson(capture.toJson())
        val processingParsed = Message.fromJson(processing.toJson())
        val resultParsed = Message.fromJson(result.toJson())

        assertThat(captureParsed!!.type).isEqualTo(MessageType.CAPTURE_PHOTO)
        assertThat(processingParsed!!.type).isEqualTo(MessageType.AI_PROCESSING)
        assertThat(processingParsed.payload).contains("Analyzing")
        assertThat(resultParsed!!.type).isEqualTo(MessageType.PHOTO_ANALYSIS_RESULT)
        assertThat(resultParsed.payload).contains("graph")
    }

    @Test
    fun `errors and display control messages roundtrip`() {
        val messages = listOf(
            Message.aiError("AI request timed out after 25s"),
            Message.displayText("Manual status"),
            Message.displayClear()
        )

        val parsed = messages.map { Message.fromJson(it.toJson()) }

        assertThat(parsed[0]!!.type).isEqualTo(MessageType.AI_ERROR)
        assertThat(parsed[0]!!.payload).contains("timed out")
        assertThat(parsed[1]!!.type).isEqualTo(MessageType.DISPLAY_TEXT)
        assertThat(parsed[1]!!.payload).isEqualTo("Manual status")
        assertThat(parsed[2]!!.type).isEqualTo(MessageType.DISPLAY_CLEAR)
    }
}
