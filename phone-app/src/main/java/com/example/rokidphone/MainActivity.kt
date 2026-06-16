package com.example.rokidphone

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.rokidphone.service.BluetoothConnectionState
import com.example.rokidphone.service.PhoneAIService
import com.example.rokidphone.service.ServiceBridge
import com.example.rokidphone.service.ai.AiRequestSettings
import com.example.rokidphone.service.ai.AiRequestSettingsStore
import com.example.rokidphone.service.ai.CodexRelayConfig
import com.example.rokidphone.service.ai.CodexRelayVisionClient
import com.example.rokidphone.service.ai.KnowledgeBaseProfile
import com.example.rokidphone.service.ai.KnowledgeBaseRepository
import com.example.rokidphone.service.ai.KnowledgeBaseStore
import com.example.rokidphone.service.ai.PromptStore
import com.example.rokidphone.service.photo.GlassesPhotoSimulator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val requiredGranted = requiredBridgePermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED ||
                permissions[permission] == true
        }
        if (requiredGranted) {
            startBridgeService()
        } else {
            Log.w(TAG, "Bridge service not started; required permissions missing: ${permissions.filterValues { !it }}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPermissionsAndStart()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PhotoAiWorkbench(
                        onStart = { checkPermissionsAndStart() },
                        onStop = { stopBridgeService() }
                    )
                }
            }
        }
    }

    private fun checkPermissionsAndStart() {
        val missingRequired = requiredBridgePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        val missingOptional = optionalBridgePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingRequired.isEmpty()) {
            startBridgeService()
            if (missingOptional.isNotEmpty()) {
                permissionLauncher.launch(missingOptional.toTypedArray())
            }
        } else {
            permissionLauncher.launch((missingRequired + missingOptional).toTypedArray())
        }
    }

    private fun requiredBridgePermissions(): List<String> {
        return buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        }
    }

    private fun optionalBridgePermissions(): List<String> {
        return buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun startBridgeService() {
        PhoneAIService.start(this)
    }

    private fun stopBridgeService() {
        PhoneAIService.stop(this)
    }
}

