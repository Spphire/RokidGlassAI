package com.example.rokidglasses.viewmodel

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rokidcommon.protocol.ConnectionState
import com.example.rokidcommon.protocol.Message
import com.example.rokidcommon.protocol.MessageType
import com.example.rokidglasses.R
import com.example.rokidglasses.sdk.CameraMode
import com.example.rokidglasses.sdk.CxrServiceManager
import com.example.rokidglasses.sdk.UnifiedCameraManager
import com.example.rokidglasses.service.BluetoothClientState
import com.example.rokidglasses.service.BluetoothSppClient
import com.example.rokidglasses.service.photo.ImageCompressor
import com.example.rokidglasses.service.photo.PhotoTransferProtocol
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class GlassesUiState(
    val isConnected: Boolean = false,
    val isProcessing: Boolean = false,
    val displayText: String = "",
    val statusText: String = "",
    val hintText: String = "",
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val currentPage: Int = 0,
    val totalPages: Int = 1,
    val isPaginated: Boolean = false,
    val bluetoothState: BluetoothClientState = BluetoothClientState.DISCONNECTED,
    val connectedDeviceName: String? = null,
    val availableDevices: List<BluetoothDevice> = emptyList(),
    val cxrConnectedPhoneName: String? = null,
    val isCapturingPhoto: Boolean = false,
    val photoTransferProgress: Float = 0f
)

