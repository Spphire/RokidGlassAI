package com.example.rokidphone.service.photo

import com.example.rokidcommon.protocol.photo.PacketUtils
import com.example.rokidcommon.protocol.photo.PhotoTransferConstants
import com.example.rokidcommon.protocol.photo.PhotoTransferState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BluetoothPhotoReceiverTest {
    @Test
    fun `complete transfer emits received photo and leaves success state`() = runTest {
        val receiver = newReceiver()
        val photo = ByteArray(6000) { index -> (index % 251).toByte() }
        val received = mutableListOf<ReceivedPhoto>()
        val collectJob = launch { receiver.receivedPhoto.collect { received.add(it) } }

        receiver.sendTransfer(photo)
        runCurrent()

        assertThat(received).hasSize(1)
        assertThat(received.single().data).isEqualTo(photo)
        assertThat(receiver.transferState.value).isInstanceOf(PhotoTransferState.Success::class.java)
        assertThat(receiver.isReceiving()).isFalse()

        collectJob.cancel()
    }

    @Test
    fun `complete transfer does not suspend when no photo collector is active`() = runTest {
        val receiver = newReceiver()
        val photo = byteArrayOf(1, 2, 3, 4)

        receiver.sendTransfer(photo)
        runCurrent()

        assertThat(receiver.transferState.value).isInstanceOf(PhotoTransferState.Success::class.java)
        assertThat(receiver.isReceiving()).isFalse()
    }

    @Test
    fun `completed photo queue reports error instead of dropping old photos`() = runTest {
        val receiver = newReceiver()

        repeat(4) { index ->
            receiver.sendTransfer(byteArrayOf(index.toByte()))
            assertThat(receiver.transferState.value).isInstanceOf(PhotoTransferState.Success::class.java)
        }

        receiver.sendTransfer(byteArrayOf(9))
        runCurrent()

        val state = receiver.transferState.value
        assertThat(state).isInstanceOf(PhotoTransferState.Error::class.java)
        assertThat((state as PhotoTransferState.Error).message)
            .isEqualTo("Received photo queue is full")
        assertThat(receiver.isReceiving()).isFalse()
    }

    @Test
    fun `end with missing chunks fails and releases session`() = runTest {
        val receiver = newReceiver()
        val outputBytes = ByteArrayOutputStream()
        receiver.setOutputStream(outputBytes)
        val chunks = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        val photo = chunks[0] + chunks[1]

        receiver.processPacket(
            PacketUtils.createStartPacket(
                totalSize = photo.size,
                totalChunks = chunks.size,
                md5 = PacketUtils.calculateMD5(photo)
            )
        )
        receiver.processPacket(PacketUtils.createDataPacket(0, chunks[0]))
        receiver.processPacket(PacketUtils.createEndPacket(PhotoTransferConstants.STATUS_SUCCESS))

        val state = receiver.transferState.value
        assertThat(state).isInstanceOf(PhotoTransferState.Error::class.java)
        assertThat((state as PhotoTransferState.Error).message).contains("Missing chunks")
        assertThat(receiver.isReceiving()).isFalse()
        assertThat(outputBytes.toByteArray().lastPacketType()).isEqualTo(PhotoTransferConstants.PACKET_TYPE_RETRY)
    }

    @Test
    fun `md5 mismatch fails and releases session`() = runTest {
        val receiver = newReceiver()
        val photo = byteArrayOf(1, 2, 3, 4)
        val wrongMd5 = PacketUtils.calculateMD5(byteArrayOf(9, 9, 9, 9))

        receiver.processPacket(
            PacketUtils.createStartPacket(
                totalSize = photo.size,
                totalChunks = 1,
                md5 = wrongMd5
            )
        )
        receiver.processPacket(PacketUtils.createDataPacket(0, photo))
        receiver.processPacket(PacketUtils.createEndPacket(PhotoTransferConstants.STATUS_SUCCESS))

        val state = receiver.transferState.value
        assertThat(state).isInstanceOf(PhotoTransferState.Error::class.java)
        assertThat((state as PhotoTransferState.Error).errorCode)
            .isEqualTo(PhotoTransferConstants.STATUS_MD5_ERROR)
        assertThat(receiver.isReceiving()).isFalse()
    }

    @Test
    fun `transfer timeout leaves observable error state`() = runTest {
        val receiver = newReceiver()
        val photo = byteArrayOf(1, 2, 3)
        receiver.processPacket(
            PacketUtils.createStartPacket(
                totalSize = photo.size,
                totalChunks = 1,
                md5 = PacketUtils.calculateMD5(photo)
            )
        )

        advanceTimeBy(30_000L)
        runCurrent()

        val state = receiver.transferState.value
        assertThat(state).isInstanceOf(PhotoTransferState.Error::class.java)
        assertThat((state as PhotoTransferState.Error).errorCode)
            .isEqualTo(PhotoTransferConstants.STATUS_TIMEOUT)
        assertThat(receiver.isReceiving()).isFalse()
    }

    private fun TestScope.newReceiver(): BluetoothPhotoReceiver {
        val receiver = BluetoothPhotoReceiver(this.backgroundScope)
        receiver.setOutputStream(ByteArrayOutputStream())
        return receiver
    }

    private suspend fun BluetoothPhotoReceiver.sendTransfer(photo: ByteArray) {
        val chunks = PacketUtils.splitIntoChunks(photo)
        processPacket(
            PacketUtils.createStartPacket(
                totalSize = photo.size,
                totalChunks = chunks.size,
                md5 = PacketUtils.calculateMD5(photo)
            )
        )
        chunks.forEachIndexed { index, chunk ->
            processPacket(PacketUtils.createDataPacket(index, chunk))
        }
        processPacket(PacketUtils.createEndPacket(PhotoTransferConstants.STATUS_SUCCESS))
    }

    private fun ByteArray.lastPacketType(): Byte {
        assertThat(size).isAtLeast(PhotoTransferConstants.RETRY_PACKET_SIZE)
        return this[size - PhotoTransferConstants.RETRY_PACKET_SIZE]
    }
}