@Composable
private fun PhotoAiWorkbench(
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val serviceRunning by ServiceBridge.serviceStateFlow.collectAsState()
    val bluetoothState by ServiceBridge.bluetoothStateFlow.collectAsState()
    val deviceName by ServiceBridge.connectedDeviceNameFlow.collectAsState()
    val processingStatus by ServiceBridge.processingStatusFlow.collectAsState()
    val phoneTestState by PhonePhotoTestRunner.state.collectAsState()
    var prompt by rememberSaveable { mutableStateOf("") }
    var reasoningEffort by rememberSaveable { mutableStateOf(AiRequestSettingsStore.DEFAULT_REASONING_EFFORT) }
    var textVerbosity by rememberSaveable { mutableStateOf(AiRequestSettingsStore.DEFAULT_TEXT_VERBOSITY) }
    var maxOutputTokens by rememberSaveable { mutableStateOf(AiRequestSettingsStore.DEFAULT_MAX_OUTPUT_TOKENS.toString()) }
    var maxImageSidePx by rememberSaveable { mutableStateOf(AiRequestSettingsStore.DEFAULT_MAX_IMAGE_SIDE_PX.toString()) }
    var jpegQuality by rememberSaveable { mutableStateOf(AiRequestSettingsStore.DEFAULT_JPEG_QUALITY.toString()) }
    var timeoutSeconds by rememberSaveable { mutableStateOf(AiRequestSettingsStore.DEFAULT_TIMEOUT_SECONDS.toString()) }
    var selectedKnowledgeBaseId by rememberSaveable {
        mutableStateOf(KnowledgeBaseStore.AUTO_ID)
    }
    var knowledgeBaseProfiles by remember { mutableStateOf<List<KnowledgeBaseProfile>>(emptyList()) }
    var knowledgeBaseStatus by remember { mutableStateOf("Loading knowledge bases...") }
    var pendingPhotoUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var batteryOptimized by remember { mutableStateOf(context.isBatteryOptimizationActive()) }
    val vendorBackgroundSettingsIntent = remember(context) {
        context.vendorBackgroundSettingsIntent()
    }
    val backgroundSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        batteryOptimized = context.isBatteryOptimizationActive()
    }

    LaunchedEffect(Unit) {
        if (prompt.isBlank()) {
            prompt = PromptStore.getPrompt(context)
        }
        val settings = AiRequestSettingsStore.getSettings(context)
        reasoningEffort = settings.reasoningEffort
        textVerbosity = settings.textVerbosity
        maxOutputTokens = settings.maxOutputTokens.toString()
        maxImageSidePx = settings.maxImageSidePx.toString()
        jpegQuality = settings.jpegQuality.toString()
        timeoutSeconds = settings.timeoutSeconds.toString()
        selectedKnowledgeBaseId = KnowledgeBaseStore.getSelectedKnowledgeBaseId(context)
        knowledgeBaseProfiles = KnowledgeBaseRepository.loadProfiles(context)
        knowledgeBaseStatus = knowledgeBaseProfiles.knowledgeBaseStatusText(selectedKnowledgeBaseId)
    }

    fun currentAiSettings(): AiRequestSettings {
        return AiRequestSettings(
            reasoningEffort = reasoningEffort,
            textVerbosity = textVerbosity,
            maxOutputTokens = maxOutputTokens.toIntOrNull()
                ?: AiRequestSettingsStore.DEFAULT_MAX_OUTPUT_TOKENS,
            maxImageSidePx = maxImageSidePx.toIntOrNull()
                ?: AiRequestSettingsStore.DEFAULT_MAX_IMAGE_SIDE_PX,
            jpegQuality = jpegQuality.toIntOrNull()
                ?: AiRequestSettingsStore.DEFAULT_JPEG_QUALITY,
            timeoutSeconds = timeoutSeconds.toIntOrNull()
                ?: AiRequestSettingsStore.DEFAULT_TIMEOUT_SECONDS
        ).normalized()
    }

    fun saveAiSettings(settings: AiRequestSettings = currentAiSettings()) {
        val normalized = settings.normalized()
        reasoningEffort = normalized.reasoningEffort
        textVerbosity = normalized.textVerbosity
        maxOutputTokens = normalized.maxOutputTokens.toString()
        maxImageSidePx = normalized.maxImageSidePx.toString()
        jpegQuality = normalized.jpegQuality.toString()
        timeoutSeconds = normalized.timeoutSeconds.toString()
        AiRequestSettingsStore.saveSettings(context, normalized)
    }

    fun selectKnowledgeBase(knowledgeBaseId: String) {
        selectedKnowledgeBaseId = knowledgeBaseId
        KnowledgeBaseStore.saveSelectedKnowledgeBaseId(context, knowledgeBaseId)
        knowledgeBaseStatus = knowledgeBaseProfiles.knowledgeBaseStatusText(knowledgeBaseId)
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val photoUri = pendingPhotoUriString?.let(Uri::parse)
        Log.d(TAG, "Phone camera result: success=$success, uri=$photoUri")
        if (!success || photoUri == null) {
            PhonePhotoTestRunner.setMessage("Camera returned no image.")
            return@rememberLauncherForActivityResult
        }

        PromptStore.savePrompt(context, prompt)
        saveAiSettings()
        KnowledgeBaseStore.saveSelectedKnowledgeBaseId(context, selectedKnowledgeBaseId)
        PhonePhotoTestRunner.start(
            context = context,
            photoUri = photoUri,
            prompt = prompt,
            aiSettings = currentAiSettings(),
            knowledgeBaseId = selectedKnowledgeBaseId
        )
        pendingPhotoUriString = null
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val photoUri = context.createTempPhotoUri()
            pendingPhotoUriString = photoUri.toString()
            PhonePhotoTestRunner.setMessage("Opening camera...")
            Log.d(TAG, "Phone camera launch after permission: uri=$photoUri")
            cameraLauncher.launch(photoUri)
        } else {
            PhonePhotoTestRunner.setMessage("Camera permission denied.")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Rokid Photo AI", style = MaterialTheme.typography.headlineMedium)

        Text("AI Relay", style = MaterialTheme.typography.titleMedium)
        Text("Providers: ${CodexRelayConfig.providers.size}")
        Text("Primary URL: ${CodexRelayConfig.baseUrl}")
        Text("Primary model: ${CodexRelayConfig.model}")

        Text("Knowledge Base", style = MaterialTheme.typography.titleMedium)
        KnowledgeBaseSelector(
            profiles = knowledgeBaseProfiles,
            selectedId = selectedKnowledgeBaseId,
            onSelected = ::selectKnowledgeBase
        )
        Text(knowledgeBaseStatus, style = MaterialTheme.typography.bodyMedium)

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = prompt,
            onValueChange = {
                prompt = it
                PromptStore.savePrompt(context, it)
            },
            minLines = 3,
            label = { Text("Preset prompt") },
            placeholder = { Text("Example: 帮我回答图中的题目") }
        )

        Text("AI Parameters", style = MaterialTheme.typography.titleMedium)
        Text("reasoning=$reasoningEffort, verbosity=$textVerbosity, tokens=$maxOutputTokens, timeout=${timeoutSeconds}s")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { saveAiSettings(AiRequestSettingsStore.FAST_PRESET) }) {
                Text("Fast")
            }
            TextButton(onClick = { saveAiSettings(AiRequestSettingsStore.BALANCED_PRESET) }) {
                Text("Balanced")
            }
            TextButton(onClick = { saveAiSettings(AiRequestSettingsStore.QUALITY_PRESET) }) {
                Text("Quality")
            }
        }
        OptionButtons(
            label = "Reasoning",
            options = AiRequestSettingsStore.REASONING_EFFORTS,
            selected = reasoningEffort,
            onSelected = {
                reasoningEffort = it
                saveAiSettings()
            }
        )
        OptionButtons(
            label = "Verbosity",
            options = AiRequestSettingsStore.TEXT_VERBOSITIES,
            selected = textVerbosity,
            onSelected = {
                textVerbosity = it
                saveAiSettings()
            }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                modifier = Modifier.weight(1f),
                value = maxOutputTokens,
                onValueChange = { maxOutputTokens = it },
                label = "Tokens"
            )
            NumberField(
                modifier = Modifier.weight(1f),
                value = maxImageSidePx,
                onValueChange = { maxImageSidePx = it },
                label = "Image"
            )
            NumberField(
                modifier = Modifier.weight(1f),
                value = jpegQuality,
                onValueChange = { jpegQuality = it },
                label = "JPEG"
            )
            NumberField(
                modifier = Modifier.weight(1f),
                value = timeoutSeconds,
                onValueChange = { timeoutSeconds = it },
                label = "Timeout"
            )
        }
        TextButton(onClick = { saveAiSettings() }) {
            Text("Save AI Parameters")
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !phoneTestState.isTesting,
            onClick = {
                PromptStore.savePrompt(context, prompt)
                saveAiSettings()
                KnowledgeBaseStore.saveSelectedKnowledgeBaseId(context, selectedKnowledgeBaseId)
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    val photoUri = context.createTempPhotoUri()
                    pendingPhotoUriString = photoUri.toString()
                    PhonePhotoTestRunner.setMessage("Opening camera...")
                    Log.d(TAG, "Phone camera launch: uri=$photoUri")
                    cameraLauncher.launch(photoUri)
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        ) {
            Text(if (phoneTestState.isTesting) "Testing..." else "Take Phone Photo and Test AI")
        }

        Text(phoneTestState.message, style = MaterialTheme.typography.bodyLarge)

        HorizontalDivider()

        Text("Glasses Bridge", style = MaterialTheme.typography.titleMedium)
        Text("Service: ${if (serviceRunning) "Running" else "Stopped"}")
        Text("Bluetooth: ${bluetoothState.toDisplayText()}")
        Text("Device: ${deviceName ?: "None"}")
        Text("Latest: $processingStatus")
        Text("Background: ${if (batteryOptimized) "Restricted" else "Allowed"}")
        Text("Screen-off bridge: ${if (serviceRunning) "Active" else "Inactive"}")
        Text(
            "For iQOO/vivo, also enable Auto-start and unrestricted background power for this app.",
            style = MaterialTheme.typography.bodyMedium
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onStart) {
                Text("Start")
            }
            TextButton(onClick = onStop) {
                Text("Stop")
            }
        }

        if (batteryOptimized) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    runCatching {
                        backgroundSettingsLauncher.launch(context.backgroundRunSettingsIntent())
                    }.onFailure {
                        backgroundSettingsLauncher.launch(context.appDetailsSettingsIntent())
                    }
                }
            ) {
                Text("Allow Background")
            }
        }

        vendorBackgroundSettingsIntent?.let { intent ->
            TextButton(
                onClick = {
                    backgroundSettingsLauncher.launch(intent)
                }
            ) {
                Text("Background Power Settings")
            }
        }

        TextButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                runCatching {
                    backgroundSettingsLauncher.launch(context.powerManagerSettingsIntent())
                }.onFailure {
                    backgroundSettingsLauncher.launch(context.appDetailsSettingsIntent())
                }
            }
        ) {
            Text("Power Manager")
        }

        TextButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                backgroundSettingsLauncher.launch(context.appDetailsSettingsIntent())
            }
        ) {
            Text("App Settings")
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = serviceRunning && bluetoothState == BluetoothConnectionState.CONNECTED,
            onClick = {
                PromptStore.savePrompt(context, prompt)
                saveAiSettings()
                KnowledgeBaseStore.saveSelectedKnowledgeBaseId(context, selectedKnowledgeBaseId)
                scope.launch {
                    ServiceBridge.requestCapturePhoto()
                }
            }
        ) {
            Text("Ask Glasses to Capture and Analyze")
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun KnowledgeBaseSelector(
    profiles: List<KnowledgeBaseProfile>,
    selectedId: String,
    onSelected: (String) -> Unit
) {
    val options = listOf(
        KnowledgeBaseProfile(
            id = KnowledgeBaseStore.AUTO_ID,
            name = "Auto",
            description = "Pick the best knowledge base for each request.",
            asset = ""
        ),
        KnowledgeBaseProfile(
            id = KnowledgeBaseStore.NONE_ID,
            name = "None",
            description = "Use only the preset prompt.",
            asset = ""
        )
    ) + profiles

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { profile ->
            val selected = profile.id == selectedId
            if (selected) {
                Button(modifier = Modifier.fillMaxWidth(), onClick = { onSelected(profile.id) }) {
                    Text(profile.name)
                }
            } else {
                ElevatedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSelected(profile.id) }
                ) {
                    Text(profile.name)
                }
            }
        }
    }
}

