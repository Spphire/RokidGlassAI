package com.example.rokidphone.service

import android.app.Notification
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import com.example.rokidcommon.Constants
import com.example.rokidcommon.protocol.Message
import com.example.rokidcommon.protocol.MessageType
import com.example.rokidcommon.protocol.photo.PhotoTransferState
import com.example.rokidphone.R
import com.example.rokidphone.service.ai.AiRequestSettingsStore
import com.example.rokidphone.service.ai.CodexRelayVisionClient
import com.example.rokidphone.service.ai.KnowledgeBaseRepository
import com.example.rokidphone.service.ai.KnowledgeBaseStore
import com.example.rokidphone.service.ai.PromptStore
import com.example.rokidphone.service.photo.PhotoRepository
import com.example.rokidphone.service.photo.ReceivedPhoto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class PhoneAIService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val visionClient = CodexRelayVisionClient()
    private val photoRepository by lazy { PhotoRepository(this, serviceScope) }
    private var bluetoothManager: BluetoothSppManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var keepAliveOverlay: PhoneKeepAliveOverlay? = null

    override fun onCreate() {
        super.onCreate()
        PhoneAIServiceRuntimeState.record(this, "service onCreate")
        NotificationChannels.ensureServiceChannel(this)
        startBridgeForeground("Waiting for glasses")
        acquireBridgeWakeLock("service create")
        keepAliveOverlay = PhoneKeepAliveOverlay(this).also { it.showIfAllowed() }
        PhoneCompanionBridge.startObservingAssociatedDevices(this)
        ServiceBridge.updateServiceState(true)
        startBluetoothBridge()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reason = intent?.getStringExtra(EXTRA_START_REASON)
            ?: intent?.action
            ?: "start command"
        PhoneAIServiceRuntimeState.record(
            this,
            "service onStartCommand",
            bluetoothManager?.debugSnapshot().orEmpty() + mapOf(
                "startId" to startId,
                "flags" to flags,
                "reason" to reason,
                "autoRunEnabled" to PhoneAIServiceRunPolicy.isAutoRunEnabled(this)
            )
        )
        if (!PhoneAIServiceRunPolicy.isAutoRunEnabled(this)) {
            Log.d(TAG, "Service start ignored because auto-run is disabled")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (shouldRefreshBluetoothListenerForReason(reason)) {
            bluetoothManager?.restartListening("start command: $reason")
        } else {
            bluetoothManager?.ensureListening("start command: $reason")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        PhoneAIServiceRestartReceiver.scheduleRestartIfAutoRunEnabled(this)
    }

    override fun onDestroy() {
        PhoneAIServiceRuntimeState.record(
            this,
            "service onDestroy",
            bluetoothManager?.debugSnapshot().orEmpty()
        )
        ServiceBridge.updateServiceState(false)
        bluetoothManager?.disconnect(restartListening = false)
        keepAliveOverlay?.hide()
        keepAliveOverlay = null
        releaseBridgeWakeLock("service destroy")
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startBluetoothBridge() {
        val manager = BluetoothSppManager(this, serviceScope)
        bluetoothManager = manager

        PhoneAIServiceRuntimeState.record(this, "bluetooth bridge start", manager.debugSnapshot())
        manager.ensureListening("service start")
        startBluetoothWatchdog(manager)

        serviceScope.launch {
            manager.connectionState.collect { state ->
                PhoneAIServiceRuntimeState.record(
                    this@PhoneAIService,
                    "bluetooth state changed",
                    manager.debugSnapshot() + mapOf("state" to state)
                )
                ServiceBridge.updateBluetoothState(state)
                updateNotification(state.toNotificationText())
            }
        }

        serviceScope.launch {
            manager.connectedDeviceName.collect { name ->
                ServiceBridge.updateConnectedDeviceName(name)
            }
        }

        serviceScope.launch {
            manager.messageFlow.collect { message ->
                handleGlassesMessage(message)
            }
        }

        serviceScope.launchPhotoAnalysisQueue(manager.receivedPhoto) { photo ->
            handleReceivedPhoto(photo)
        }

        serviceScope.launch {
            manager.photoTransferState.collect { state ->
                photoTransferStatusText(state)?.let(ServiceBridge::updateProcessingStatus)
            }
        }

        serviceScope.launch {
            ServiceBridge.capturePhotoFlow.collect {
                manager.sendMessage(Message(type = MessageType.CAPTURE_PHOTO))
            }
        }

        serviceScope.launch {
            ServiceBridge.startListeningFlow.collect {
                manager.stopListening()
                manager.startListening()
            }
        }

        serviceScope.launch {
            ServiceBridge.disconnectFlow.collect {
                manager.disconnect(restartListening = true)
            }
        }
    }

    private fun startBluetoothWatchdog(manager: BluetoothSppManager) {
        serviceScope.launch {
            while (true) {
                delay(BLUETOOTH_WATCHDOG_INTERVAL_MS)
                try {
                    acquireBridgeWakeLock("watchdog")
                    if (!PhoneAIServiceRunPolicy.isAutoRunEnabled(this@PhoneAIService)) {
                        Log.d(TAG, "Auto-run disabled; stopping watchdog and service")
                        PhoneAIServiceRuntimeState.record(
                            this@PhoneAIService,
                            "watchdog stopping service",
                            manager.debugSnapshot()
                        )
                        stopSelf()
                        break
                    }
                    PhoneAIServiceRuntimeState.record(
                        this@PhoneAIService,
                        "watchdog ensureListening",
                        manager.debugSnapshot()
                    )
                    manager.ensureListening("service watchdog")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Bluetooth watchdog iteration failed; will retry", e)
                    PhoneAIServiceRuntimeState.record(
                        this@PhoneAIService,
                        "watchdog iteration failed",
                        mapOf("error" to (e.message ?: e::class.java.simpleName))
                    )
                }
            }
        }
    }

    private suspend fun handleGlassesMessage(message: Message) {
        PhoneAIServiceRuntimeState.record(
            this,
            "message from glasses",
            bluetoothManager?.debugSnapshot().orEmpty() + mapOf("messageType" to message.type)
        )
        when (message.type) {
            MessageType.HANDSHAKE -> bluetoothManager?.sendMessage(
                Message(type = MessageType.HANDSHAKE_ACK, payload = "rokid-phone-ready")
            )
            MessageType.HEARTBEAT -> bluetoothManager?.sendMessage(Message(type = MessageType.HEARTBEAT_ACK))
            else -> Log.d(TAG, "Ignoring message from glasses: ${message.type}")
        }
    }

    private suspend fun handleReceivedPhoto(photo: ReceivedPhoto) {
        Log.d(TAG, "Photo received: ${photo.data.size} bytes")
        val manager = bluetoothManager ?: return

        photoRepository.processReceivedPhoto(photo)?.let { savedPhoto ->
            Log.d(TAG, "Saved received glasses photo: ${savedPhoto.filePath}")
        }

        ServiceBridge.updateProcessingStatus("Photo received. Calling AI...")
        manager.sendMessage(Message.aiProcessing("Analyzing photo..."))

        val prompt = PromptStore.getPrompt(this)
        val aiSettings = AiRequestSettingsStore.getSettings(this)
        val knowledgeBaseId = KnowledgeBaseStore.getSelectedKnowledgeBaseId(this)
        val knowledgePrompt = KnowledgeBaseRepository.buildPrompt(this, prompt, knowledgeBaseId)
        knowledgePrompt.profile?.let { profile ->
            val status = "Using ${profile.name} knowledge base (${knowledgePrompt.contextChars} chars)"
            ServiceBridge.updateProcessingStatus(status)
            manager.sendMessage(Message.aiProcessing(status))
        }
        val aiTimeoutMs = effectiveAiAnalysisTimeoutMs(aiSettings.timeoutSeconds)
        var lastAiProgress = "Starting AI request"
        var progressSendJob: Job? = null
        val result = withTimeoutOrNull(aiTimeoutMs) {
            visionClient.analyze(photo.data, knowledgePrompt.prompt, aiSettings) { progress ->
                lastAiProgress = progress.toStatusText()
                ServiceBridge.updateProcessingStatus(lastAiProgress)
                val statusForGlasses = cleanAiStatusForGlasses(lastAiProgress)
                val previousProgressSend = progressSendJob
                progressSendJob = serviceScope.launch {
                    previousProgressSend?.join()
                    manager.sendMessage(Message.aiProcessing(statusForGlasses))
                }
            }
        } ?: Result.failure(
            IllegalStateException(
                "AI request timed out after ${aiTimeoutMs / 1_000L}s. Last stage: $lastAiProgress"
            )
        )

        withTimeoutOrNull(PROGRESS_SEND_DRAIN_TIMEOUT_MS) {
            progressSendJob?.join()
        }

        val displayText = result.fold(
            onSuccess = { cleanForGlasses(it) },
            onFailure = { error ->
                Log.e(TAG, "Photo analysis failed", error)
                "Photo analysis failed: ${error.message ?: "unknown error"}"
            }
        )

        ServiceBridge.updateProcessingStatus(displayText)
        manager.sendMessage(
            Message(
                type = MessageType.PHOTO_ANALYSIS_RESULT,
                payload = displayText
            )
        )
    }

    private fun cleanForGlasses(text: String): String {
        return ServiceBridge.cleanMarkdown(text)
            .replace(Regex("\\s{3,}"), " ")
            .trim()
            .take(600)
    }

    private fun cleanAiStatusForGlasses(text: String): String {
        return ServiceBridge.cleanMarkdown(text)
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .take(120)
    }

    private fun effectiveAiAnalysisTimeoutMs(timeoutSeconds: Int): Long {
        val configuredMs = timeoutSeconds.coerceAtLeast(1).toLong() * 1_000L
        return configuredMs.coerceAtLeast(MIN_AI_ANALYSIS_TIMEOUT_MS)
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Rokid Photo AI")
            .setContentText("$text. Keeping glasses bridge alive.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(android.app.NotificationManager::class.java)
        notificationManager.notify(Constants.NOTIFICATION_ID, createNotification(text))
    }

    private fun startBridgeForeground(text: String) {
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            Constants.NOTIFICATION_ID,
            createNotification(text),
            serviceType
        )
    }

    @Suppress("DEPRECATION")
    private fun acquireBridgeWakeLock(reason: String) {
        val existing = wakeLock
        if (existing != null && existing.isHeld) {
            existing.acquire(WAKE_LOCK_TIMEOUT_MS)
            return
        }

        val powerManager = getSystemService<PowerManager>() ?: return
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:GlassesBridge"
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        PhoneAIServiceRuntimeState.record(
            this,
            "wake lock acquired",
            mapOf("reason" to reason, "timeoutMs" to WAKE_LOCK_TIMEOUT_MS)
        )
    }

    private fun releaseBridgeWakeLock(reason: String) {
        val lock = wakeLock ?: return
        if (lock.isHeld) {
            runCatching { lock.release() }
                .onFailure { Log.w(TAG, "Unable to release wake lock", it) }
        }
        wakeLock = null
        PhoneAIServiceRuntimeState.record(this, "wake lock released", mapOf("reason" to reason))
    }

    private fun BluetoothConnectionState.toNotificationText(): String = when (this) {
        BluetoothConnectionState.CONNECTED -> "Glasses connected"
        BluetoothConnectionState.LISTENING -> "Waiting for glasses"
        BluetoothConnectionState.CONNECTING -> "Connecting"
        BluetoothConnectionState.DISCONNECTED -> "Disconnected"
    }

    companion object {
        private const val TAG = "PhoneAIService"
        private const val BLUETOOTH_WATCHDOG_INTERVAL_MS = 10_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 20 * 60 * 1_000L
        private const val MIN_AI_ANALYSIS_TIMEOUT_MS = 180_000L
        private const val PROGRESS_SEND_DRAIN_TIMEOUT_MS = 5_000L
        private const val EXTRA_START_REASON = "com.example.rokidphone.extra.START_REASON"
        private const val ACTION_ACL_CONNECTED = "android.bluetooth.device.action.ACL_CONNECTED"
        private const val ACTION_ACL_DISCONNECTED = "android.bluetooth.device.action.ACL_DISCONNECTED"

        fun start(context: Context) {
            PhoneAIServiceRunPolicy.setAutoRunEnabled(context, true)
            startInternal(context, "manual start")
        }

        fun startIfAutoRunEnabled(context: Context, reason: String) {
            if (!PhoneAIServiceRunPolicy.isAutoRunEnabled(context)) {
                Log.d(TAG, "Auto start skipped for $reason because auto-run is disabled")
                return
            }
            startInternal(context, reason)
        }

        fun stop(context: Context) {
            PhoneAIServiceRunPolicy.setAutoRunEnabled(context, false)
            context.stopService(Intent(context, PhoneAIService::class.java))
            ServiceBridge.updateServiceState(false)
            ServiceBridge.updateBluetoothState(BluetoothConnectionState.DISCONNECTED)
            ServiceBridge.updateConnectedDeviceName(null)
        }

        private fun startInternal(context: Context, reason: String) {
            val intent = Intent(context, PhoneAIService::class.java)
                .putExtra(EXTRA_START_REASON, reason)
            runCatching {
                NotificationChannels.ensureServiceChannel(context)
                Log.d(TAG, "Starting foreground service: $reason")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                Log.e(TAG, "Unable to start foreground service", it)
                PhoneAIServiceRestartReceiver.scheduleRestartIfAutoRunEnabled(
                    context,
                    delayMs = 10_000L
                )
            }
        }

        private fun shouldRefreshBluetoothListenerForReason(reason: String): Boolean {
            return reason == BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED ||
                reason == BluetoothAdapter.ACTION_STATE_CHANGED ||
                reason == ACTION_ACL_CONNECTED ||
                reason == ACTION_ACL_DISCONNECTED
        }
    }
}

internal fun CoroutineScope.launchPhotoAnalysisQueue(
    photos: Flow<ReceivedPhoto>,
    handlePhoto: suspend (ReceivedPhoto) -> Unit
) = launch {
    photos.collect { photo ->
        handlePhoto(photo)
    }
}

internal fun photoTransferStatusText(state: PhotoTransferState): String? = when (state) {
    is PhotoTransferState.InProgress -> "Receiving photo ${state.progressPercent.toInt()}%"
    is PhotoTransferState.Error -> "Photo transfer failed: ${state.message}"
    is PhotoTransferState.Success -> null
    PhotoTransferState.Idle -> null
}
