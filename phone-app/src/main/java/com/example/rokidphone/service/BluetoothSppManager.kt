package com.example.rokidphone.service

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import com.example.rokidcommon.protocol.Message
import com.example.rokidcommon.protocol.MessageType
import com.example.rokidcommon.protocol.photo.PhotoTransferConstants
import com.example.rokidcommon.protocol.photo.PhotoTransferState
import com.example.rokidphone.service.photo.BluetoothPhotoReceiver
import com.example.rokidphone.service.photo.ReceivedPhoto
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Bluetooth connection state
 */
enum class BluetoothConnectionState {
    DISCONNECTED,
    LISTENING,
    CONNECTING,
    CONNECTED
}

/**
 * Bluetooth SPP Manager
 * Uses Classic Bluetooth Serial Port Profile for communication between glasses and phone
 */
class BluetoothSppManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "BluetoothSppManager"
        private const val SERVICE_NAME = "RokidAIAssistant"
        // SPP UUID
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        // Custom UUID (to identify our application)
        private val APP_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        
        private const val BUFFER_SIZE = 8192
        private const val CONNECTED_STALE_MS = 35_000L
        private const val HEALTH_PROBE_GRACE_MS = 5_000L
        private const val LISTENING_REFRESH_MS = 30_000L
        private const val LISTENER_RESTART_DELAY_MS = 250L
        private const val PRIMARY_RFCOMM_CHANNEL = 4
        private const val FALLBACK_RFCOMM_CHANNEL = 1
        
        // Binary packet header bytes (photo transfer protocol)
        private val PHOTO_PACKET_TYPES = setOf<Byte>(
            PhotoTransferConstants.PACKET_TYPE_START,
            PhotoTransferConstants.PACKET_TYPE_DATA,
            PhotoTransferConstants.PACKET_TYPE_END
        )
    }
    
    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    
    private var serverSocket: BluetoothServerSocket? = null
    private var serverSocketLabel: String? = null
    private var clientSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private val sendMutex = Mutex()
    
    private var acceptJob: Job? = null
    private var readJob: Job? = null
    private var healthProbeJob: Job? = null
    private var listenerRestartJob: Job? = null
    @Volatile
    private var lastInboundElapsedMs: Long = SystemClock.elapsedRealtime()
    @Volatile
    private var lastListenStartElapsedMs: Long = 0L
    
    // Connection state
    private val _connectionState = MutableStateFlow(BluetoothConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BluetoothConnectionState> = _connectionState.asStateFlow()
    
    // Received messages
    private val _messageFlow = MutableSharedFlow<Message>(replay = 0, extraBufferCapacity = 100)
    val messageFlow: SharedFlow<Message> = _messageFlow.asSharedFlow()
    
    // Connected device name
    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()
    
    // Connected BluetoothDevice (for CXR SDK initialization)
    private var _connectedDevice: BluetoothDevice? = null
    val connectedDevice: BluetoothDevice? get() = _connectedDevice

    // Photo receiver for handling chunked photo transfer
    private val photoReceiver = BluetoothPhotoReceiver(scope)
    
    // Photo transfer state
    val photoTransferState: StateFlow<PhotoTransferState> = photoReceiver.transferState
    
    // Received photos (emitted when a complete photo is received)
    val receivedPhoto: Flow<ReceivedPhoto> = photoReceiver.receivedPhoto
    
    // Binary packet buffer for photo transfer (use ByteArrayOutputStream for efficiency)
    private var binaryBuffer = ByteArrayOutputStream(8192)
    private var expectedPacketLength: Int = 0
    private var parsingBinaryPacket = false
    private var pendingBinaryPacketType: Byte? = null
    
    // Flag to prevent duplicate disconnect
    @Volatile
    private var isDisconnecting = false
    private val disconnectLock = Any()

    /**
     * Check Bluetooth permission
     */
    fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
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

    fun ensureListening(reason: String) {
        recordRuntimeState("ensureListening: $reason")
        if (!hasBluetoothPermission()) {
            Log.w(TAG, "Cannot ensure listening for $reason: missing Bluetooth permission")
            recordRuntimeState("ensureListening missing permission: $reason")
            return
        }

        if (!isBluetoothEnabled()) {
            Log.w(TAG, "Cannot ensure listening for $reason: Bluetooth is disabled")
            recordRuntimeState("ensureListening bluetooth disabled: $reason")
            when (_connectionState.value) {
                BluetoothConnectionState.LISTENING -> stopListening()
                BluetoothConnectionState.CONNECTED,
                BluetoothConnectionState.CONNECTING -> disconnect(restartListening = false)
                BluetoothConnectionState.DISCONNECTED -> Unit
            }
            return
        }

        when (_connectionState.value) {
            BluetoothConnectionState.DISCONNECTED -> {
                Log.d(TAG, "Ensuring Bluetooth server is listening: $reason")
                startListening()
            }
            BluetoothConnectionState.LISTENING -> {
                if (acceptJob?.isActive == true) {
                    val listeningMs = SystemClock.elapsedRealtime() - lastListenStartElapsedMs
                    if (lastListenStartElapsedMs > 0L && listeningMs >= LISTENING_REFRESH_MS) {
                        Log.w(
                            TAG,
                            "Bluetooth server has been listening for ${listeningMs}ms without accepting; refreshing: $reason"
                        )
                        recordRuntimeState("refreshing stale listener: $reason")
                        restartListening("stale listener after ${listeningMs}ms: $reason")
                    } else {
                        Log.d(TAG, "Bluetooth server already listening: $reason")
                    }
                } else {
                    Log.w(TAG, "Bluetooth server state is LISTENING but accept job is inactive; restarting: $reason")
                    recordRuntimeState("restarting inactive listener: $reason")
                    restartListening("inactive accept job: $reason")
                }
            }
            BluetoothConnectionState.CONNECTING -> {
                Log.d(TAG, "Bluetooth connection is in progress; watchdog leaves it alone: $reason")
            }
            BluetoothConnectionState.CONNECTED -> {
                ensureConnectedSocketHealthy(reason)
            }
        }
    }
    
    /**
     * Start listening for connections (as server)
     * Uses insecure RFCOMM for better compatibility with various devices
     * Continuously accepts reconnections when the current connection is lost
     */
    fun startListening() {
        startListening(cancelPendingRestart = true)
    }

    private fun startListening(cancelPendingRestart: Boolean) {
        if (!hasBluetoothPermission()) {
            Log.e(TAG, "Missing Bluetooth permission")
            return
        }
        
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported")
            return
        }

        if (!isBluetoothEnabled()) {
            Log.e(TAG, "Bluetooth is disabled")
            _connectionState.value = BluetoothConnectionState.DISCONNECTED
            return
        }

        if (cancelPendingRestart) {
            listenerRestartJob?.cancel()
            listenerRestartJob = null
        }

        stopListening(cancelPendingRestart = false)
        recordRuntimeState("startListening scheduling accept loop")
        
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                var activeServerSocket: BluetoothServerSocket? = null
                try {
                    _connectionState.value = BluetoothConnectionState.LISTENING
                    lastListenStartElapsedMs = SystemClock.elapsedRealtime()
                    Log.d(TAG, "Starting Bluetooth server...")
                    recordRuntimeState("accept loop starting server")
                    
                    // Prefer direct channels on this hardware path because some
                    // phone stacks return a connected socket from stale SDP records
                    // without ever delivering it to this app's server socket.
                    val serverCandidate = createServerSocketOnChannel(PRIMARY_RFCOMM_CHANNEL, secure = false)
                        ?: createServerSocket(APP_UUID, "custom app")
                        ?: createServerSocketOnChannel(PRIMARY_RFCOMM_CHANNEL, secure = true)
                        ?: createServerSocket(SPP_UUID, "standard SPP")
                        ?: createServerSocketOnChannel(FALLBACK_RFCOMM_CHANNEL, secure = false)
                        ?: error("Unable to create RFCOMM server socket")
                    activeServerSocket = serverCandidate.socket
                    serverSocket = activeServerSocket
                    serverSocketLabel = serverCandidate.label
                    recordRuntimeState("server socket created")
                    
                    Log.d(TAG, "Waiting for connection...")
                    
                    // Wait for connection
                    val socket = activeServerSocket?.accept()
                    
                    if (socket != null) {
                        Log.d(TAG, "Connection accepted from: ${socket.remoteDevice.name}")
                        recordRuntimeState("connection accepted")
                        closeServerSocket(activeServerSocket)
                        if (serverSocket === activeServerSocket) {
                            serverSocket = null
                            serverSocketLabel = null
                        }
                        activeServerSocket = null
                        handleConnection(socket)
                        
                        // Wait for this connection to be disconnected before accepting new ones
                        // The read job will handle disconnection detection
                        readJob?.join()
                        
                        Log.d(TAG, "Connection ended, preparing to accept new connections...")
                        
                        // Wait for BT stack to fully release the socket resources
                        // before re-creating the server socket.
                        // The glasses client waits 2s before reconnecting, so 1s here is safe.
                        delay(1000)
                    }
                    
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security exception", e)
                    recordRuntimeState("security exception: ${e.message}")
                    _connectionState.value = BluetoothConnectionState.DISCONNECTED
                    break // Exit loop on security exception
                } catch (e: IOException) {
                    if (_connectionState.value != BluetoothConnectionState.DISCONNECTED) {
                        Log.e(TAG, "Accept failed: ${e.message}")
                        recordRuntimeState("accept failed: ${e.message}")
                        // Don't break, try to restart the server socket
                        delay(500)
                    } else {
                        // Intentional disconnect, exit the loop
                        break
                    }
                } catch (e: CancellationException) {
                    Log.d(TAG, "Accept job cancelled")
                    recordRuntimeState("accept job cancelled")
                    break
                } finally {
                    // Close server socket to free up the port
                    activeServerSocket?.let(::closeServerSocket)
                    if (serverSocket === activeServerSocket) {
                        serverSocket = null
                        serverSocketLabel = null
                    }
                }
            }
            
            Log.d(TAG, "Accept loop exited")
            recordRuntimeState("accept loop exited")
        }
    }

    fun restartListening(reason: String) {
        if (!hasBluetoothPermission()) {
            Log.w(TAG, "Cannot restart listener for $reason: missing Bluetooth permission")
            return
        }

        if (!isBluetoothEnabled()) {
            Log.w(TAG, "Cannot restart listener for $reason: Bluetooth is disabled")
            stopListening()
            return
        }

        if (_connectionState.value == BluetoothConnectionState.CONNECTED ||
            _connectionState.value == BluetoothConnectionState.CONNECTING
        ) {
            Log.d(TAG, "Listener restart skipped because connection is active: $reason")
            return
        }

        if (listenerRestartJob?.isActive == true) {
            Log.d(TAG, "Listener restart already scheduled: $reason")
            return
        }

        Log.w(TAG, "Restarting Bluetooth server listener: $reason")
        recordRuntimeState("restartListening: $reason")
        listenerRestartJob = scope.launch(Dispatchers.IO) {
            stopListening(cancelPendingRestart = false)
            delay(LISTENER_RESTART_DELAY_MS)
            if (isActive &&
                _connectionState.value != BluetoothConnectionState.CONNECTED &&
                _connectionState.value != BluetoothConnectionState.CONNECTING &&
                isBluetoothEnabled()
            ) {
                startListening(cancelPendingRestart = false)
            }
        }
    }
    
    /**
     * Connect to specified device (as client)
     */
    fun connectToDevice(device: BluetoothDevice) {
        if (!hasBluetoothPermission()) {
            Log.e(TAG, "Missing Bluetooth permission")
            return
        }
        
        disconnect(restartListening = false)
        
        scope.launch(Dispatchers.IO) {
            try {
                _connectionState.value = BluetoothConnectionState.CONNECTING
                Log.d(TAG, "Connecting to: ${device.name}")
                
                val socket = device.createRfcommSocketToServiceRecord(APP_UUID)
                
                // Cancel discovery to speed up connection
                bluetoothAdapter?.cancelDiscovery()
                
                socket.connect()
                
                Log.d(TAG, "Connected to: ${device.name}")
                handleConnection(socket)
                
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception", e)
                _connectionState.value = BluetoothConnectionState.DISCONNECTED
            } catch (e: IOException) {
                Log.e(TAG, "Connection failed", e)
                _connectionState.value = BluetoothConnectionState.DISCONNECTED
            }
        }
    }
    
    private suspend fun handleConnection(socket: BluetoothSocket) {
        clientSocket = socket
        inputStream = socket.inputStream
        outputStream = socket.outputStream
        
        // Set output stream for photo receiver (for ACK/RETRY responses)
        photoReceiver.setOutputStream(outputStream) { packet ->
            writeRawBytes(packet)
        }
        
        try {
            _connectedDevice = socket.remoteDevice
            _connectedDeviceName.value = socket.remoteDevice.name
        } catch (e: SecurityException) {
            _connectedDevice = socket.remoteDevice
            _connectedDeviceName.value = "Unknown device"
        }
        
        _connectionState.value = BluetoothConnectionState.CONNECTED
        lastInboundElapsedMs = SystemClock.elapsedRealtime()
        Log.d(TAG, "Connection established")
        recordRuntimeState("connection established")
        
        // Start reading data
        startReading()
    }
    
    private fun startReading() {
        readJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(BUFFER_SIZE)
            val messageBuffer = StringBuilder()
            
            try {
                while (isActive && _connectionState.value == BluetoothConnectionState.CONNECTED) {
                    val bytesRead = inputStream?.read(buffer) ?: -1
                    
                    if (bytesRead == -1) {
                        Log.d(TAG, "Connection closed by remote")
                        break
                    }
                    
                    if (bytesRead > 0) {
                        lastInboundElapsedMs = SystemClock.elapsedRealtime()
                        // Process received data
                        val data = buffer.copyOf(bytesRead)
                        processReceivedData(data, messageBuffer)
                    }
                }
            } catch (e: IOException) {
                // Only log error if not actively disconnecting
                if (_connectionState.value == BluetoothConnectionState.CONNECTED) {
                    Log.e(TAG, "Read error", e)
                }
            } finally {
                // Only call disconnect if still connected (avoid duplicate calls)
                if (_connectionState.value == BluetoothConnectionState.CONNECTED) {
                    handleDisconnection()
                }
            }
        }
    }
    
    private suspend fun processReceivedData(data: ByteArray, messageBuffer: StringBuilder) {
        try {
            var offset = 0
            while (offset < data.size) {
                // Check if we're continuing to parse a binary photo packet
                if (parsingBinaryPacket) {
                    if (expectedPacketLength == 0) {
                        val packetType = pendingBinaryPacketType ?: binaryBuffer.toByteArray().firstOrNull()
                        val neededForLength = neededBytesForPacketLength(packetType)
                        val bytesToRead = minOf(neededForLength - binaryBuffer.size(), data.size - offset)
                        if (bytesToRead > 0) {
                            binaryBuffer.write(data, offset, bytesToRead)
                            offset += bytesToRead
                        }

                        if (binaryBuffer.size() < neededForLength) {
                            continue
                        }

                        val buffered = binaryBuffer.toByteArray()
                        expectedPacketLength = getPacketLength(packetType ?: buffered[0], buffered, 0)
                    }

                    val remaining = expectedPacketLength - binaryBuffer.size()
                    val bytesToRead = minOf(remaining, data.size - offset)
                    // Write bytes in bulk (much more efficient than byte-by-byte)
                    binaryBuffer.write(data, offset, bytesToRead)
                    offset += bytesToRead
                    
                    if (binaryBuffer.size() >= expectedPacketLength) {
                        // Complete binary packet received
                        val packet = binaryBuffer.toByteArray()
                        binaryBuffer.reset()
                        parsingBinaryPacket = false
                        expectedPacketLength = 0
                        pendingBinaryPacketType = null
                        
                        // Process photo packet
                        photoReceiver.processPacket(packet)
                    }
                    continue
                }
                
                // Check first byte to determine if this is a binary photo packet
                val firstByte = data[offset]
                
                if (looksLikePhotoPacket(data, offset)) {
                    // This is a binary photo packet
                    val packetLength = getPacketLength(firstByte, data, offset)

                    // Start collecting binary packet. DATA packet length may be unknown
                    // until the first 3 bytes arrive.
                    binaryBuffer.reset()
                    expectedPacketLength = packetLength
                    parsingBinaryPacket = true
                    pendingBinaryPacketType = firstByte

                    val minimumLengthToRead = if (packetLength > 0) {
                        packetLength
                    } else {
                        neededBytesForPacketLength(firstByte)
                    }
                    val bytesAvailable = data.size - offset
                    val bytesToRead = minOf(minimumLengthToRead, bytesAvailable)

                    // Write bytes in bulk (much more efficient than byte-by-byte)
                    binaryBuffer.write(data, offset, bytesToRead)
                    offset += bytesToRead

                    if (expectedPacketLength == 0 && binaryBuffer.size() >= neededBytesForPacketLength(firstByte)) {
                        expectedPacketLength = getPacketLength(firstByte, binaryBuffer.toByteArray(), 0)
                    }

                    if (expectedPacketLength > 0 && binaryBuffer.size() >= expectedPacketLength) {
                        // Complete packet in this buffer
                        val packet = binaryBuffer.toByteArray()
                        binaryBuffer.reset()
                        parsingBinaryPacket = false
                        expectedPacketLength = 0
                        pendingBinaryPacketType = null

                        photoReceiver.processPacket(packet)
                    }
                    continue
                }
                
                // Regular JSON message processing. A Bluetooth read can contain
                // JSON followed immediately by a binary photo packet.
                val nextPhotoPacket = findNextPhotoPacketOffset(data, offset)
                val textEnd = if (nextPhotoPacket >= 0) nextPhotoPacket else data.size
                if (textEnd > offset) {
                    messageBuffer.append(String(data, offset, textEnd - offset, Charsets.UTF_8))
                    offset = textEnd
                    processCompleteJsonMessages(messageBuffer)
                } else {
                    offset++
                }
            }

            processCompleteJsonMessages(messageBuffer)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing data", e)
        }
    }

    private suspend fun processCompleteJsonMessages(messageBuffer: StringBuilder) {
        // Find complete JSON messages (ending with newline)
        var newlineIndex: Int
        while (messageBuffer.indexOf("\n").also { newlineIndex = it } != -1) {
            val messageJson = messageBuffer.substring(0, newlineIndex)
            messageBuffer.delete(0, newlineIndex + 1)

            if (messageJson.isNotEmpty()) {
                try {
                    val message = Message.fromJson(messageJson)
                    if (message == null) {
                        Log.e(TAG, "Failed to parse message: $messageJson")
                        continue
                    }
                    Log.d(TAG, "Received message: ${message.type}")

                    // Process messages
                    when (message.type) {
                        MessageType.HEARTBEAT -> {
                            // Respond to heartbeat to keep connection alive
                            Log.d(TAG, "Heartbeat received, sending ACK")
                            scope.launch {
                                sendMessage(Message(type = MessageType.HEARTBEAT_ACK))
                            }
                        }
                        MessageType.HANDSHAKE -> {
                            Log.d(TAG, "Handshake received, sending ACK")
                            scope.launch {
                                sendMessage(
                                    Message(
                                        type = MessageType.HANDSHAKE_ACK,
                                        payload = "rokid-phone-ready"
                                    )
                                )
                            }
                            _messageFlow.emit(message)
                        }
                        MessageType.HANDSHAKE_ACK -> {
                            Log.d(TAG, "Handshake ACK received")
                        }
                        MessageType.HEARTBEAT_ACK -> {
                            Log.d(TAG, "Heartbeat ACK received")
                        }
                        else -> {
                            _messageFlow.emit(message)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse message: $messageJson", e)
                }
            }
        }
    }

    private fun findNextPhotoPacketOffset(data: ByteArray, startOffset: Int): Int {
        for (i in startOffset until data.size) {
            if (looksLikePhotoPacket(data, i)) {
                return i
            }
        }
        return -1
    }

    private fun looksLikePhotoPacket(data: ByteArray, offset: Int): Boolean {
        if (offset >= data.size) return false
        return when (data[offset]) {
            PhotoTransferConstants.PACKET_TYPE_START -> {
                if (offset + PhotoTransferConstants.START_PACKET_SIZE > data.size) {
                    true
                } else {
                    val totalSize = ByteBuffer.wrap(data, offset + 1, 4)
                        .order(ByteOrder.BIG_ENDIAN)
                        .int
                    val totalChunks = ByteBuffer.wrap(data, offset + 5, 4)
                        .order(ByteOrder.BIG_ENDIAN)
                        .int
                    totalSize in 1..PhotoTransferConstants.MAX_PHOTO_SIZE &&
                        totalChunks in 1..PhotoTransferConstants.MAX_CHUNKS
                }
            }
            PhotoTransferConstants.PACKET_TYPE_DATA -> {
                if (offset + 3 > data.size) {
                    true
                } else {
                    val dataLength = ByteBuffer.wrap(data, offset + 1, 2)
                        .order(ByteOrder.BIG_ENDIAN)
                        .short.toInt() and 0xFFFF
                    dataLength <= PhotoTransferConstants.CHUNK_SIZE
                }
            }
            PhotoTransferConstants.PACKET_TYPE_END -> {
                if (offset + PhotoTransferConstants.END_PACKET_SIZE > data.size) {
                    true
                } else {
                    data[offset + 1] in setOf(
                        PhotoTransferConstants.STATUS_SUCCESS,
                        PhotoTransferConstants.STATUS_ERROR,
                        PhotoTransferConstants.STATUS_CRC_ERROR,
                        PhotoTransferConstants.STATUS_MD5_ERROR,
                        PhotoTransferConstants.STATUS_TIMEOUT,
                        PhotoTransferConstants.STATUS_OUT_OF_MEMORY
                    )
                }
            }
            else -> false
        }
    }
    
    /**
     * Determines the expected length of a binary photo packet.
     * Returns 0 if length cannot be determined from available data.
     */
    private fun getPacketLength(packetType: Byte, data: ByteArray, offset: Int): Int {
        return when (packetType) {
            PhotoTransferConstants.PACKET_TYPE_START -> {
                // START: [Type:1][TotalSize:4][TotalChunks:4][MD5:16] = 25 bytes
                PhotoTransferConstants.START_PACKET_SIZE
            }
            PhotoTransferConstants.PACKET_TYPE_DATA -> {
                // DATA: [Type:1][DataLength:2][ChunkIndex:4][CRC32:4][Payload:n]
                // We need at least 3 bytes to read DataLength
                if (offset + 3 <= data.size) {
                    val dataLength = ByteBuffer.wrap(data, offset + 1, 2)
                        .order(ByteOrder.BIG_ENDIAN)
                        .short.toInt() and 0xFFFF
                    PhotoTransferConstants.DATA_HEADER_SIZE + dataLength
                } else {
                    0
                }
            }
            PhotoTransferConstants.PACKET_TYPE_END -> {
                // END: [Type:1][Status:1] = 2 bytes
                PhotoTransferConstants.END_PACKET_SIZE
            }
            else -> 0
        }
    }

    private fun neededBytesForPacketLength(packetType: Byte?): Int {
        return when (packetType) {
            PhotoTransferConstants.PACKET_TYPE_DATA -> 3
            PhotoTransferConstants.PACKET_TYPE_START -> PhotoTransferConstants.START_PACKET_SIZE
            PhotoTransferConstants.PACKET_TYPE_END -> PhotoTransferConstants.END_PACKET_SIZE
            else -> 1
        }
    }
    
    /**
     * Send message
     */
    suspend fun sendMessage(message: Message): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (_connectionState.value != BluetoothConnectionState.CONNECTED) {
                    Log.w(TAG, "Not connected, cannot send message")
                    return@withContext false
                }
                
                val json = message.toJson() + "\n"
                writeRawBytes(json.toByteArray(Charsets.UTF_8))
                
                Log.d(TAG, "Sent message: ${message.type}")
                true
            } catch (e: IOException) {
                Log.e(TAG, "Send failed", e)
                disconnect()
                false
            }
        }
    }
    
    /**
     * Stop listening
     */
    fun stopListening() {
        stopListening(cancelPendingRestart = true)
    }

    private fun stopListening(cancelPendingRestart: Boolean) {
        recordRuntimeState("stopListening")
        if (cancelPendingRestart) {
            listenerRestartJob?.cancel()
            listenerRestartJob = null
        }

        healthProbeJob?.cancel()
        healthProbeJob = null
        acceptJob?.cancel()
        acceptJob = null
        
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing server socket", e)
        }
        serverSocket = null
        serverSocketLabel = null
        lastListenStartElapsedMs = 0L

        if (_connectionState.value == BluetoothConnectionState.LISTENING) {
            _connectionState.value = BluetoothConnectionState.DISCONNECTED
        }
    }

    private suspend fun writeRawBytes(data: ByteArray) {
        sendMutex.withLock {
            outputStream?.write(data)
            outputStream?.flush()
        }
    }
    
    /**
     * Handle disconnection from read thread (internal use)
     * The acceptJob loop will automatically accept new connections after readJob completes
     */
    private fun handleDisconnection() {
        synchronized(disconnectLock) {
            if (isDisconnecting) {
                Log.d(TAG, "Already disconnecting, skipping...")
                return
            }
            isDisconnecting = true
        }
        
        Log.d(TAG, "Handling disconnection from read thread...")
        recordRuntimeState("handleDisconnection")
        
        healthProbeJob?.cancel()
        healthProbeJob = null
        clearActiveConnection()
        
        // Reset the flag - the acceptJob loop will automatically accept new connections
        // after readJob completes (readJob?.join() in startListening)
        synchronized(disconnectLock) {
            isDisconnecting = false
        }
        
        Log.d(TAG, "Disconnection handled, ready for reconnection")
    }
    
    /**
     * Disconnect
     * @param restartListening Whether to restart listening after disconnect (default true)
     */
    fun disconnect(restartListening: Boolean = true) {
        synchronized(disconnectLock) {
            if (isDisconnecting) {
                Log.d(TAG, "Already disconnecting, skipping...")
                return
            }
            isDisconnecting = true
        }
        
        Log.d(TAG, "Disconnecting... (restartListening=$restartListening)")
        recordRuntimeState("disconnect restartListening=$restartListening")
        
        // Set state first to stop read thread
        _connectionState.value = BluetoothConnectionState.DISCONNECTED
        
        readJob?.cancel()
        readJob = null
        healthProbeJob?.cancel()
        healthProbeJob = null
        listenerRestartJob?.cancel()
        listenerRestartJob = null
        
        clearActiveConnection()
        
        // Stop old server socket and restart listening with delay
        // Reset flag only AFTER restart completes to prevent race conditions
        if (restartListening) {
            scope.launch(Dispatchers.IO) {
                try {
                    delay(500) // Wait for socket cleanup
                    stopListening()
                    delay(200) // Small delay between stop and start
                    Log.d(TAG, "Restarting Bluetooth server after disconnect...")
                    startListening()
                } finally {
                    // Reset flag after restart completes
                    synchronized(disconnectLock) {
                        isDisconnecting = false
                    }
                }
            }
        } else {
            // If not restarting, reset flag immediately
            synchronized(disconnectLock) {
                isDisconnecting = false
            }
        }
    }
    
    /**
     * Get paired devices list
     */
    fun getPairedDevices(): List<BluetoothDevice> {
        if (!hasBluetoothPermission()) return emptyList()
        
        return try {
            bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception getting paired devices", e)
            emptyList()
        }
    }

    private fun clearActiveConnection() {
        photoReceiver.setOutputStream(null)
        clearBinaryParserState()

        try {
            inputStream?.close()
            outputStream?.close()
            clientSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing connection", e)
        }

        inputStream = null
        outputStream = null
        clientSocket = null

        _connectionState.value = BluetoothConnectionState.DISCONNECTED
        _connectedDevice = null
        _connectedDeviceName.value = null
    }

    private fun ensureConnectedSocketHealthy(reason: String) {
        val readActive = readJob?.isActive == true
        if (!readActive || clientSocket?.isConnected != true || inputStream == null || outputStream == null) {
            Log.w(TAG, "Connected state is missing active socket/read resources; restarting listener: $reason")
            disconnect(restartListening = true)
            return
        }

        val idleMs = SystemClock.elapsedRealtime() - lastInboundElapsedMs
        if (idleMs < CONNECTED_STALE_MS) {
            Log.d(TAG, "Connected socket is healthy: idle=${idleMs}ms reason=$reason")
            return
        }

        if (healthProbeJob?.isActive == true) {
            Log.d(TAG, "Health probe already running: idle=${idleMs}ms reason=$reason")
            return
        }

        healthProbeJob = scope.launch(Dispatchers.IO) {
            val probeStart = SystemClock.elapsedRealtime()
            val lastInboundBeforeProbe = lastInboundElapsedMs
            Log.w(TAG, "Connected socket stale for ${idleMs}ms; probing phone->glasses heartbeat: $reason")
            val sent = sendMessage(Message.heartbeat())
            if (!sent) {
                Log.w(TAG, "Health probe send failed; restarting listener")
                disconnect(restartListening = true)
                return@launch
            }

            delay(HEALTH_PROBE_GRACE_MS)
            val receivedDuringProbe = lastInboundElapsedMs > lastInboundBeforeProbe
            if (_connectionState.value == BluetoothConnectionState.CONNECTED && !receivedDuringProbe) {
                Log.w(
                    TAG,
                    "Health probe unanswered after ${SystemClock.elapsedRealtime() - probeStart}ms; restarting listener"
                )
                disconnect(restartListening = true)
            } else {
                Log.d(TAG, "Health probe succeeded")
            }
        }
    }

    private fun clearBinaryParserState() {
        binaryBuffer.reset()
        parsingBinaryPacket = false
        expectedPacketLength = 0
        pendingBinaryPacketType = null
    }

    private fun closeServerSocket(socket: BluetoothServerSocket) {
        try {
            socket.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing server socket: ${e.message}")
        }
    }

    private fun createServerSocket(uuid: UUID, label: String): ServerSocketCandidate? {
        return try {
            bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, uuid)
                ?.also { Log.d(TAG, "Server socket created ($label insecure)") }
                ?.let { ServerSocketCandidate(it, "$label insecure") }
        } catch (e: Exception) {
            Log.w(TAG, "$label insecure socket failed, trying secure: ${e.message}")
            runCatching {
                bluetoothAdapter?.listenUsingRfcommWithServiceRecord(SERVICE_NAME, uuid)
                    ?.also { Log.d(TAG, "Server socket created ($label secure)") }
                    ?.let { ServerSocketCandidate(it, "$label secure") }
            }.onFailure { secureError ->
                Log.w(TAG, "$label secure socket failed: ${secureError.message}")
            }.getOrNull()
        }
    }

    private fun createServerSocketOnChannel(
        channel: Int,
        secure: Boolean
    ): ServerSocketCandidate? {
        val adapter = bluetoothAdapter ?: return null
        val methodName = if (secure) {
            "listenUsingRfcommOn"
        } else {
            "listenUsingInsecureRfcommOn"
        }
        val label = "direct channel $channel ${if (secure) "secure" else "insecure"}"

        return runCatching {
            val parameterType = Int::class.javaPrimitiveType ?: Int::class.java
            val method = runCatching {
                adapter.javaClass.getMethod(methodName, parameterType)
            }.getOrElse {
                adapter.javaClass.getDeclaredMethod(methodName, parameterType).apply {
                    isAccessible = true
                }
            }
            method.invoke(adapter, channel) as? BluetoothServerSocket
        }.onSuccess { socket ->
            if (socket != null) {
                Log.d(TAG, "Server socket created ($label)")
            }
        }.onFailure { error ->
            Log.w(TAG, "$label socket failed: ${error.message}")
        }.getOrNull()?.let { ServerSocketCandidate(it, label) }
    }

    fun debugSnapshot(): Map<String, Any?> {
        val now = SystemClock.elapsedRealtime()
        return mapOf(
            "connectionState" to _connectionState.value,
            "hasBluetoothPermission" to hasBluetoothPermission(),
            "isBluetoothEnabled" to isBluetoothEnabled(),
            "acceptJobActive" to (acceptJob?.isActive == true),
            "readJobActive" to (readJob?.isActive == true),
            "healthProbeActive" to (healthProbeJob?.isActive == true),
            "listenerRestartActive" to (listenerRestartJob?.isActive == true),
            "hasServerSocket" to (serverSocket != null),
            "serverSocketLabel" to serverSocketLabel,
            "clientSocketConnected" to (clientSocket?.isConnected == true),
            "hasInputStream" to (inputStream != null),
            "hasOutputStream" to (outputStream != null),
            "lastListenAgeMs" to lastListenStartElapsedMs
                .takeIf { it > 0L }
                ?.let { now - it },
            "lastInboundAgeMs" to (now - lastInboundElapsedMs),
            "isDisconnecting" to isDisconnecting
        )
    }

    private fun recordRuntimeState(event: String) {
        PhoneAIServiceRuntimeState.record(
            context,
            event,
            debugSnapshot()
        )
    }

    private data class ServerSocketCandidate(
        val socket: BluetoothServerSocket,
        val label: String
    )
}
