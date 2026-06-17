package com.example.rokidphone.service

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "ServiceBridge"

object ServiceBridge {
    private val _serviceStateFlow = MutableStateFlow(false)
    val serviceStateFlow: StateFlow<Boolean> = _serviceStateFlow.asStateFlow()

    private val _bluetoothStateFlow = MutableStateFlow(BluetoothConnectionState.DISCONNECTED)
    val bluetoothStateFlow: StateFlow<BluetoothConnectionState> = _bluetoothStateFlow.asStateFlow()

    private val _connectedDeviceNameFlow = MutableStateFlow<String?>(null)
    val connectedDeviceNameFlow: StateFlow<String?> = _connectedDeviceNameFlow.asStateFlow()

    private val _processingStatusFlow = MutableStateFlow("Waiting for glasses photo")
    val processingStatusFlow: StateFlow<String> = _processingStatusFlow.asStateFlow()

    private val _companionStatusFlow = MutableStateFlow("Companion: not checked")
    val companionStatusFlow: StateFlow<String> = _companionStatusFlow.asStateFlow()

    private val _capturePhotoFlow = MutableSharedFlow<Unit>(replay = 0)
    val capturePhotoFlow: SharedFlow<Unit> = _capturePhotoFlow.asSharedFlow()

    private val _startListeningFlow = MutableSharedFlow<Unit>(replay = 0)
    val startListeningFlow: SharedFlow<Unit> = _startListeningFlow.asSharedFlow()

    private val _disconnectFlow = MutableSharedFlow<Unit>(replay = 0)
    val disconnectFlow: SharedFlow<Unit> = _disconnectFlow.asSharedFlow()

    suspend fun requestCapturePhoto() {
        _capturePhotoFlow.emit(Unit)
    }

    fun updateServiceState(isRunning: Boolean) {
        _serviceStateFlow.value = isRunning
    }

    fun updateBluetoothState(state: BluetoothConnectionState) {
        Log.d(TAG, "Bluetooth state: $state")
        _bluetoothStateFlow.value = state
    }

    fun updateConnectedDeviceName(name: String?) {
        _connectedDeviceNameFlow.value = name
    }

    fun updateProcessingStatus(status: String) {
        _processingStatusFlow.value = status
    }

    fun updateCompanionStatus(status: String) {
        _companionStatusFlow.value = status
    }

    suspend fun requestStartListening() {
        _startListeningFlow.emit(Unit)
    }

    suspend fun requestDisconnect() {
        _disconnectFlow.emit(Unit)
    }

    fun cleanMarkdown(text: String): String {
        return text
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("\\*(.+?)\\*"), "$1")
            .replace(Regex("__(.+?)__"), "$1")
            .replace(Regex("_(.+?)_"), "$1")
            .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("```[\\s\\S]*?```"), "")
            .replace(Regex("`(.+?)`"), "$1")
            .replace(Regex("\\[(.+?)]\\([^)]+\\)"), "$1")
            .replace(Regex("^[\\-*+]\\s+", RegexOption.MULTILINE), "- ")
            .replace(Regex("^\\d+\\.\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}
