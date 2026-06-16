package com.example.rokidglasses

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.rokidglasses.service.photo.CameraService
import com.example.rokidglasses.ui.theme.RokidGlassesTheme
import com.example.rokidglasses.viewmodel.GlassesViewModel

private const val TWO_FINGER_TRIGGER_POINTERS = 2
private val ROKID_TWO_FINGER_SHORTCUT_KEYCODES = setOf(260, 261, 262, 263)

class MainActivity : ComponentActivity() {
    private var glassesViewModel: GlassesViewModel? = null
    private var pendingDebugConnect: DebugConnectRequest? = null
    private var pendingDebugCaptureOnly = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            startServices()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
        }

        checkPermissions()
        handleWakeUpIntent(intent)

        setContent {
            RokidGlassesTheme {
                val viewModel: GlassesViewModel = viewModel(
                    factory = GlassesViewModel.Factory(this)
                )
                glassesViewModel = viewModel
                LaunchedEffect(viewModel) {
                    pendingDebugConnect?.let { request ->
                        pendingDebugConnect = null
                        Log.d(TAG, "Running pending debug connect")
                        viewModel.connectToPairedDevice(
                            address = request.address,
                            nameQuery = request.nameQuery,
                            maxRetries = request.maxRetries
                        )
                    }
                    if (pendingDebugCaptureOnly) {
                        pendingDebugCaptureOnly = false
                        Log.d(TAG, "Running pending debug capture-only request")
                        viewModel.captureDebugPhotoOnly()
                    }
                }
                GlassesMainScreen(viewModel = viewModel)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d(
            TAG,
            "onKeyDown: keyCode=$keyCode (${KeyEvent.keyCodeToString(keyCode)}), " +
                "scanCode=${event?.scanCode}, repeat=${event?.repeatCount}"
        )

        val viewModel = glassesViewModel ?: return super.onKeyDown(keyCode, event)
        val uiState = viewModel.uiState.value
        Log.d(
            TAG,
            "Key dispatch state: bluetooth=${uiState.bluetoothState}, " +
                "connected=${uiState.isConnected}, capturing=${uiState.isCapturingPhoto}, " +
                "processing=${uiState.isProcessing}"
        )

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                Log.d(TAG, "Single tap key ignored; capture requires two-finger shortcut")
                true
            }
            KeyEvent.KEYCODE_CAMERA,
            KeyEvent.KEYCODE_FOCUS -> {
                Log.d(TAG, "Camera/focus key accepted; requesting photo capture")
                viewModel.captureAndSendPhoto()
                true
            }
            in ROKID_TWO_FINGER_SHORTCUT_KEYCODES -> {
                Log.d(TAG, "Two-finger capture key accepted; requesting photo capture")
                viewModel.captureAndSendPhoto()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                true
            }
            else -> super.onKeyUp(keyCode, event)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWakeUpIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun handleWakeUpIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("wake_up", false) == true) {
            Log.d(TAG, "Wake-up intent received")
        }
        handleDebugIntent(intent)
    }

    private fun handleDebugIntent(intent: Intent?) {
        if (!isDebuggable()) return

        if (intent?.getBooleanExtra(EXTRA_DEBUG_CAPTURE_ONLY, false) == true) {
            val viewModel = glassesViewModel
            if (viewModel == null) {
                Log.w(TAG, "Debug capture-only requested before ViewModel is ready")
                pendingDebugCaptureOnly = true
            } else {
                Log.d(TAG, "Debug capture-only requested")
                viewModel.captureDebugPhotoOnly()
            }
        }

        val address = intent?.getStringExtra(EXTRA_DEBUG_CONNECT_ADDRESS)
        val nameQuery = intent?.getStringExtra(EXTRA_DEBUG_CONNECT_NAME)
        val maxRetries = intent?.getIntExtra(EXTRA_DEBUG_CONNECT_RETRIES, 5) ?: 5
        if (address.isNullOrBlank() && nameQuery.isNullOrBlank()) return

        val viewModel = glassesViewModel
        if (viewModel == null) {
            Log.w(TAG, "Debug connect requested before ViewModel is ready")
            pendingDebugConnect = DebugConnectRequest(address, nameQuery, maxRetries)
            return
        }

        Log.d(TAG, "Debug connect requested: address=$address, name=$nameQuery, retries=$maxRetries")
        viewModel.connectToPairedDevice(
            address = address,
            nameQuery = nameQuery,
            maxRetries = maxRetries
        )
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.addAll(
                listOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                )
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            startServices()
        } else {
            Log.w(TAG, "Missing permissions: ${notGranted.joinToString(", ")}")
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun startServices() {
        if (!CameraService.isRunning) {
            val serviceIntent = Intent(this, CameraService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    private fun isDebuggable(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private companion object {
        private const val TAG = "MainActivity"
        private const val EXTRA_DEBUG_CONNECT_ADDRESS = "debug_connect_address"
        private const val EXTRA_DEBUG_CONNECT_NAME = "debug_connect_name"
        private const val EXTRA_DEBUG_CONNECT_RETRIES = "debug_connect_retries"
        private const val EXTRA_DEBUG_CAPTURE_ONLY = "debug_capture_only"
    }

    private data class DebugConnectRequest(
        val address: String?,
        val nameQuery: String?,
        val maxRetries: Int
    )
}

@Composable
fun GlassesMainScreen(viewModel: GlassesViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var showDeviceSelector by remember { mutableStateOf(false) }
    val answerScrollState = rememberScrollState()

    LaunchedEffect(uiState.displayText) {
        answerScrollState.scrollTo(0)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(uiState.displayText) {
                if (uiState.displayText.isNotBlank()) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, dragAmount ->
                            answerScrollState.dispatchRawDelta(-dragAmount)
                        }
                    )
                }
            }
            .pointerInput(uiState.isConnected) {
                detectTwoFingerPress {
                    Log.d("GlassesMainScreen", "Two-finger press accepted")
                    if (uiState.isConnected) {
                        viewModel.captureAndSendPhoto()
                    } else {
                        viewModel.refreshPairedDevices()
                        showDeviceSelector = true
                    }
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            AnswerDisplayArea(
                displayText = uiState.displayText,
                scrollState = answerScrollState,
                modifier = Modifier
                    .weight(5f)
                    .fillMaxWidth()
            )

            BottomStatusBar(
                isConnected = uiState.isConnected,
                deviceName = uiState.connectedDeviceName,
                statusText = uiState.statusText,
                hintText = uiState.hintText,
                isProcessing = uiState.isProcessing,
                photoTransferProgress = uiState.photoTransferProgress,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }

        if (showDeviceSelector) {
            DeviceSelectorDialog(
                devices = uiState.availableDevices,
                cxrConnectedPhoneName = uiState.cxrConnectedPhoneName,
                onDeviceSelected = { device ->
                    viewModel.connectToDevice(device)
                    showDeviceSelector = false
                },
                onDismiss = { showDeviceSelector = false }
            )
        }
    }
}

private suspend fun PointerInputScope.detectTwoFingerPress(onPress: () -> Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= TWO_FINGER_TRIGGER_POINTERS) {
                event.changes.forEach { it.consume() }
                onPress()

                do {
                    val releaseEvent = awaitPointerEvent()
                    releaseEvent.changes.forEach { it.consume() }
                } while (releaseEvent.changes.any { it.pressed })
            }
        }
    }
}

