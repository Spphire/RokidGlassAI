package com.example.rokidphone.service

import android.app.AlarmManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PhoneAIServiceAutoRunTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("rokid_phone_ai_service", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        shadowOf(context.applicationContext as android.app.Application).clearStartedServices()
    }

    @Test
    fun `auto run is enabled by default and can be disabled`() {
        assertThat(PhoneAIServiceRunPolicy.isAutoRunEnabled(context)).isTrue()

        PhoneAIServiceRunPolicy.setAutoRunEnabled(context, false)

        assertThat(PhoneAIServiceRunPolicy.isAutoRunEnabled(context)).isFalse()
    }

    @Test
    fun `auto start receiver starts service for boot when policy enabled`() {
        PhoneAIServiceAutoStartReceiver().onReceive(
            context,
            Intent(Intent.ACTION_BOOT_COMPLETED)
        )

        val started = shadowOf(context.applicationContext as android.app.Application)
            .nextStartedService
        assertThat(started.component?.className).isEqualTo(PhoneAIService::class.java.name)
    }

    @Test
    fun `auto start receiver ignores boot when policy disabled`() {
        PhoneAIServiceRunPolicy.setAutoRunEnabled(context, false)

        PhoneAIServiceAutoStartReceiver().onReceive(
            context,
            Intent(Intent.ACTION_BOOT_COMPLETED)
        )

        val started = shadowOf(context.applicationContext as android.app.Application)
            .nextStartedService
        assertThat(started).isNull()
    }

    @Test
    fun `auto start rule only accepts useful bluetooth state changes`() {
        assertThat(
            shouldAutoStartForIntent(
                Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                    .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON)
            )
        ).isTrue()

        assertThat(
            shouldAutoStartForIntent(
                Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                    .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
            )
        ).isFalse()
        assertThat(shouldAutoStartForIntent(Intent("not.relevant"))).isFalse()
    }

    @Test
    fun `restart alarm is only scheduled when auto run is enabled`() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val shadowAlarm = shadowOf(alarmManager)

        PhoneAIServiceRunPolicy.setAutoRunEnabled(context, false)
        PhoneAIServiceRestartReceiver.scheduleRestartIfAutoRunEnabled(context)
        assertThat(shadowAlarm.nextScheduledAlarm).isNull()

        PhoneAIServiceRunPolicy.setAutoRunEnabled(context, true)
        PhoneAIServiceRestartReceiver.scheduleRestartIfAutoRunEnabled(context)
        assertThat(shadowAlarm.nextScheduledAlarm).isNotNull()
    }
}
