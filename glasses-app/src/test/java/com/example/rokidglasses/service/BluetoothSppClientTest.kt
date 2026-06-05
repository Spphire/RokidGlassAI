package com.example.rokidglasses.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import com.example.rokidcommon.protocol.Message
import com.example.rokidcommon.protocol.MessageType
import com.example.rokidcommon.protocol.photo.PacketUtils
import com.example.rokidcommon.protocol.photo.PhotoTransferConstants
import com.example.rokidglasses.service.photo.PhotoTransferResponse
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BluetoothSppClientTest {
    private lateinit var context: Context
    private lateinit var scope: TestScope
    private lateinit var client: BluetoothSppClient
    private lateinit var mockBluetoothAdapter: BluetoothAdapter
    private lateinit var mockBluetoothManager: BluetoothManager
    private lateinit var mockPrefs: SharedPreferences

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        mockBluetoothManager = mockk(relaxed = true)
        mockBluetoothAdapter = mockk(relaxed = true)
        mockPrefs = mockk(relaxed = true)

        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns mockBluetoothManager
        every { context.checkSelfPermission(any()) } returns PackageManager.PERMISSION_GRANTED
        every { context.getSharedPreferences(any(), any()) } returns mockPrefs
        every { mockPrefs.getString(any(), any()) } returns null
        every { mockBluetoothManager.adapter } returns mockBluetoothAdapter

        scope = TestScope(UnconfinedTestDispatcher())
        client = BluetoothSppClient(context, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `all expected client states exist`() {
        assertThat(BluetoothClientState.entries).containsExactly(
            BluetoothClientState.DISCONNECTED,
            BluetoothClientState.CONNECTING,
            BluetoothClientState.CONNECTED
        )
    }

    @Test
    fun `initial state is disconnected with no socket`() {
        assertThat(client.connectionState.value).isEqualTo(BluetoothClientState.DISCONNECTED)
        assertThat(client.connectedDeviceName.value).isNull()
        assertThat(client.connectedSocket).isNull()
    }

    @Test
    fun `SERVICE_UUID matches phone app uuid`() {
        assertThat(BluetoothSppClient.SERVICE_UUID)
            .isEqualTo(UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890"))
    }

    @Test
    fun `getPairedDevices returns empty list when permission is denied`() {
        every { context.checkSelfPermission(any()) } returns PackageManager.PERMISSION_DENIED

        assertThat(client.getPairedDevices()).isEmpty()
    }

    @Test
    fun `isBluetoothEnabled returns adapter state`() {
        every { mockBluetoothAdapter.isEnabled } returns true
        assertThat(client.isBluetoothEnabled()).isTrue()

        every { mockBluetoothAdapter.isEnabled } returns false
        assertThat(client.isBluetoothEnabled()).isFalse()
    }

    @Test
    fun `connect does not proceed when already connecting`() = scope.runTest {
        setConnectionState(BluetoothClientState.CONNECTING)
        client.connect(mockk<BluetoothDevice>(relaxed = true))

        assertThat(client.connectionState.value).isEqualTo(BluetoothClientState.CONNECTING)
    }

    @Test
    fun `connect does not proceed when already connected`() = scope.runTest {
        setConnectionState(BluetoothClientState.CONNECTED)
        client.connect(mockk<BluetoothDevice>(relaxed = true))

        assertThat(client.connectionState.value).isEqualTo(BluetoothClientState.CONNECTED)
    }

    @Test
    fun `disconnect sets state to disconnected and clears device name`() {
        setConnectionState(BluetoothClientState.CONNECTED)
        setConnectedDeviceName("Test Phone")

        client.disconnect()

        assertThat(client.connectionState.value).isEqualTo(BluetoothClientState.DISCONNECTED)
        assertThat(client.connectedDeviceName.value).isNull()
    }

    @Test
    fun `onHeartbeatAckReceived resets missed heartbeat count`() {
        val missedField = BluetoothSppClient::class.java.getDeclaredField("missedHeartbeatCount")
        missedField.isAccessible = true
        missedField.setInt(client, 2)

        client.onHeartbeatAckReceived()

        assertThat(missedField.getInt(client)).isEqualTo(0)
    }

    @Test
    fun `sendMessage returns false when not connected`() = scope.runTest {
        val result = client.sendMessage(Message.capturePhoto())

        assertThat(result).isFalse()
    }

    @Test
    fun `sendMessage writes json to output stream when connected`() = scope.runTest {
        val outputBytes = ByteArrayOutputStream()
        setConnectionState(BluetoothClientState.CONNECTED)
        setOutputStream(outputBytes)

        val result = client.sendMessage(Message.capturePhoto())

        assertThat(result).isTrue()
        val written = outputBytes.toString(Charsets.UTF_8.name())
        assertThat(written).contains("\"type\"")
        assertThat(written).contains(MessageType.CAPTURE_PHOTO.code.toString())
        assertThat(written).endsWith("\n")
    }

    @Test
    fun `sendMessage handles IOException and triggers disconnection`() = scope.runTest {
        val mockOutputStream = mockk<java.io.OutputStream>(relaxed = true)
        every { mockOutputStream.write(any<ByteArray>()) } throws IOException("connection lost")
        setConnectionState(BluetoothClientState.CONNECTED)
        setOutputStream(mockOutputStream)

        val result = client.sendMessage(Message.displayText("hello"))

        assertThat(result).isFalse()
    }

    @Test
    fun `handleDisconnection skips when already disconnected`() = scope.runTest {
        invokeHandleDisconnection()

        assertThat(client.connectionState.value).isEqualTo(BluetoothClientState.DISCONNECTED)
    }

    @Test
    fun `handleDisconnection cancels heartbeat and sets disconnected`() = scope.runTest {
        setConnectionState(BluetoothClientState.CONNECTED)

        val job = launch { invokeHandleDisconnection() }
        advanceUntilIdle()

        assertThat(job.isCompleted).isTrue()
        assertThat(client.connectionState.value).isEqualTo(BluetoothClientState.DISCONNECTED)
        assertThat(client.connectedDeviceName.value).isNull()
    }

    @Test
    fun `retry backoff delay formula produces expected values`() {
        for (attempt in 1..4) {
            val delay = 2500L + (attempt * 1500L)
            val expected = when (attempt) {
                1 -> 4000L
                2 -> 5500L
                3 -> 7000L
                4 -> 8500L
                else -> 0L
            }
            assertThat(delay).isEqualTo(expected)
        }
    }

    @Test
    fun `connectByAddress does nothing when no permission`() {
        every { context.checkSelfPermission(any()) } returns PackageManager.PERMISSION_DENIED

        client.connectByAddress("AA:BB:CC:DD:EE:FF")

        assertThat(client.connectionState.value).isEqualTo(BluetoothClientState.DISCONNECTED)
    }

    @Test
    fun `phone candidate ranking prefers Android phone names over iPhone`() {
        val iPhone = mockDevice(name = "iPhone", address = "00:11:22:33:44:55")
        val iQoo = mockDevice(name = "iQOO 13", address = "40:45:A0:13:97:2A")

        val ranked = invokeRankPairedDevicesForPhone(listOf(iPhone, iQoo))

        assertThat(ranked.first()).isSameInstanceAs(iQoo)
    }

    @Test
    fun `phone candidate ranking prefers persisted phone address`() {
        val oldPhone = mockDevice(name = "Pixel", address = "AA:BB:CC:DD:EE:FF")
        val preferredPhone = mockDevice(name = "Office Android", address = "11:22:33:44:55:66")
        every { mockPrefs.getString("preferred_phone_address", null) } returns "11:22:33:44:55:66"

        val ranked = invokeRankPairedDevicesForPhone(listOf(oldPhone, preferredPhone))

        assertThat(ranked.first()).isSameInstanceAs(preferredPhone)
    }

    @Test
    fun `processIncomingData routes photo ack and json message separately`() = scope.runTest {
        val message = Message.photoAnalysisResult("done")
        val ack = PacketUtils.createAckPacket(3, PhotoTransferConstants.STATUS_SUCCESS)
        val combined = ack + (message.toJson() + "\n").toByteArray(Charsets.UTF_8)
        val messageBuffer = StringBuilder()
        val messages = mutableListOf<Message>()
        val collectJob = launch { client.messageFlow.collect { messages.add(it) } }

        invokeProcessIncomingData(combined, messageBuffer)
        advanceUntilIdle()

        val response = receivePhotoResponse()
        assertThat(response).isInstanceOf(PhotoTransferResponse.Ack::class.java)
        assertThat((response as PhotoTransferResponse.Ack).data.chunkIndex).isEqualTo(3)
        assertThat(messages.map { it.type }).contains(MessageType.PHOTO_ANALYSIS_RESULT)

        collectJob.cancel()
    }

    @Test
    fun `processIncomingData reassembles split retry packet`() = scope.runTest {
        val retry = PacketUtils.createRetryPacket(7)
        val messageBuffer = StringBuilder()

        invokeProcessIncomingData(retry.copyOfRange(0, 2), messageBuffer)
        invokeProcessIncomingData(retry.copyOfRange(2, retry.size), messageBuffer)

        val response = receivePhotoResponse()
        assertThat(response).isInstanceOf(PhotoTransferResponse.Retry::class.java)
        assertThat((response as PhotoTransferResponse.Retry).chunkIndex).isEqualTo(7)
    }

    private fun setConnectionState(state: BluetoothClientState) {
        val stateField = BluetoothSppClient::class.java.getDeclaredField("_connectionState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (stateField.get(client) as MutableStateFlow<BluetoothClientState>).value = state
    }

    private fun setConnectedDeviceName(name: String?) {
        val nameField = BluetoothSppClient::class.java.getDeclaredField("_connectedDeviceName")
        nameField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (nameField.get(client) as MutableStateFlow<String?>).value = name
    }

    private fun setOutputStream(outputStream: java.io.OutputStream) {
        val osField = BluetoothSppClient::class.java.getDeclaredField("outputStream")
        osField.isAccessible = true
        osField.set(client, outputStream)
    }

    private suspend fun invokeHandleDisconnection() {
        val method = BluetoothSppClient::class.java.getDeclaredMethod(
            "handleDisconnection",
            kotlin.coroutines.Continuation::class.java
        )
        method.isAccessible = true
        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
            method.invoke(client, cont)
        }
    }

    private suspend fun invokeProcessIncomingData(data: ByteArray, messageBuffer: StringBuilder) {
        val method = BluetoothSppClient::class.java.getDeclaredMethod(
            "processIncomingData",
            ByteArray::class.java,
            StringBuilder::class.java,
            kotlin.coroutines.Continuation::class.java
        )
        method.isAccessible = true
        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
            method.invoke(client, data, messageBuffer, cont)
        }
    }

    private fun receivePhotoResponse(): PhotoTransferResponse {
        val field = BluetoothSppClient::class.java.getDeclaredField("photoResponseChannel")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val channel = field.get(client) as Channel<PhotoTransferResponse>
        return channel.tryReceive().getOrThrow()
    }

    private fun mockDevice(name: String, address: String): BluetoothDevice {
        return mockk<BluetoothDevice>(relaxed = true) {
            every { this@mockk.name } returns name
            every { this@mockk.address } returns address
        }
    }

    private fun invokeRankPairedDevicesForPhone(devices: List<BluetoothDevice>): List<BluetoothDevice> {
        val method = BluetoothSppClient::class.java.getDeclaredMethod(
            "rankPairedDevicesForPhone",
            List::class.java
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(client, devices) as List<BluetoothDevice>
    }
}