private fun BluetoothDevice.safeDisplayName(context: android.content.Context, fallback: String): String {
    val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
    if (!hasPermission) return fallback
    return runCatching { name }.getOrNull().orEmpty().ifBlank { fallback }
}

@Composable
fun DeviceSelectorDialog(
    devices: List<android.bluetooth.BluetoothDevice>,
    cxrConnectedPhoneName: String? = null,
    onDeviceSelected: (android.bluetooth.BluetoothDevice) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sortedDevices = remember(devices, cxrConnectedPhoneName) {
        if (cxrConnectedPhoneName != null) {
            devices.sortedByDescending {
                @Suppress("MissingPermission")
                it.name?.equals(cxrConnectedPhoneName, ignoreCase = true) == true
            }
        } else {
            devices
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A1A),
        title = {
            Text(
                text = stringResource(R.string.select_phone),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            if (sortedDevices.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_paired_devices) + "\n" +
                        stringResource(R.string.pair_device_hint),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sortedDevices.forEach { device ->
                        val deviceName = device.safeDisplayName(
                            context,
                            stringResource(R.string.unknown_device)
                        )
                        val isRecommended = cxrConnectedPhoneName != null &&
                            deviceName.equals(cxrConnectedPhoneName, ignoreCase = true)

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDeviceSelected(device) },
                            color = if (isRecommended) Color(0xFF1E3A5F) else Color(0xFF2A2A2A),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = deviceName,
                                    color = Color.White,
                                    fontSize = 16.sp
                                )
                                if (isRecommended) {
                                    Text(
                                        text = stringResource(R.string.recommended),
                                        color = Color(0xFF64B5F6),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = Color(0xFF64B5F6))
            }
        }
    )
}

@Composable
fun StatusDot(
    color: Color,
    label: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, shape = androidx.compose.foundation.shape.CircleShape)
        )
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}

@Composable
fun AnswerDisplayArea(
    displayText: String,
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 10.dp),
        contentAlignment = Alignment.TopStart
    ) {
        AnimatedContent(
            targetState = displayText,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "display_text"
        ) { text ->
            if (text.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = text,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Start,
                        lineHeight = 18.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
fun BottomStatusBar(
    isConnected: Boolean,
    deviceName: String?,
    statusText: String,
    hintText: String,
    isProcessing: Boolean,
    photoTransferProgress: Float,
    modifier: Modifier = Modifier
) {
    val deviceLabel = deviceName?.let { compactText(it, maxChars = 12) }
    val statusLine = compactText(statusText, maxChars = 34)
    val hintLine = compactText(hintText, maxChars = 42)
    val progressLabel = if (isProcessing && photoTransferProgress > 0f && photoTransferProgress < 1f) {
        "${(photoTransferProgress * 100).toInt().coerceIn(1, 99)}%"
    } else {
        null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xEE050505))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusDot(
                color = if (isConnected) Color(0xFF64B5F6) else Color(0xFFFF7043),
                label = if (isConnected) "BT" else "BT-"
            )

            if (deviceLabel != null) {
                Text(
                    text = deviceLabel,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = statusLine,
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = Color(0xFF64B5F6),
                    strokeWidth = 2.dp
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = hintLine,
                color = Color.White.copy(alpha = 0.52f),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (progressLabel != null) {
                Text(
                    text = progressLabel,
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }
        }
    }
}

private fun compactText(text: String, maxChars: Int): String {
    val compact = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
    return if (compact.length <= maxChars) compact else compact.take(maxChars - 1) + "..."
}