@Composable
private fun OptionButtons(
    label: String,
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                if (option == selected) {
                    Button(onClick = { onSelected(option) }) {
                        Text(option)
                    }
                } else {
                    TextButton(onClick = { onSelected(option) }) {
                        Text(option)
                    }
                }
            }
        }
    }
}

@Composable
private fun NumberField(
    modifier: Modifier = Modifier,
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    OutlinedTextField(
        modifier = modifier,
        value = value,
        onValueChange = { input -> onValueChange(input.filter { it.isDigit() }) },
        singleLine = true,
        label = { Text(label) }
    )
}

private fun List<KnowledgeBaseProfile>.knowledgeBaseStatusText(selectedId: String): String {
    if (selectedId == KnowledgeBaseStore.AUTO_ID) {
        val count = size
        val chunks = sumOf { it.chunkCount }
        return "Auto-selects from $count knowledge bases ($chunks chunks) for each request."
    }
    if (selectedId == KnowledgeBaseStore.NONE_ID) {
        return "No knowledge base context will be attached."
    }
    val profile = firstOrNull { it.id == selectedId }
        ?: return "Knowledge base assets are not ready yet."
    val size = if (profile.chunkCount > 0) {
        "${profile.chunkCount} chunks, ${profile.includedChars} chars"
    } else {
        "asset configured"
    }
    return "${profile.description} ($size)"
}

