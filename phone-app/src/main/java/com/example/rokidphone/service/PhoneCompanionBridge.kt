package com.example.rokidphone.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.annotation.RequiresApi
import java.util.regex.Pattern

object PhoneCompanionBridge {
    private const val TAG = "PhoneCompanionBridge"
    private const val GLASSES_NAME_PATTERN = "(?i).*(rokid|glasses?|rg[_ -]?glasses).*"

    fun refreshStatus(context: Context): String {
        val status = associationStatus(context)
        ServiceBridge.updateCompanionStatus(status)
        return status
    }

    fun requestAssociation(
        context: Context,
        launchIntentSender: (IntentSender) -> Unit,
        onStatus: (String) -> Unit = ServiceBridge::updateCompanionStatus
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onStatus("Companion: requires Android 13+")
            return
        }

        val manager = context.getSystemService(CompanionDeviceManager::class.java)
        if (manager == null) {
            onStatus("Companion: unavailable on this device")
            return
        }

        val existingStatus = associationStatus(context)
        if (hasAssociation(context)) {
            startObservingAssociatedDevices(context)
            onStatus(existingStatus)
            return
        }

        val bondedCandidates = bondedGlassesCandidates(context)
        val request = AssociationRequest.Builder()
            .apply {
                if (bondedCandidates.isNotEmpty()) {
                    bondedCandidates.forEach { candidate ->
                        addDeviceFilter(
                            BluetoothDeviceFilter.Builder()
                                .setAddress(candidate.address)
                                .build()
                        )
                    }
                } else {
                    addDeviceFilter(
                        BluetoothDeviceFilter.Builder()
                            .setNamePattern(Pattern.compile(GLASSES_NAME_PATTERN))
                            .build()
                    )
                }
            }
            .setSingleDevice(bondedCandidates.size <= 1)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    setDeviceProfile(AssociationRequest.DEVICE_PROFILE_GLASSES)
                }
            }
            .build()

        val searchStatus = if (bondedCandidates.isNotEmpty()) {
            "Companion: associating ${bondedCandidates.joinToString { it.name }}"
        } else {
            "Companion: searching for Rokid glasses"
        }
        onStatus(searchStatus)
        associateOnAndroid13Plus(context, manager, request, launchIntentSender, onStatus)
    }

    fun handleAssociationResult(context: Context, resultCode: Int, data: Intent?): String {
        if (resultCode != Activity.RESULT_OK) {
            return "Companion: association canceled"
        }

        val detail = associationDetailFromResult(data)
        startObservingAssociatedDevices(context)
        PhoneAIService.startIfAutoRunEnabled(context, "companion association approved")
        return detail ?: associationStatus(context)
    }

    fun startObservingAssociatedDevices(context: Context): String {
        val manager = context.getSystemService(CompanionDeviceManager::class.java)
            ?: return "Companion: unavailable on this device"

        val addresses = runCatching { manager.associations }.getOrDefault(emptyList())
        if (addresses.isEmpty()) {
            return "Companion: not associated"
        }

        addresses.forEach { address ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    manager.startObservingDevicePresence(address)
                }.onFailure { error ->
                    Log.w(TAG, "Unable to observe companion device presence for $address", error)
                }
            }
        }

        val status = "Companion: observing ${addresses.size} device(s)"
        ServiceBridge.updateCompanionStatus(status)
        return status
    }

    internal fun hasAssociation(context: Context): Boolean {
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return false
        return runCatching { manager.associations.isNotEmpty() }.getOrDefault(false)
    }

    internal fun associationStatus(context: Context): String {
        val manager = context.getSystemService(CompanionDeviceManager::class.java)
            ?: return "Companion: unavailable on this device"

        val names = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                manager.myAssociations.map { it.displayNameForStatus() }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        if (names.isNotEmpty()) {
            return "Companion: associated ${names.joinToString()}"
        }

        val addresses = runCatching { manager.associations }.getOrDefault(emptyList())
        return if (addresses.isEmpty()) {
            "Companion: not associated"
        } else {
            "Companion: associated ${addresses.joinToString()}"
        }
    }

    internal fun matchesGlassesCandidateName(name: String): Boolean {
        return Pattern.compile(GLASSES_NAME_PATTERN).matcher(name).matches()
    }

    internal fun bondedGlassesCandidates(context: Context): List<CompanionCandidate> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.orEmpty()
                .mapNotNull { device ->
                    val name = device.name.orEmpty()
                    val address = device.address.orEmpty()
                    if (name.isNotBlank() &&
                        address.isNotBlank() &&
                        matchesGlassesCandidateName(name)
                    ) {
                        CompanionCandidate(name = name, address = address)
                    } else {
                        null
                    }
                }
                .distinctBy { it.address.lowercase() }
        }.getOrElse { error ->
            Log.w(TAG, "Unable to read bonded glasses candidates", error)
            emptyList()
        }
    }

    private fun associationDetailFromResult(data: Intent?): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || data == null) {
            return null
        }

        return runCatching {
            val association = data.getParcelableExtra(
                CompanionDeviceManager.EXTRA_ASSOCIATION,
                AssociationInfo::class.java
            )
            association?.let { "Companion: associated ${it.displayNameForStatus()}" }
        }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun associateOnAndroid13Plus(
        context: Context,
        manager: CompanionDeviceManager,
        request: AssociationRequest,
        launchIntentSender: (IntentSender) -> Unit,
        onStatus: (String) -> Unit
    ) {
        manager.associate(
            request,
            context.mainExecutor,
            object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    onStatus("Companion: approve the glasses association")
                    launchIntentSender(intentSender)
                }

                override fun onDeviceFound(intentSender: IntentSender) {
                    onStatus("Companion: approve the glasses association")
                    launchIntentSender(intentSender)
                }

                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    val status = "Companion: associated ${associationInfo.displayNameForStatus()}"
                    PhoneAIServiceRuntimeState.record(
                        context,
                        "companion association created",
                        associationInfo.toRuntimeDetails()
                    )
                    startObservingAssociatedDevices(context)
                    PhoneAIService.startIfAutoRunEnabled(context, "companion association created")
                    onStatus(status)
                }

                override fun onFailure(error: CharSequence?) {
                    val message = error?.toString()?.takeIf { it.isNotBlank() } ?: "unknown error"
                    Log.w(TAG, "Companion association failed: $message")
                    onStatus("Companion: association failed: $message")
                }

                override fun onFailure(errorCode: Int, error: CharSequence?) {
                    val message = error?.toString()?.takeIf { it.isNotBlank() } ?: "code $errorCode"
                    Log.w(TAG, "Companion association failed: $message")
                    onStatus("Companion: association failed: $message")
                }
            }
        )
    }
}

data class CompanionCandidate(
    val name: String,
    val address: String
)

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun AssociationInfo.displayNameForStatus(): String {
    return displayName?.toString()?.takeIf { it.isNotBlank() }
        ?: deviceMacAddress?.toString()
        ?: "#$id"
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun AssociationInfo.toRuntimeDetails(): Map<String, Any?> {
    return mapOf(
        "associationId" to id,
        "displayName" to displayName,
        "deviceAddress" to deviceMacAddress,
        "deviceProfile" to deviceProfile
    )
}
