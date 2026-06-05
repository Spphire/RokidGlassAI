package com.example.rokidphone.service

import android.app.NotificationManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.rokidcommon.Constants
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class NotificationChannelsTest {
    @Test
    fun `ensureServiceChannel creates photo ai foreground channel`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        NotificationChannels.ensureServiceChannel(context)

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val channel = notificationManager.getNotificationChannel(Constants.NOTIFICATION_CHANNEL_ID)
        assertThat(channel.name.toString()).isEqualTo("Rokid Photo AI")
        assertThat(channel.description).contains("photo AI bridge")
        assertThat(channel.description).doesNotContain("Voice")
    }
}
