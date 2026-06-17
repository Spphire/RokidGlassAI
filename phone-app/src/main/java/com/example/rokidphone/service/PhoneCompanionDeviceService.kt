package com.example.rokidphone.service

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class PhoneCompanionDeviceService : CompanionDeviceService() {
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        val label = associationInfo.displayName?.toString()
            ?: associationInfo.deviceMacAddress?.toString()
            ?: "#${associationInfo.id}"
        Log.d(TAG, "Companion device appeared: $label")
        PhoneAIServiceRuntimeState.record(
            this,
            "companion device appeared",
            mapOf(
                "associationId" to associationInfo.id,
                "displayName" to associationInfo.displayName,
                "deviceAddress" to associationInfo.deviceMacAddress
            )
        )
        ServiceBridge.updateCompanionStatus("Companion: present $label")
        PhoneAIService.startIfAutoRunEnabled(this, "companion device appeared")
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        val label = associationInfo.displayName?.toString()
            ?: associationInfo.deviceMacAddress?.toString()
            ?: "#${associationInfo.id}"
        Log.d(TAG, "Companion device disappeared: $label")
        PhoneAIServiceRuntimeState.record(
            this,
            "companion device disappeared",
            mapOf(
                "associationId" to associationInfo.id,
                "displayName" to associationInfo.displayName,
                "deviceAddress" to associationInfo.deviceMacAddress
            )
        )
        ServiceBridge.updateCompanionStatus("Companion: away $label")
        PhoneAIService.startIfAutoRunEnabled(this, "companion device disappeared")
    }

    @Deprecated("Legacy callback for pre-AssociationInfo devices")
    override fun onDeviceAppeared(address: String) {
        Log.d(TAG, "Companion device appeared: $address")
        PhoneAIServiceRuntimeState.record(
            this,
            "companion device appeared",
            mapOf("deviceAddress" to address)
        )
        ServiceBridge.updateCompanionStatus("Companion: present $address")
        PhoneAIService.startIfAutoRunEnabled(this, "companion device appeared")
    }

    @Deprecated("Legacy callback for pre-AssociationInfo devices")
    override fun onDeviceDisappeared(address: String) {
        Log.d(TAG, "Companion device disappeared: $address")
        PhoneAIServiceRuntimeState.record(
            this,
            "companion device disappeared",
            mapOf("deviceAddress" to address)
        )
        ServiceBridge.updateCompanionStatus("Companion: away $address")
        PhoneAIService.startIfAutoRunEnabled(this, "companion device disappeared")
    }

    private companion object {
        private const val TAG = "PhoneCompanionDeviceSvc"
    }
}
