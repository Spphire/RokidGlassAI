package com.example.rokidphone

import android.app.Application
import com.example.rokidphone.service.NotificationChannels

class PhoneApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureServiceChannel(this)
    }
}