class GlassesViewModel(
    private val context: Context
) : ViewModel() {
    private val bluetoothClient = BluetoothSppClient(context, viewModelScope)
    private var cameraManager: UnifiedCameraManager? = null
    private var cxrServiceManager: CxrServiceManager? = null
    private var photoTransferProtocol: PhotoTransferProtocol? = null
    private var autoConnectStarted = false

    private var responsePages: List<String> = emptyList()

    private val _uiState = MutableStateFlow(
        GlassesUiState(
            statusText = context.getString(R.string.connecting_status),
            hintText = context.getString(R.string.tap_touchpad_photo)
        )
    )
    val uiState: StateFlow<GlassesUiState> = _uiState.asStateFlow()

    init {
        initializeBluetooth()
        initializeCamera()
        initializeCxrService()
    }

    private fun initializeBluetooth() {
        viewModelScope.launch {
            bluetoothClient.connectionState.collect { state ->
                val connectionState = when (state) {
                    BluetoothClientState.DISCONNECTED -> ConnectionState.DISCONNECTED
                    BluetoothClientState.CONNECTING -> ConnectionState.CONNECTING
                    BluetoothClientState.CONNECTED -> ConnectionState.CONNECTED
                }

                _uiState.update {
                    it.copy(
                        bluetoothState = state,
                        connectionState = connectionState,
                        isConnected = state == BluetoothClientState.CONNECTED,
                        isProcessing = it.isProcessing && state == BluetoothClientState.CONNECTED,
                        statusText = when (state) {
                            BluetoothClientState.DISCONNECTED -> context.getString(R.string.not_connected)
                            BluetoothClientState.CONNECTING -> context.getString(R.string.connecting_status)
                            BluetoothClientState.CONNECTED -> context.getString(R.string.connected_ready)
                        },
                        hintText = when (state) {
                            BluetoothClientState.DISCONNECTED -> context.getString(R.string.please_connect_phone)
                            BluetoothClientState.CONNECTING -> context.getString(R.string.please_wait)
                            BluetoothClientState.CONNECTED -> context.getString(R.string.tap_touchpad_photo)
                        }
                    )
                }
            }
        }

        viewModelScope.launch {
            bluetoothClient.connectedDeviceName.collect { name ->
                _uiState.update { it.copy(connectedDeviceName = name) }
            }
        }

        viewModelScope.launch {
            bluetoothClient.messageFlow.collect { message ->
                handlePhoneMessage(message)
            }
        }

        refreshPairedDevices()
        startBluetoothAutoConnect()
    }

    private fun startBluetoothAutoConnect() {
        if (autoConnectStarted) return
        autoConnectStarted = true

        viewModelScope.launch {
            delay(AUTO_CONNECT_INITIAL_DELAY_MS)
            while (true) {
                when (bluetoothClient.connectionState.value) {
                    BluetoothClientState.DISCONNECTED -> {
                        refreshPairedDevices()
                        val started = bluetoothClient.connectToPairedDevice(
                            maxRetries = AUTO_CONNECT_RETRIES_PER_DEVICE
                        )
                        delay(
                            if (started) {
                                AUTO_CONNECT_AFTER_START_DELAY_MS
                            } else {
                                AUTO_CONNECT_IDLE_RETRY_MS
                            }
                        )
                    }
                    BluetoothClientState.CONNECTING -> {
                        delay(AUTO_CONNECT_CONNECTING_POLL_MS)
                    }
                    BluetoothClientState.CONNECTED -> {
                        delay(AUTO_CONNECT_CONNECTED_POLL_MS)
                    }
                }
            }
        }
    }

    private fun initializeCamera() {
        viewModelScope.launch {
            cameraManager = UnifiedCameraManager(
                context = context,
                preferredMode = CameraMode.CAMERA2
            )

            val result = cameraManager?.initialize()
            if (result?.isSuccess == true) {
                Log.d(TAG, "Camera manager initialized: ${cameraManager?.getCameraTypeName()}")
            } else {
                Log.w(TAG, "Camera manager initialization failed: ${result?.exceptionOrNull()?.message}")
            }
        }
    }

    private fun initializeCxrService() {
        if (!CxrServiceManager.isSdkAvailable()) {
            Log.w(TAG, "CXR-S SDK not available")
            return
        }

        cxrServiceManager = CxrServiceManager.getInstance()
        val initialized = cxrServiceManager?.initialize() == true
        Log.d(TAG, "CXR-S Service initialized: $initialized")
        if (!initialized) return

        viewModelScope.launch {
            cxrServiceManager?.connectionState?.collect { state ->
                when (state) {
                    is CxrServiceManager.ConnectionState.Connected -> {
                        _uiState.update { it.copy(cxrConnectedPhoneName = state.deviceName) }
                    }
                    is CxrServiceManager.ConnectionState.Disconnected -> {
                        _uiState.update { it.copy(cxrConnectedPhoneName = null) }
                    }
                }
            }
        }
    }

    fun refreshPairedDevices() {
        val devices = bluetoothClient.getPairedDevices()
        _uiState.update { it.copy(availableDevices = devices) }
        Log.d(TAG, "Found ${devices.size} paired devices")
    }

    fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to device")
        bluetoothClient.connect(device)
    }

    fun connectToPairedDevice(
        address: String? = null,
        nameQuery: String? = null,
        maxRetries: Int = 5
    ): Boolean {
        Log.d(TAG, "Connecting to paired device: address=$address, nameQuery=$nameQuery")
        val started = bluetoothClient.connectToPairedDevice(
            address = address,
            nameQuery = nameQuery,
            maxRetries = maxRetries
        )
        if (!started) {
            _uiState.update {
                it.copy(
                    statusText = context.getString(R.string.bluetooth_not_connected),
                    hintText = context.getString(R.string.connect_phone_first)
                )
            }
        }
        return started
    }

    fun disconnectBluetooth() {
        bluetoothClient.disconnect()
    }

    private fun handlePhoneMessage(message: Message) {
        Log.d(TAG, "Received from phone: ${message.type}, payload: ${message.payload}")

        when (message.type) {
            MessageType.AI_PROCESSING -> showProcessing(message.payload)
            MessageType.AI_ERROR -> showError(message.payload)
            MessageType.DISPLAY_TEXT -> showDisplayText(message.payload.orEmpty())
            MessageType.DISPLAY_CLEAR -> resetDisplay()
            MessageType.HEARTBEAT -> acknowledgeHeartbeat()
            MessageType.CAPTURE_PHOTO -> captureAndSendPhoto()
            MessageType.PHOTO_ANALYSIS_RESULT -> showAnalysisResult(
                message.payload ?: context.getString(R.string.photo_analysis_no_result)
            )
            MessageType.HEARTBEAT_ACK -> Unit
            else -> Log.d(TAG, "Unhandled message type: ${message.type}")
        }
    }

    private fun showProcessing(text: String?) {
        val status = text ?: context.getString(R.string.processing)
        _uiState.update { currentState ->
            if (!currentState.isProcessing && currentState.displayText.isNotBlank()) {
                Log.d(TAG, "Ignoring stale AI processing status after final result: $status")
                currentState
            } else {
                responsePages = emptyList()
                currentState.copy(
                isProcessing = true,
                displayText = "",
                    statusText = status,
                hintText = context.getString(R.string.please_wait_short),
                currentPage = 0,
                totalPages = 1,
                isPaginated = false
            )
            }
        }
    }

    private fun showError(text: String?) {
        _uiState.update {
            it.copy(
                isCapturingPhoto = false,
                isProcessing = false,
                statusText = context.getString(R.string.error_prefix, text.orEmpty()),
                hintText = context.getString(R.string.please_retry)
            )
        }
    }

    private fun showDisplayText(text: String) {
        _uiState.update {
            it.copy(
                statusText = text,
                isProcessing = false,
                isCapturingPhoto = false
            )
        }
    }

    private fun resetDisplay() {
        responsePages = emptyList()
        _uiState.update {
            it.copy(
                displayText = "",
                statusText = context.getString(R.string.connected_ready),
                hintText = context.getString(R.string.tap_touchpad_photo),
                currentPage = 0,
                totalPages = 1,
                isPaginated = false,
                isProcessing = false,
                isCapturingPhoto = false,
                photoTransferProgress = 0f
            )
        }
    }

    private fun acknowledgeHeartbeat() {
        viewModelScope.launch {
            bluetoothClient.sendMessage(Message(type = MessageType.HEARTBEAT_ACK))
        }
    }

    private fun showAnalysisResult(text: String) {
        responsePages = emptyList()
        val shouldShowScrollHint = text.length > SCROLL_HINT_TEXT_LENGTH

        _uiState.update {
            it.copy(
                isCapturingPhoto = false,
                photoTransferProgress = 0f,
                isProcessing = false,
                displayText = text,
                statusText = context.getString(R.string.ai_done_status),
                hintText = if (shouldShowScrollHint) {
                    context.getString(R.string.swipe_for_more)
                } else {
                    context.getString(R.string.tap_touchpad_photo)
                },
                currentPage = 0,
                totalPages = 1,
                isPaginated = false
            )
        }
    }

    private fun paginateText(text: String): List<String> {
        if (text.isBlank()) return listOf("")
        if (text.length <= MAX_CHARS_PER_PAGE) return listOf(text)

        val breakChars = listOf('\n', ' ', '。', '，', '；', '、', ';', '.', ',')
        val pages = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val hardEnd = minOf(index + MAX_CHARS_PER_PAGE, text.length)
            var breakPoint = hardEnd
            if (hardEnd < text.length) {
                val naturalBreak = breakChars
                    .map { text.lastIndexOf(it, hardEnd) }
                    .filter { it > index }
                    .maxOrNull()

                if (naturalBreak != null) {
                    breakPoint = naturalBreak + 1
                }
            }

            pages.add(text.substring(index, breakPoint).trim())
            index = breakPoint
        }
        return pages.filter { it.isNotBlank() }.ifEmpty { listOf(text.take(MAX_CHARS_PER_PAGE)) }
    }

    fun nextPage() {
        val currentState = _uiState.value
        if (!currentState.isPaginated || currentState.currentPage >= currentState.totalPages - 1) return

        val newPage = currentState.currentPage + 1
        _uiState.update {
            it.copy(
                currentPage = newPage,
                displayText = responsePages.getOrElse(newPage) { "" },
                hintText = if (newPage == currentState.totalPages - 1) {
                    context.getString(R.string.tap_touchpad_photo)
                } else {
                    context.getString(R.string.swipe_for_more)
                }
            )
        }
    }

    fun previousPage() {
        val currentState = _uiState.value
        if (!currentState.isPaginated || currentState.currentPage <= 0) return

        val newPage = currentState.currentPage - 1
        _uiState.update {
            it.copy(
                currentPage = newPage,
                displayText = responsePages.getOrElse(newPage) { "" },
                hintText = context.getString(R.string.swipe_for_more)
            )
        }
    }

    fun dismissPagination() {
        resetDisplay()
    }

    fun captureDebugPhotoOnly() {
        responsePages = emptyList()
        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        isCapturingPhoto = true,
                        isProcessing = true,
                        displayText = "",
                        statusText = "Debug capture",
                        hintText = context.getString(R.string.please_wait_short),
                        currentPage = 0,
                        totalPages = 1,
                        isPaginated = false,
                        photoTransferProgress = 0f
                    )
                }

                val rawImageData = cameraManager?.capturePhoto()
                    ?: error("Camera returned no image")
                val compressedData = withContext(Dispatchers.Default) {
                    ImageCompressor.compressForTransfer(rawImageData)
                }
                val savedCapture = saveLatestCaptureForDebug(rawImageData, compressedData)

                _uiState.update {
                    it.copy(
                        isCapturingPhoto = false,
                        isProcessing = false,
                        statusText = "Saved ${savedCapture.rawDimensions.first}x${savedCapture.rawDimensions.second} -> " +
                            "${savedCapture.transferDimensions.first}x${savedCapture.transferDimensions.second}",
                        hintText = context.getString(R.string.tap_touchpad_photo),
                        photoTransferProgress = 0f
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Debug capture-only failed", e)
                _uiState.update {
                    it.copy(
                        isCapturingPhoto = false,
                        isProcessing = false,
                        statusText = "Debug capture failed: ${e.message.orEmpty()}",
                        hintText = context.getString(R.string.please_retry),
                        photoTransferProgress = 0f
                    )
                }
            }
        }
    }

    private suspend fun saveLatestCaptureForDebug(
        rawImageData: ByteArray,
        compressedData: ByteArray
    ): SavedCaptureDebugInfo {
        val rawDimensions = ImageCompressor.getImageDimensions(rawImageData)
        val transferDimensions = ImageCompressor.getImageDimensions(compressedData)
        val debugDir = File(context.filesDir, "debug_capture")
        withContext(Dispatchers.IO) {
            debugDir.mkdirs()
            File(debugDir, "latest_raw.jpg").writeBytes(rawImageData)
            File(debugDir, "latest_transfer.jpg").writeBytes(compressedData)
        }
        return SavedCaptureDebugInfo(rawDimensions, transferDimensions)
    }

    fun captureAndSendPhoto() {
        val state = _uiState.value
        Log.d(
            TAG,
            "captureAndSendPhoto requested: bluetooth=${state.bluetoothState}, " +
                "capturing=${state.isCapturingPhoto}, processing=${state.isProcessing}"
        )

        if (_uiState.value.isCapturingPhoto) {
            Log.w(TAG, "Photo capture already in progress")
            return
        }

        if (_uiState.value.bluetoothState != BluetoothClientState.CONNECTED) {
            Log.w(TAG, "Photo capture blocked: bluetooth not connected")
            _uiState.update {
                it.copy(
                    statusText = context.getString(R.string.bluetooth_not_connected),
                    hintText = context.getString(R.string.connect_phone_first)
                )
            }
            return
        }

        responsePages = emptyList()
        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        isCapturingPhoto = true,
                        isProcessing = true,
                        displayText = "",
                        statusText = context.getString(R.string.capturing_photo),
                        hintText = context.getString(R.string.please_wait_short),
                        currentPage = 0,
                        totalPages = 1,
                        isPaginated = false,
                        photoTransferProgress = 0f
                    )
                }

                val rawImageData = cameraManager?.capturePhoto()
                if (rawImageData == null) {
                    Log.e(TAG, "Failed to capture photo. Camera state: ${cameraManager?.cameraState?.value}")
                    _uiState.update {
                        it.copy(
                            isCapturingPhoto = false,
                            isProcessing = false,
                            statusText = context.getString(R.string.capture_failed),
                            hintText = context.getString(R.string.capture_failed_hint)
                        )
                    }
                    return@launch
                }

                _uiState.update { it.copy(statusText = context.getString(R.string.compressing_photo)) }
                val compressedData = withContext(Dispatchers.Default) {
                    ImageCompressor.compressForTransfer(rawImageData)
                }
                Log.d(TAG, "Photo compressed: ${rawImageData.size} -> ${compressedData.size} bytes")
                runCatching {
                    saveLatestCaptureForDebug(rawImageData, compressedData)
                }.onFailure { error ->
                    Log.w(TAG, "Failed to save latest capture debug files", error)
                }

                _uiState.update {
                    it.copy(
                        statusText = context.getString(R.string.transferring_photo),
                        photoTransferProgress = 0f
                    )
                }

                photoTransferProtocol = bluetoothClient.createPhotoTransferProtocol { current, total ->
                    val progress = if (total > 0) current.toFloat() / total else 0f
                    _uiState.update { it.copy(photoTransferProgress = progress) }
                } ?: error("Bluetooth socket not connected")

                val transferResult = photoTransferProtocol?.sendPhoto(compressedData)
                    ?: Result.failure(IllegalStateException("Photo transfer protocol not available"))

                transferResult.fold(
                    onSuccess = { stats ->
                        Log.d(TAG, "Photo transfer complete: $stats")
                        _uiState.update {
                            it.copy(
                                isCapturingPhoto = false,
                                statusText = context.getString(R.string.photo_sent_waiting_ai),
                                hintText = context.getString(R.string.please_wait_short)
                            )
                        }
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Photo transfer failed", error)
                        _uiState.update {
                            it.copy(
                                isCapturingPhoto = false,
                                isProcessing = false,
                                statusText = context.getString(R.string.transfer_failed, error.message.orEmpty()),
                                hintText = context.getString(R.string.please_retry)
                            )
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Photo capture error", e)
                _uiState.update {
                    it.copy(
                        isCapturingPhoto = false,
                        isProcessing = false,
                        statusText = context.getString(R.string.error_message, e.message.orEmpty()),
                        hintText = context.getString(R.string.please_retry)
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        bluetoothClient.disconnect()
        cameraManager?.release()
        cxrServiceManager?.release()
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(GlassesViewModel::class.java)) {
                return GlassesViewModel(context.applicationContext) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    private companion object {
        private const val TAG = "GlassesViewModel"
        private const val MAX_CHARS_PER_PAGE = 320
        private const val SCROLL_HINT_TEXT_LENGTH = 180
        private const val AUTO_CONNECT_INITIAL_DELAY_MS = 1_000L
        private const val AUTO_CONNECT_RETRIES_PER_DEVICE = 6
        private const val AUTO_CONNECT_AFTER_START_DELAY_MS = 20_000L
        private const val AUTO_CONNECT_IDLE_RETRY_MS = 10_000L
        private const val AUTO_CONNECT_CONNECTING_POLL_MS = 3_000L
        private const val AUTO_CONNECT_CONNECTED_POLL_MS = 15_000L
    }
}

private data class SavedCaptureDebugInfo(
    val rawDimensions: Pair<Int, Int>,
    val transferDimensions: Pair<Int, Int>
)
