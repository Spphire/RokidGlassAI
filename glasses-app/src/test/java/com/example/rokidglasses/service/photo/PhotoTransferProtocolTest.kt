package com.example.rokidglasses.service.photo

import android.bluetooth.BluetoothSocket
import com.example.rokidcommon.protocol.photo.PacketUtils
import com.example.rokidcommon.protocol.photo.PhotoTransferConstants
import com.example.rokidcommon.protocol.photo.PhotoTransferState
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PhotoTransferProtocolTest {

    @Test
    fun `sendPhoto waits for ack for start and each data chunk`() = runTest {
        val imageData = ByteArray(PhotoTransferConstants.CHUNK_SIZE + 10) { it.toByte() }
        val responses = Channel<PhotoTransferResponse>(Channel.BUFFERED)
        val output = ByteArrayOutputStream()
        val protocol = PhotoTransferProtocol(
            bluetoothSocket = connectedSocket(output),
            receiveResponse = { responses.receive() },
            clearPendingResponses = {},
            responseTimeoutMs = 50
        )
        responses.trySend(ack(0))
        responses.trySend(ack(0))
        responses.trySend(ack(1))

        val result = protocol.sendPhoto(imageData)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().totalChunks).isEqualTo(2)
        assertThat(result.getOrThrow().retryCount).isEqualTo(0)

        val packets = parseWrittenPackets(output.toByteArray())
        assertThat(packets.map { it[0] }).containsExactly(
            PhotoTransferConstants.PACKET_TYPE_START,
            PhotoTransferConstants.PACKET_TYPE_DATA,
            PhotoTransferConstants.PACKET_TYPE_DATA,
            PhotoTransferConstants.PACKET_TYPE_END
        ).inOrder()
    }

    @Test
    fun `sendPhoto retransmits chunk when receiver requests retry`() = runTest {
        val imageData = ByteArray(128) { (it * 2).toByte() }
        val responses = Channel<PhotoTransferResponse>(Channel.BUFFERED)
        val output = ByteArrayOutputStream()
        val protocol = PhotoTransferProtocol(
            bluetoothSocket = connectedSocket(output),
            receiveResponse = { responses.receive() },
            clearPendingResponses = {},
            responseTimeoutMs = 50
        )
        responses.trySend(ack(0))
        responses.trySend(PhotoTransferResponse.Retry(0))
        responses.trySend(ack(0))

        val result = protocol.sendPhoto(imageData)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().retryCount).isEqualTo(1)

        val dataPackets = parseWrittenPackets(output.toByteArray())
            .filter { it[0] == PhotoTransferConstants.PACKET_TYPE_DATA }
        assertThat(dataPackets).hasSize(2)
        assertThat(PacketUtils.parseDataPacket(dataPackets[0]).chunkIndex).isEqualTo(0)
        assertThat(PacketUtils.parseDataPacket(dataPackets[1]).chunkIndex).isEqualTo(0)
    }

    @Test
    fun `sendPhoto fails when ack never arrives`() = runTest {
        val responses = Channel<PhotoTransferResponse>(Channel.BUFFERED)
        val output = ByteArrayOutputStream()
        val protocol = PhotoTransferProtocol(
            bluetoothSocket = connectedSocket(output),
            receiveResponse = { responses.receive() },
            clearPendingResponses = {},
            responseTimeoutMs = 50
        )

        val result = protocol.sendPhoto(ByteArray(32) { it.toByte() })

        assertThat(result.isFailure).isTrue()
        assertThat(protocol.transferState.value).isInstanceOf(PhotoTransferState.Error::class.java)
        assertThat((protocol.transferState.value as PhotoTransferState.Error).message)
            .contains("Failed to start photo transfer")
    }

    @Test
    fun `sendPhoto fails fast and sets error when socket is disconnected`() = runTest {
        val protocol = PhotoTransferProtocol(bluetoothSocket = disconnectedSocket())

        val result = protocol.sendPhoto(ByteArray(32) { it.toByte() })

        assertThat(result.isFailure).isTrue()
        assertThat(protocol.transferState.value).isEqualTo(
            PhotoTransferState.Error("Bluetooth socket not connected")
        )
    }

    @Test
    fun `sendPhoto rejects empty photo data before writing packets`() = runTest {
        val output = ByteArrayOutputStream()
        val protocol = PhotoTransferProtocol(bluetoothSocket = connectedSocket(output))

        val result = protocol.sendPhoto(ByteArray(0))

        assertThat(result.isFailure).isTrue()
        assertThat(protocol.transferState.value).isEqualTo(
            PhotoTransferState.Error("Photo data is empty")
        )
        assertThat(output.toByteArray()).isEmpty()
    }

    @Test
    fun `sendPhoto sends failure end and sets error when data ack never arrives`() = runTest {
        val responses = Channel<PhotoTransferResponse>(Channel.BUFFERED)
        val output = ByteArrayOutputStream()
        val protocol = PhotoTransferProtocol(
            bluetoothSocket = connectedSocket(output),
            receiveResponse = { responses.receive() },
            clearPendingResponses = {},
            responseTimeoutMs = 50
        )
        responses.trySend(ack(0))

        val result = protocol.sendPhoto(ByteArray(32) { it.toByte() })

        assertThat(result.isFailure).isTrue()
        assertThat(protocol.transferState.value).isInstanceOf(PhotoTransferState.Error::class.java)
        assertThat((protocol.transferState.value as PhotoTransferState.Error).message)
            .contains("Failed to send chunk 0")

        val packets = parseWrittenPackets(output.toByteArray())
        assertThat(packets.last()[0]).isEqualTo(PhotoTransferConstants.PACKET_TYPE_END)
        assertThat(PacketUtils.parseEndPacket(packets.last())).isEqualTo(PhotoTransferConstants.STATUS_ERROR)
    }

    private fun ack(chunkIndex: Int): PhotoTransferResponse.Ack {
        return PhotoTransferResponse.Ack(
            PacketUtils.parseAckPacket(
                PacketUtils.createAckPacket(chunkIndex, PhotoTransferConstants.STATUS_SUCCESS)
            )
        )
    }

    private fun connectedSocket(output: ByteArrayOutputStream): BluetoothSocket {
        return mockk(relaxed = true) {
            every { isConnected } returns true
            every { outputStream } returns output
        }
    }

    private fun disconnectedSocket(): BluetoothSocket {
        return mockk(relaxed = true) {
            every { isConnected } returns false
        }
    }

    private fun parseWrittenPackets(bytes: ByteArray): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            val length = when (bytes[offset]) {
                PhotoTransferConstants.PACKET_TYPE_START -> PhotoTransferConstants.START_PACKET_SIZE
                PhotoTransferConstants.PACKET_TYPE_DATA -> {
                    val dataLength = java.nio.ByteBuffer.wrap(bytes, offset + 1, 2)
                        .order(java.nio.ByteOrder.BIG_ENDIAN)
                        .short.toInt() and 0xFFFF
                    PhotoTransferConstants.DATA_HEADER_SIZE + dataLength
                }
                PhotoTransferConstants.PACKET_TYPE_END -> PhotoTransferConstants.END_PACKET_SIZE
                else -> error("Unexpected packet type ${bytes[offset]}")
            }
            packets.add(bytes.copyOfRange(offset, offset + length))
            offset += length
        }
        return packets
    }
}