private fun android.content.Context.createTempPhotoUri(): Uri {
    val imageDir = File(cacheDir, "camera").apply { mkdirs() }
    val imageFile = File.createTempFile("phone_ai_", ".jpg", imageDir)
    return FileProvider.getUriForFile(this, "${packageName}.fileprovider", imageFile)
}

private fun Context.isBatteryOptimizationActive(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
    val powerManager = getSystemService(PowerManager::class.java)
    return powerManager?.isIgnoringBatteryOptimizations(packageName) != true
}

@SuppressLint("BatteryLife")
private fun Context.backgroundRunSettingsIntent(): Intent {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && isBatteryOptimizationActive()) {
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
    } else {
        appDetailsSettingsIntent()
    }
}

private fun Context.powerManagerSettingsIntent(): Intent {
    val vendorPackages = listOf(
        "com.iqoo.powersaving",
        "com.iqoo.secure",
        "com.vivo.pem",
        "com.vivo.safecenter",
        "com.vivo.permissionmanager",
        "com.vivo.devicepower"
    )
    return vendorPackages
        .asSequence()
        .mapNotNull { packageManager.getLaunchIntentForPackage(it) }
        .firstOrNull()
        ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        }
}

private fun Context.appDetailsSettingsIntent(): Intent {
    return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:$packageName")
    }
}

