package com.example.rokidcommon.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MessageTest {
    @Test
    fun `handshake creates HANDSHAKE message with device name`() {
        val msg = Message.handshake("Rokid-Glasses")

        assertThat(msg.type).isEqualTo(MessageType.HANDSHAKE)
        assertThat(msg.payload).isEqualTo("Rokid-Glasses")
        assertThat(msg.binaryData).isNull()
        assertThat(msg.id).isNotEmpty()
        assertThat(msg.timestamp).isGreaterThan(0)
    }

    @Test
    fun `factory methods create simplified photo flow messages`() {
        assertThat(Message.heartbeat().type).isEqualTo(MessageType.HEARTBEAT)
        assertThat(Message.aiProcessing("Calling AI").payload).isEqualTo("Calling AI")
        assertThat(Message.aiError("failed").type).isEqualTo(MessageType.AI_ERROR)
        assertThat(Message.displayText("show").type).isEqualTo(MessageType.DISPLAY_TEXT)
        assertThat(Message.displayClear().type).isEqualTo(MessageType.DISPLAY_CLEAR)
        assertThat(Message.capturePhoto().type).isEqualTo(MessageType.CAPTURE_PHOTO)

        val result = Message.photoAnalysisResult("answer")
        assertThat(result.type).isEqualTo(MessageType.PHOTO_ANALYSIS_RESULT)
        assertThat(result.payload).isEqualTo("answer")
    }

    @Test
    fun `photoData creates PHOTO_DATA message with binary data`() {
        val bytes = byteArrayOf(9, 8, 7)
        val msg = Message.photoData(bytes)

        assertThat(msg.type).isEqualTo(MessageType.PHOTO_DATA)
        assertThat(msg.binaryData).isEqualTo(bytes)
    }

    @Test
    fun `toJson and fromJson roundtrip for text payload`() {
        val original = Message.photoAnalysisResult("B. because the diagram shows it")
        val restored = Message.fromJson(original.toJson())

        assertThat(restored).isNotNull()
        assertThat(restored!!.type).isEqualTo(MessageType.PHOTO_ANALYSIS_RESULT)
        assertThat(restored.payload).isEqualTo(original.payload)
        assertThat(restored.id).isEqualTo(original.id)
    }

    @Test
    fun `toJson and fromJson roundtrip for binary payload`() {
        val original = Message.photoData(byteArrayOf(0, 1, 127, -128, -1))
        val restored = Message.fromJson(original.toJson())

        assertThat(restored).isNotNull()
        assertThat(restored!!.type).isEqualTo(MessageType.PHOTO_DATA)
        assertThat(restored.binaryData).isEqualTo(original.binaryData)
    }

    @Test
    fun `fromJson returns null for invalid or removed type code`() {
        assertThat(Message.fromJson("not valid json")).isNull()
        assertThat(Message.fromJson("{}")).isNull()
        assertThat(Message.fromJson("""{"id":"x","type":16,"timestamp":0}""")).isNull()
    }

    @Test
    fun `toJson omits null payload and binary data`() {
        val json = Message.heartbeat().toJson()

        assertThat(json).doesNotContain("\"payload\"")
        assertThat(json).doesNotContain("\"binaryData\"")
    }

    @Test
    fun `toBytes and fromBytes roundtrip for photo data`() {
        val photoChunk = byteArrayOf(1, 2, 3, 4, 5)
        val original = Message.photoData(photoChunk)
        val restored = Message.fromBytes(original.toBytes())

        assertThat(restored).isNotNull()
        assertThat(restored!!.type).isEqualTo(MessageType.PHOTO_DATA)
        assertThat(restored.binaryData).isEqualTo(photoChunk)
    }

    @Test
    fun `toBytes encodes type code and payload length correctly`() {
        val data = byteArrayOf(10, 20)
        val bytes = Message.photoData(data).toBytes()
        val buffer = ByteBuffer.wrap(bytes)

        assertThat(buffer.getInt()).isEqualTo(0x41)
        assertThat(buffer.getInt()).isEqualTo(2)
    }

    @Test
    fun `fromBytes rejects invalid bytes`() {
        assertThat(Message.fromBytes(ByteArray(7))).isNull()

        val buffer = ByteBuffer.allocate(8)
        buffer.putInt(0x10)
        buffer.putInt(0)
        assertThat(Message.fromBytes(buffer.array())).isNull()
    }

    @Test
    fun `long analysis result payload is preserved`() {
        val longText = "A".repeat(10_000)
        val restored = Message.fromJson(Message.photoAnalysisResult(longText).toJson())

        assertThat(restored!!.payload).isEqualTo(longText)
    }

    @Test
    fun `equals is based on id and type only`() {
        val msg1 = Message(id = "same-id", type = MessageType.HEARTBEAT, payload = "a")
        val msg2 = Message(id = "same-id", type = MessageType.HEARTBEAT, payload = "b")
        val msg3 = Message(id = "diff-id", type = MessageType.HEARTBEAT, payload = "a")

        assertThat(msg1).isEqualTo(msg2)
        assertThat(msg1).isNotEqualTo(msg3)
    }
}
