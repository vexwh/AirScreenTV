package com.airscreen.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import com.airscreen.tv.ui.components.AirPlayPinPrompt
import com.airscreen.tv.ui.theme.AirScreenTvTheme
import io.github.jqssun.airplay.service.AirPlayService

class PinPromptActivity : ComponentActivity() {
    private val pinCleared = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pin = intent.getStringExtra(EXTRA_PIN).orEmpty()
        if (pin.isBlank()) {
            finish()
            return
        }
        ContextCompat.registerReceiver(
            this,
            pinCleared,
            IntentFilter(AirPlayService.ACTION_PIN_CLEARED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        setContent {
            AirScreenTvTheme {
                AirPlayPinPrompt(pin) { cancelPin() }
            }
        }
    }

    private fun cancelPin() {
        startService(
            Intent(this, AirPlayService::class.java)
                .setAction(AirPlayService.ACTION_CANCEL_PIN)
        )
        finish()
    }

    override fun onDestroy() {
        try { unregisterReceiver(pinCleared) } catch (_: Throwable) {}
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PIN = "pin"
    }
}
