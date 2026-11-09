package com.airscreen.tv.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.airscreen.tv.airplay2.AirPlayEngineController
import com.airscreen.tv.domain.contract.AppSettingsRepository
import io.github.jqssun.airplay.service.AirPlayService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject
    lateinit var settingsRepository: AppSettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val settings = settingsRepository.settings.first()
                if (settings.autoStartOnBoot) {
                    AirPlayEngineController.configure(
                        context,
                        settings.fpsCap.fps,
                        settings.maxResolution.width,
                        settings.maxResolution.height,
                        settings.deviceName,
                        settings.pinSecurityEnabled,
                        settings.hardwareDecodingEnabled
                    )
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, ReceiverForegroundService::class.java)
                    )
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, AirPlayService::class.java)
                            .setAction(AirPlayService.ACTION_START_SERVER)
                    )
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to auto-start receiver", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "AirScreenBoot"
    }
}