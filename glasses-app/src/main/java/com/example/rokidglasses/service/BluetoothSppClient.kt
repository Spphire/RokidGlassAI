package com.example.rokidglasses.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.example.rokidcommon.protocol.Message
import com.example.rokidcommon.protocol.MessageType
import com.example.rokidcommon.protocol.photo.PacketUtils
import com.example.rokidcommon.protocol.photo.PhotoTransferConstants
import com.example.rokidglasses.service.photo.PhotoTransferProtocol
import com.example.rokidglasses.service.photo.PhotoTransferResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID

/**
 * Bluetooth Connection State
 */
enum class BluetoothClientState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

/**
 * Glasses-side Bluetooth SPP client.
 * Responsible for connecting to the phone and exchanging photo AI control messages.
 */
class BluetoothSppClient(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "BluetoothSppClient"
        
        // Same UUID as phone-side
        val SERVICE_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        
        // Message delimiter
        private const val MESSAGE_DELIMITER = "\n"
        private const val HANDSHAKE_TIMEOUT_MS = 4_000L
        private const val PREFS_NAME = "rokid_glasses_bluetooth"
        private const val KEY_PREFERRED_PHONE_ADDRESS = "preferred_phone_address"
        private const val KEY_PREFERRED_PHONE_NAME = "preferred_phone_name"
        private const val MAX_JSON_MESSAGE_CHARS = 64 * 1024

        private val PHONE_NAME_HINTS = mapOf(
            "iqoo" to 250,
            "vivo" to 220,
            "pixel" to 200,
            "xiaomi" to 190,
            "redmi" to 190,
            "oppo" to 180,
            "oneplus" to 180,
            "huawei" to 180,
            "honor" to 180,
            "samsung" to 180,
            "android" to 120,
            "phone" to 20
        )
        private val NON_ANDROID_PHONE_NAME_HINTS = listOf(
            "iphone",
            "ipad"
        )

        private val PHOTO_RESPONSE_PACKET_TYPES = setOf(
            PhotoTransferConstants.PACKET_TYPE_ACK,
            PhotoTransferConstants.PACKET_TYPE_RETRY
        )
    }
    
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }
    
    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private val sendMutex = Mutex()
    
    private var connectJob: Job? = null
    private var readJob: Job? = null
    private var heartbeatJob: Job? = null
    private val handshakeAckChannel = Channel<Unit>(Channel.CONFLATED)
    private val photoResponseChannel = Channel<PhotoTransferResponse>(Channel.BUFFERED)
    private val photoResponseBuffer = ByteArrayOutputStream(PhotoTransferConstants.ACK_PACKET_SIZE)
    private var expectedPhotoResponseLength = 0
    private var parsingPhotoResponse = false
    
    // Heartbeat interval (10 seconds)
    private val HEARTBEAT_INTERVAL = 10_000L
    
    // Maximum missed heartbeats before triggering reconnection
    private val MAX_MISSED_HEARTBEATS = 3
    
    // Counter for heartbeats sent without receiving ACK
    @Volatile
    private var missedHeartbeatCount = 0
    
    // Last device we were connected to (for auto-reconnect)
    private var lastConnectedDevice: BluetoothDevice? = null

    /**
     * Safely get the device name with BLUETOOTH_CONNECT permission check.
     * Returns "unknown" if the permission is not granted.
     */
    private fun getSafeDeviceName(device: BluetoothDevice): String {
        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        return if (hasPermission) device.name ?: "unknown" else "unknown (missing permission)"
    }
    
    // Connection state
    private val _connectionState = MutableStateFlow(BluetoothClientState.DISCONNECTED)
    val connectionState: StateFlow<BluetoothClientState> = _connectionState.asStateFlow()
    
    // Received message flow
    private val _messageFlow = MutableSharedFlow<Message>(extraBufferCapacity = 16)
    val messageFlow: SharedFlow<Message> = _messageFlow.asSharedFlow()
    
    // Connected device name
    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()
    
    /**
     * Get list of paired devices
     */
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        if (!hasBluetoothPermission()) {
            Log.w(TAG, "No Bluetooth permission")
            return emptyList()
        }
        
        return filterSupportedPhoneCandidates(bluetoothAdapter?.bondedDevices?.toList().orEmpty())
    }

    /**
     * Test/deployment helper: connect to a paired device by address or partial name.
     */
    @SuppressLint("MissingPermission")
    fun connectToPairedDevice(
        address: String? = null,
        nameQuery: String? = null,
        maxRetries: Int = 5
    ): Boolean {
        if (!hasBluetoothPermission()) {
            Log.e(TAG, "No Bluetooth permission")
            return false
        }

        val normalizedAddress = address
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.uppercase(Locale.US)
        val normalizedNameQuery = nameQuery?.trim()?.takeIf { it.isNotBlank() }
        val devices = filterSupportedPhoneCandidates(bluetoothAdapter?.bondedDevices?.toList().orEmpty())

        val selectedDevices = if (normalizedAddress != null || normalizedNameQuery != null) {
            devices.filter { device ->
                val deviceAddress = runCatching { device.address.uppercase(Locale.US) }.getOrNull()
                val deviceName = getSafeDeviceName(device)
                val addressMatches = normalizedAddress != null && deviceAddress == normalizedAddress
                val nameMatches = normalizedNameQuery != null &&
                    deviceName.contains(normalizedNameQuery, ignoreCase = true)
                addressMatches || nameMatches
            }.sortedByDescending { device ->
                val deviceAddress = runCatching { device.address.uppercase(Locale.US) }.getOrNull()
                if (normalizedAddress != null && deviceAddress == normalizedAddress) 1 else 0
            }
        } else {
            rankPairedDevicesForPhone(devices)
        }

        if (selectedDevices.isEmpty()) {
            val availableDevices = devices.joinToString { device ->
                "${getSafeDeviceName(device)} (${runCatching { device.address }.getOrDefault("unknown")})"
            }
            Log.w(
                TAG,
                "No paired device matched address=$normalizedAddress name=$normalizedNameQuery. " +
                    "Available: $availableDevices"
            )
            return false
        }

        Log.d(
            TAG,
            "Matched paired device candidates: " + selectedDevices.joinToString { device ->
                "${getSafeDeviceName(device)} " +
                    "(${runCatching { device.address }.getOrDefault("unknown")})"
            }
        )
        connectCandidates(selectedDevices, maxRetries)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun rankPairedDevicesForPhone(devices: List<BluetoothDevice>): List<BluetoothDevice> {
        return filterSupportedPhoneCandidates(devices)
            .sortedByDescending { device -> phoneCandidateScore(device) }
    }

    @SuppressLint("MissingPermission")
    private fun filterSupportedPhoneCandidates(devices: List<BluetoothDevice>): List<BluetoothDevice> {
        return devices.filterNot { device ->
            val deviceName = getSafeDeviceName(device)
            val excluded = NON_ANDROID_PHONE_NAME_HINTS.any { hint ->
                deviceName.contains(hint, ignoreCase = true)
            }
            if (excluded) {
                Log.d(TAG, "Skipping unsupported Bluetooth candidate: $deviceName")
            }
            excluded
        }
    }

    @SuppressLint("MissingPermission")
    private fun phoneCandidateScore(device: BluetoothDevice): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val preferredAddress = prefs.getString(KEY_PREFERRED_PHONE_ADDRESS, null)
            ?.uppercase(Locale.US)
        val preferredName = prefs.getString(KEY_PREFERRED_PHONE_NAME, null)
        val deviceAddress = runCatching { device.address.uppercase(Locale.US) }.getOrNull()
        val deviceName = getSafeDeviceName(device)
        var score = 0
        if (preferredAddress != null && preferredAddress == deviceAddress) {
            score += 1_000
        }
        if (!preferredName.isNullOrBlank() && deviceName.equals(preferredName, ignoreCase = true)) {
            score += 500
        }
        PHONE_NAME_HINTS.forEach { (hint, weight) ->
            if (deviceName.contains(hint, ignoreCase = true)) {
                score += weight
            }
        }
        if (NON_ANDROID_PHONE_NAME_HINTS.any { hint -> deviceName.contains(hint, ignoreCase = true) }) {
            score -= 200
        }
        return score
    }

    @SuppressLint("MissingPermission")
    private fun rememberPreferredPhone(device: BluetoothDevice) {
        val address = runCatching { device.address }.getOrNull()
        val name = getSafeDeviceName(device)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (!address.isNullOrBlank()) {
                    putString(KEY_PREFERRED_PHONE_ADDRESS, address)
                }
                if (name.isNotBlank() && !name.startsWith("unknown", ignoreCase = true)) {
                    putString(KEY_PREFERRED_PHONE_NAME, name)
                }
            }
            .apply()
        Log.d(TAG, "Remembered preferred phone: $name ($address)")
    }

    /**
     * Connect to specified device (with retry mechanism)
     */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, maxRetries: Int = 5) {
        if (!hasBluetoothPermission()) {
            Log.e(TAG, "No Bluetooth permission")
            return
        }

        connectCandidates(listOf(device), maxRetries)
    }

    private fun connectCandidates(devices: List<BluetoothDevice>, maxRetries: Int) {
        if (_connectionState.value == BluetoothClientState.CONNECTING ||
            _connectionState.value == BluetoothClientState.CONNECTED) {
            Log.w(TAG, "Already connecting or connected")
            return
        }

        connectJob?.cancel()
        connectJob = scope.launch(Dispatchers.IO) {
            devices.forEachIndexed { index, device ->
                if (!isActive) return@launch
                if (index > 0) {
                    delay(1_000L)
                }
                if (connectWithRetries(device, maxRetries)) {
                    return@launch
                }
            }

            Log.e(TAG, "All paired phone candidates failed")
            _connectionState.value = BluetoothClientState.DISCONNECTED
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectWithRetries(device: BluetoothDevice, maxRetries: Int): Boolean {
        var lastException: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                _connectionState.value = BluetoothClientState.CONNECTING
                Log.d(TAG, "Connecting to ${getSafeDeviceName(device)}... (attempt $attempt/$maxRetries)")

                // Cancel device discovery to speed up connection
                bluetoothAdapter?.cancelDiscovery()

                // Wait a bit for discovery cancellation to take effect
                delay(200)

                // Close previous socket
                closeSocket()

                // Try multiple socket creation methods
                val candidateSocket = createSocket(device, attempt)
                if (candidateSocket == null) {
                    throw IOException("Failed to create socket")
                }

                // Connect with timeout handling
                Log.d(TAG, "Attempting socket connection...")
                candidateSocket.connect()

                // Verify connection is established
                if (!candidateSocket.isConnected) {
                    throw IOException("Socket not connected after connect() call")
                }

                socket = candidateSocket
                inputStream = candidateSocket.inputStream
                outputStream = candidateSocket.outputStream
                missedHeartbeatCount = 0

                Log.d(TAG, "Socket connected to ${getSafeDeviceName(device)}, waiting for app handshake")
                startReading()
                val handshakeOk = performHandshake()
                if (!handshakeOk) {
                    throw IOException("App handshake timed out")
                }

                _connectionState.value = BluetoothClientState.CONNECTED
                _connectedDeviceName.value = getSafeDeviceName(device)
                lastConnectedDevice = device
                rememberPreferredPhone(device)

                Log.d(TAG, "Connected to ${getSafeDeviceName(device)} after app handshake")

                // Start heartbeat to keep connection alive
                startHeartbeat()
                return true

            } catch (e: Exception) {
                Log.e(TAG, "Connection attempt $attempt failed: ${e.message}", e)
                lastException = e

                // Explicitly close socket and wait for system to release resources
                closeSocket()

                if (attempt < maxRetries) {
                    // Progressive backoff: longer delays for more failed attempts
                    // Start with 3s to give phone server time to restart its listening socket
                    val delayMs = 2500L + (attempt * 1500L)  // 4s, 5.5s, 7s...
                    Log.d(TAG, "Connection failed. Waiting ${delayMs}ms before retry $attempt/$maxRetries...")
                    delay(delayMs)

                    // Cancel any pending discovery operations before retry
                    try {
                        bluetoothAdapter?.cancelDiscovery()
                        delay(300)  // Brief pause for cancellation to take effect
                    } catch (e2: Exception) {
                        Log.w(TAG, "Failed to cancel discovery: ${e2.message}")
                    }
                }
            }
        }

        Log.e(
            TAG,
            "All connection attempts failed for ${getSafeDeviceName(device)}: " +
                (lastException?.message ?: "unknown")
        )
        closeSocket()
        _connectionState.value = BluetoothClientState.DISCONNECTED
        return false
    }

    /**
     * Create socket using different methods based on attempt number
     * Prioritizes UUID-based methods (proper SDP lookup) for reliable channel discovery
     * Falls back to direct channel methods only if UUID methods fail
     */
    @SuppressLint("MissingPermission")
    private fun createSocket(device: BluetoothDevice, attempt: Int): BluetoothSocket? {
        // Try direct channel 4 first on Rokid/iQOO because SDP records can
        // become stale while still returning a connected socket.
        
        return when (attempt) {
            1 -> {
                Log.d(TAG, "Attempt 1: Using reflection with channel 4")
                tryReflectionChannel(device, 4)
            }
            2 -> {
                Log.d(TAG, "Attempt 2: Using createInsecureRfcommSocketToServiceRecord (UUID-based)")
                try {
                    device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
                } catch (e: Exception) {
                    Log.w(TAG, "Insecure UUID method failed: ${e.message}")
                    null
                }
            }
            3 -> {
                Log.d(TAG, "Attempt 3: Using createRfcommSocketToServiceRecord (UUID-based)")
                try {
                    device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                } catch (e: Exception) {
                    Log.w(TAG, "Secure UUID method failed: ${e.message}")
                    null
                }
            }
            4 -> {
                Log.d(TAG, "Attempt 4: Using standard SPP UUID insecure socket")
                try {
                    device.createInsecureRfcommSocketToServiceRecord(PhotoTransferConstants.SPP_UUID)
                } catch (e: Exception) {
                    Log.w(TAG, "Standard SPP insecure UUID method failed: ${e.message}")
                    null
                }
            }
            5 -> {
                Log.d(TAG, "Attempt 5: Using standard SPP secure socket")
                try {
                    device.createRfcommSocketToServiceRecord(PhotoTransferConstants.SPP_UUID)
                } catch (e: Exception) {
                    Log.w(TAG, "Standard SPP secure UUID method failed: ${e.message}")
                    null
                }
            }
            6 -> {
                Log.d(TAG, "Attempt 6: Using reflection with channel 1")
                tryReflectionChannel(device, 1)
            }
            else -> {
                // Last resort: Try insecure direct channels.
                Log.d(TAG, "Attempt $attempt: Using createInsecureRfcommSocket with channel 4")
                try {
                    val method = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.java)
                    method.invoke(device, 4) as BluetoothSocket
                } catch (e: Exception) {
                    Log.w(TAG, "Insecure reflection method failed: ${e.message}")
                    // Last resort: try channel 1 insecure
                    try {
                        val method = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.java)
                        method.invoke(device, 1) as BluetoothSocket
                    } catch (e2: Exception) {
                        Log.w(TAG, "All connection methods exhausted")
                        null
                    }
                }
            }
        }
    }
    
    /**
     * Try to create socket using reflection with specific channel
     */
    private fun tryReflectionChannel(device: BluetoothDevice, channel: Int): BluetoothSocket? {
        return try {
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
            method.invoke(device, channel) as BluetoothSocket
        } catch (e: Exception) {
            Log.w(TAG, "Reflection method failed for channel $channel: ${e.message}")
            null
        }
    }
    
    /**
     * Connect by device address
     */
    @SuppressLint("MissingPermission")
    fun connectByAddress(address: String) {
        if (!hasBluetoothPermission()) {
            Log.e(TAG, "No Bluetooth permission")
            return
        }
        
        val device = bluetoothAdapter?.getRemoteDevice(address)
        if (device != null) {
            connect(device)
        } else {
            Log.e(TAG, "Device not found: $address")
        }
    }
    
    /**
     * Disconnect
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting...")
        
        connectJob?.cancel()
        readJob?.cancel()
        heartbeatJob?.cancel()
        
        closeSocket()
        
        _connectionState.value = BluetoothClientState.DISCONNECTED
        _connectedDeviceName.value = null
    }
    
    /**
     * Start heartbeat to keep connection alive
     * Sends HEARTBEAT message every 10 seconds
     * Detects connection loss if too many heartbeats go unanswered
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        missedHeartbeatCount = 0
        
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive && _connectionState.value == BluetoothClientState.CONNECTED) {
                delay(HEARTBEAT_INTERVAL)
                
                if (_connectionState.value == BluetoothClientState.CONNECTED) {
                    try {
                        // Increment missed heartbeat count before sending
                        // This will be reset to 0 when we receive HEARTBEAT_ACK
                        missedHeartbeatCount++
                        
                        val heartbeatMsg = Message(type = MessageType.HEARTBEAT)
                        sendMessage(heartbeatMsg)
                        Log.d(TAG, "Heartbeat sent (missed count: $missedHeartbeatCount)")
                        
                        // Check if too many heartbeats were missed
                        if (missedHeartbeatCount >= MAX_MISSED_HEARTBEATS) {
                            Log.w(TAG, "Too many missed heartbeats ($missedHeartbeatCount), connection may be dead")
                            // Trigger reconnection
                            handleConnectionLost()
                            break
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send heartbeat: ${e.message}")
                    }
                }
            }
        }
    }
    
    /**
     * Called when HEARTBEAT_ACK is received
     * Resets the missed heartbeat counter
     */
    fun onHeartbeatAckReceived() {
        missedHeartbeatCount = 0
    }
    
    /**
     * Handle connection lost (too many missed heartbeats)
     * Attempts to reconnect to the last connected device
     */
    private suspend fun handleConnectionLost() {
        Log.d(TAG, "Handling connection lost...")
        
        val deviceToReconnect = lastConnectedDevice
        
        // Close current connection
        closeSocket()
        _connectionState.value = BluetoothClientState.DISCONNECTED
        
        // Try to reconnect if we have a device
        if (deviceToReconnect != null) {
            Log.d(TAG, "Attempting to reconnect to ${getSafeDeviceName(deviceToReconnect)}...")
            delay(1000) // Wait a bit before reconnecting
            connect(deviceToReconnect)
        }
    }
    
    /**
     * Send message
     */
    suspend fun sendMessage(message: Message): Boolean {
        if (_connectionState.value != BluetoothClientState.CONNECTED) {
            Log.w(TAG, "Not connected, cannot send message")
            return false
        }
        
        val sent = writeRawMessage(message)
        if (!sent) {
            handleDisconnection()
        }
        return sent
    }

    private suspend fun writeRawMessage(message: Message): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val json = message.toJson()
                val data = (json + MESSAGE_DELIMITER).toByteArray(Charsets.UTF_8)
                writeRawBytes(data)
                
                Log.d(TAG, "Sent message: ${message.type}")
                true
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send message", e)
                false
            }
        }
    }

    private suspend fun writeRawBytes(data: ByteArray) {
        sendMutex.withLock {
            outputStream?.write(data)
            outputStream?.flush()
        }
    }

    private suspend fun performHandshake(): Boolean {
        clearPendingHandshakeAcks()
        val sent = writeRawMessage(Message.handshake("Rokid Glasses"))
        if (!sent) return false

        val confirmed = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
            handshakeAckChannel.receive()
            true
        } == true

        if (confirmed) {
            Log.d(TAG, "App handshake acknowledged")
        } else {
            Log.w(TAG, "App handshake timed out")
        }
        return confirmed
    }
    
    /**
     * Start reading messages
     */
    private fun startReading() {
        readJob?.cancel()
        readJob = scope.launch(Dispatchers.IO) {
            val buffer = StringBuilder()
            val readBuffer = ByteArray(4096)
            
            while (isActive && _connectionState.value != BluetoothClientState.DISCONNECTED) {
                try {
                    val bytesRead = inputStream?.read(readBuffer) ?: -1
                    
                    if (bytesRead == -1) {
                        Log.d(TAG, "Stream closed")
                        break
                    }
                    
                    processIncomingData(readBuffer.copyOf(bytesRead), buffer)
                    
                } catch (e: IOException) {
                    Log.e(TAG, "Read error", e)
                    break
                }
            }
            
            if (_connectionState.value == BluetoothClientState.CONNECTED) {
                handleDisconnection()
            } else {
                Log.d(TAG, "Read loop exited while state=${_connectionState.value}")
            }
        }
    }

    private suspend fun processIncomingData(data: ByteArray, messageBuffer: StringBuilder) {
        var offset = 0

        while (offset < data.size) {
            if (parsingPhotoResponse) {
                val remaining = expectedPhotoResponseLength - photoResponseBuffer.size()
                val bytesToRead = minOf(remaining, data.size - offset)
                photoResponseBuffer.write(data, offset, bytesToRead)
                offset += bytesToRead

                if (photoResponseBuffer.size() >= expectedPhotoResponseLength) {
                    val packet = photoResponseBuffer.toByteArray()
                    photoResponseBuffer.reset()
                    parsingPhotoResponse = false
                    expectedPhotoResponseLength = 0
                    emitPhotoTransferResponse(packet)
                }
                continue
            }

            val firstByte = data[offset]
            if (firstByte in PHOTO_RESPONSE_PACKET_TYPES) {
                val packetLength = getPhotoResponsePacketLength(firstByte)
                photoResponseBuffer.reset()
                expectedPhotoResponseLength = packetLength
                parsingPhotoResponse = true

                val bytesToRead = minOf(packetLength, data.size - offset)
                photoResponseBuffer.write(data, offset, bytesToRead)
                offset += bytesToRead

                if (photoResponseBuffer.size() >= packetLength) {
                    val packet = photoResponseBuffer.toByteArray()
                    photoResponseBuffer.reset()
                    parsingPhotoResponse = false
                    expectedPhotoResponseLength = 0
                    emitPhotoTransferResponse(packet)
                }
                continue
            }

            val nextPhotoResponse = findNextPhotoResponseOffset(data, offset)
            val textEnd = if (nextPhotoResponse >= 0) nextPhotoResponse else data.size
            if (textEnd > offset) {
                messageBuffer.append(String(data, offset, textEnd - offset, Charsets.UTF_8))
                if (messageBuffer.length > MAX_JSON_MESSAGE_CHARS) {
                    Log.w(TAG, "Incoming JSON message exceeded ${MAX_JSON_MESSAGE_CHARS} chars; disconnecting")
                    handleDisconnection()
                    return
                }
                offset = textEnd
                processCompleteJsonMessages(messageBuffer)
            } else {
                offset++
            }
        }

        processCompleteJsonMessages(messageBuffer)
    }

    private fun getPhotoResponsePacketLength(packetType: Byte): Int {
        return when (packetType) {
            PhotoTransferConstants.PACKET_TYPE_ACK -> PhotoTransferConstants.ACK_PACKET_SIZE
            PhotoTransferConstants.PACKET_TYPE_RETRY -> PhotoTransferConstants.RETRY_PACKET_SIZE
            else -> 0
        }
    }

    private fun findNextPhotoResponseOffset(data: ByteArray, startOffset: Int): Int {
        for (i in startOffset until data.size) {
            if (data[i] in PHOTO_RESPONSE_PACKET_TYPES) {
                return i
            }
        }
        return -1
    }

    private suspend fun emitPhotoTransferResponse(packet: ByteArray) {
        try {
            when (PacketUtils.parsePacketType(packet)) {
                PhotoTransferConstants.PACKET_TYPE_ACK -> {
                    val ack = PacketUtils.parseAckPacket(packet)
                    Log.d(TAG, "Received photo ACK: $ack")
                    photoResponseChannel.trySend(PhotoTransferResponse.Ack(ack))
                }
                PhotoTransferConstants.PACKET_TYPE_RETRY -> {
                    val chunkIndex = PacketUtils.parseRetryPacket(packet)
                    Log.d(TAG, "Received photo RETRY: chunk=$chunkIndex")
                    photoResponseChannel.trySend(PhotoTransferResponse.Retry(chunkIndex))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse photo transfer response", e)
        }
    }

    private suspend fun processCompleteJsonMessages(messageBuffer: StringBuilder) {
        var delimiterIndex: Int
        while (messageBuffer.indexOf(MESSAGE_DELIMITER).also { delimiterIndex = it } >= 0) {
            val messageStr = messageBuffer.substring(0, delimiterIndex)
            messageBuffer.delete(0, delimiterIndex + MESSAGE_DELIMITER.length)

            if (messageStr.isNotBlank()) {
                if (messageStr.length > MAX_JSON_MESSAGE_CHARS) {
                    Log.w(TAG, "Dropping oversized JSON message: ${messageStr.length} chars")
                    handleDisconnection()
                    return
                }
                parseAndEmitMessage(messageStr)
            }
        }
    }
    
    /**
     * Parse and emit message
     */
    private suspend fun parseAndEmitMessage(jsonStr: String) {
        // Skip non-JSON data (binary photo transfer ACKs, etc.)
        val trimmed = jsonStr.trim()
        
        // Try to find JSON object in the string (may have binary prefix)
        val jsonStart = trimmed.indexOf('{')
        if (jsonStart < 0) {
            // No JSON found, silently skip (likely binary data)
            return
        }
        
        val jsonContent = if (jsonStart > 0) {
            // Extract JSON part, discard binary prefix
            trimmed.substring(jsonStart)
        } else {
            trimmed
        }
        
        try {
            val json = JSONObject(jsonContent)
            val typeValue = json.optInt("type", -1)
            val payload = if (json.has("payload")) json.getString("payload") else null
            
            // Handle binaryData (Base64 encoded)
            val binaryData = if (json.has("binaryData")) {
                try {
                    android.util.Base64.decode(json.getString("binaryData"), android.util.Base64.DEFAULT)
                } catch (e: Exception) {
                    null
                }
            } else null
            
            val type = MessageType.fromCode(typeValue)
            if (type != null) {
                // Handle HEARTBEAT_ACK internally to reset missed heartbeat counter
                if (type == MessageType.HEARTBEAT_ACK) {
                    onHeartbeatAckReceived()
                }
                if (type == MessageType.HANDSHAKE_ACK) {
                    handshakeAckChannel.trySend(Unit)
                }
                
                val message = Message(
                    type = type,
                    payload = payload,
                    binaryData = binaryData
                )
                
                Log.d(TAG, "Received message: $type, payload: $payload")
                _messageFlow.emit(message)
            }
            
        } catch (e: Exception) {
            // Only log if it looked like JSON but failed to parse
            if (jsonContent.length < 500) {
                Log.w(TAG, "Failed to parse JSON message: ${jsonContent.take(100)}")
            } else {
                Log.w(TAG, "Failed to parse large message (${jsonContent.length} chars)")
            }
        }
    }
    
    /**
     * Handle disconnection and attempt auto-reconnect
     */
    private suspend fun handleDisconnection() {
        if (_connectionState.value == BluetoothClientState.DISCONNECTED) return
        
        Log.d(TAG, "Handling disconnection...")
        
        // Cancel heartbeat so it doesn't interfere with reconnection
        heartbeatJob?.cancel()
        heartbeatJob = null
        
        closeSocket()
        clearPendingPhotoTransferResponses()
        
        _connectionState.value = BluetoothClientState.DISCONNECTED
        _connectedDeviceName.value = null
        
        // Auto-reconnect to the last connected device
        val deviceToReconnect = lastConnectedDevice
        if (deviceToReconnect != null) {
            Log.d(TAG, "Will auto-reconnect to ${getSafeDeviceName(deviceToReconnect)} in 2s...")
            // Wait for the phone server to restart its listening socket
            delay(2000)
            // Only reconnect if still disconnected (user may have manually triggered something)
            if (_connectionState.value == BluetoothClientState.DISCONNECTED) {
                connect(deviceToReconnect)
            }
        }
    }
    
    /**
     * Close socket and release all resources
     * Ensures proper cleanup to avoid "socket might closed" errors
     */
    private fun closeSocket() {
        Log.d(TAG, "Closing socket and releasing resources...")
        
        // Close streams first (order matters)
        try {
            outputStream?.flush()  // Flush any pending data
        } catch (e: Exception) {
            Log.w(TAG, "Failed to flush output stream: ${e.message}")
        }
        
        try {
            inputStream?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing input stream: ${e.message}")
        }
        
        try {
            outputStream?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing output stream: ${e.message}")
        }
        
        // Close socket last
        try {
            socket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing socket: ${e.message}")
        }
        
        // Clear references
        inputStream = null
        outputStream = null
        socket = null
        photoResponseBuffer.reset()
        parsingPhotoResponse = false
        expectedPhotoResponseLength = 0
        clearPendingHandshakeAcks()
        
        Log.d(TAG, "Socket closed and resources released")
    }
    
    /**
     * Check Bluetooth permission
     */
    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true // On Android 11 and below, BLUETOOTH permissions are install-time
        }
    }
    
    /**
     * Check if Bluetooth is enabled
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }
    
    /**
     * The underlying Bluetooth socket for photo transfer.
     * Returns null if not connected.
     */
    val connectedSocket: BluetoothSocket?
        get() = if (_connectionState.value == BluetoothClientState.CONNECTED) socket else null

    fun createPhotoTransferProtocol(
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): PhotoTransferProtocol? {
        val activeSocket = connectedSocket ?: return null
        return PhotoTransferProtocol(
            bluetoothSocket = activeSocket,
            onProgress = onProgress,
            receiveResponse = { photoResponseChannel.receive() },
            clearPendingResponses = { clearPendingPhotoTransferResponses() },
            writePacketOverride = { packet -> writeRawBytes(packet) }
        )
    }

    private fun clearPendingPhotoTransferResponses() {
        while (photoResponseChannel.tryReceive().isSuccess) {
            // Drain stale ACK/RETRY packets from a previous transfer.
        }
    }

    private fun clearPendingHandshakeAcks() {
        while (handshakeAckChannel.tryReceive().isSuccess) {
            // Drain stale handshake acknowledgements from a previous socket.
        }
    }
}