private fun Context.vendorBackgroundSettingsIntent(): Intent? {
    val candidates = listOf(
        Intent().setComponent(
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
        ),
        Intent().setComponent(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
        ),
        Intent().setComponent(
            ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")
        ),
        Intent().setComponent(
            ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity")
        ),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    )

    return candidates
        .map { intent -> intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        .firstOrNull { intent -> intent.resolveActivity(packageManager) != null }
}

private data class PhonePhotoTestState(
    val message: String = "No phone photo test yet.",
    val isTesting: Boolean = false
)

private object PhonePhotoTestRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val visionClient = CodexRelayVisionClient()
    private val _state = MutableStateFlow(PhonePhotoTestState())
    val state: StateFlow<PhonePhotoTestState> = _state.asStateFlow()

    fun setMessage(message: String) {
        _state.value = _state.value.copy(message = message)
    }

    fun start(
        context: Context,
        photoUri: Uri,
        prompt: String,
        aiSettings: AiRequestSettings,
        knowledgeBaseId: String
    ) {
        if (_state.value.isTesting) {
            _state.value = _state.value.copy(message = "AI test is already running.")
            return
        }

        val appContext = context.applicationContext
        scope.launch {
            var summary = ""
            _state.value = PhonePhotoTestState("Reading photo...", true)
            try {
                val readStartMs = SystemClock.elapsedRealtime()
                val imageBytes = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(photoUri)?.use { it.readBytes() }
                }
                if (imageBytes == null) {
                    _state.value = PhonePhotoTestState("Unable to read captured image.")
                    return@launch
                }

                Log.d(
                    TAG,
                    "Phone photo read: bytes=${imageBytes.size}, readMs=${SystemClock.elapsedRealtime() - readStartMs}"
                )
                _state.value = PhonePhotoTestState("Simulating glasses transfer image...", true)

                val simulateStartMs = SystemClock.elapsedRealtime()
                val simulatedPhoto = GlassesPhotoSimulator.simulate(imageBytes)
                summary = simulatedPhoto.summary(SystemClock.elapsedRealtime() - simulateStartMs)
                Log.d(TAG, "Phone glasses simulation: $summary")

                _state.value = PhonePhotoTestState("$summary\nCalling AI...", true)
                val knowledgePrompt = KnowledgeBaseRepository.buildPrompt(
                    appContext,
                    prompt,
                    knowledgeBaseId
                )
                val knowledgeStatus = knowledgePrompt.profile?.let { profile ->
                    "Using ${profile.name}: ${knowledgePrompt.contextChars} context chars from ${knowledgePrompt.sourceCount} sources."
                } ?: "Knowledge base disabled."
                Log.d(TAG, "Phone AI test knowledge base: $knowledgeStatus")
                _state.value = PhonePhotoTestState("$summary\n$knowledgeStatus\nCalling AI...", true)

                var lastAiProgress = "Starting AI request"
                val result = withTimeoutOrNull(aiSettings.timeoutSeconds * 1_000L) {
                    visionClient.analyze(simulatedPhoto.data, knowledgePrompt.prompt, aiSettings) { progress ->
                        lastAiProgress = progress.toStatusText()
                        _state.value = PhonePhotoTestState(
                            "$summary\n$knowledgeStatus\n$lastAiProgress",
                            true
                        )
                    }
                }

                _state.value = PhonePhotoTestState(
                    when (result) {
                        null -> "$summary\n\nAI test timed out after ${aiSettings.timeoutSeconds}s.\nLast stage: $lastAiProgress"
                        else -> result.fold(
                            onSuccess = { "$summary\n\n${ServiceBridge.cleanMarkdown(it)}" },
                            onFailure = { "$summary\n\nAI test failed: ${it.message ?: "unknown error"}" }
                        )
                    },
                    false
                )
            } catch (e: CancellationException) {
                _state.value = PhonePhotoTestState(
                    (if (summary.isNotBlank()) "$summary\n\n" else "") + "AI test was cancelled."
                )
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Phone AI test failed", e)
                _state.value = PhonePhotoTestState(
                    (if (summary.isNotBlank()) "$summary\n\n" else "") +
                        "AI test failed: ${e.message ?: "unknown error"}"
                )
            } finally {
                if (_state.value.isTesting) {
                    _state.value = _state.value.copy(isTesting = false)
                }
            }
        }
    }
}

private fun BluetoothConnectionState.toDisplayText(): String = when (this) {
    BluetoothConnectionState.DISCONNECTED -> "Disconnected"
    BluetoothConnectionState.LISTENING -> "Waiting"
    BluetoothConnectionState.CONNECTING -> "Connecting"
    BluetoothConnectionState.CONNECTED -> "Connected"
}

private const val TAG = "MainActivity"
