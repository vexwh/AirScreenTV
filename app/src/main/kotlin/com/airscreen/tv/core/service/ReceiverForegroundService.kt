package com.airscreen.tv.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.airscreen.tv.MainActivity
import com.airscreen.tv.R
import com.airscreen.tv.core.network.NetworkEnvironmentManager
import com.airscreen.tv.protocols.airplay.rtsp.AirPlayRtspServer
import com.airscreen.tv.domain.contract.AppSettingsRepository
import com.airscreen.tv.domain.contract.DiscoveryController
import com.airscreen.tv.domain.model.DiscoveryConfig
import com.airscreen.tv.domain.model.DiscoveryStatus
import com.airscreen.tv.domain.model.NetworkState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

@AndroidEntryPoint
class ReceiverForegroundService : LifecycleService() {

    @Inject
    lateinit var discoveryController: DiscoveryController

    @Inject
    lateinit var networkEnvironment: NetworkEnvironmentManager

    @Inject
    lateinit var settingsRepository: AppSettingsRepository

    @Inject
    lateinit var airPlayRtspServer: AirPlayRtspServer

    private var discoveryStarted = false
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        foregroundStarted = promoteToForeground(
            buildNotification(
                title = getString(R.string.notification_title),
                text = getString(R.string.notification_starting)
            )
        )
        if (foregroundStarted) {
            observeNetworkAndStatus()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!foregroundStarted) {
            foregroundStarted = promoteToForeground(
                buildNotification(
                    title = getString(R.string.notification_title),
                    text = getString(R.string.notification_starting)
                )
            )
            if (foregroundStarted) {
                observeNetworkAndStatus()
            }
        }
        return START_STICKY
    }

    private fun startRtspServer() {
        lifecycleScope.launch(Dispatchers.IO) {
            airPlayRtspServer.start()
        }
    }

    private fun observeNetworkAndStatus() {
        lifecycleScope.launch {
            networkEnvironment.state.collect { network ->
                when (network) {
                    is NetworkState.Disconnected -> {
                        if (discoveryStarted) {
                            discoveryStarted = false
                            try {
                                discoveryController.stop()
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (error: Throwable) {
                                Log.w(TAG, "Unable to stop discovery after disconnect", error)
                            }
                        }
                    }

                    is NetworkState.Connected -> {
                        if (!discoveryStarted) {
                            startDiscovery()
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            discoveryController.status.collect { status ->
                updateNotification(status)
            }
        }
    }

    private suspend fun startDiscovery() {
        try {
            val settings = settingsRepository.settings.first()
            val config = DiscoveryConfig(
                deviceName = settings.deviceName,
                airPlayPort = AIRPLAY_PORT,
                raopPort = RAOP_PORT,
                pinRequired = settings.pinSecurityEnabled,
                maxWidth = settings.maxResolution.width,
                maxHeight = settings.maxResolution.height,
                maxFps = settings.fpsCap.fps,
                advertiseAirPlay = false
            )
            discoveryController.start(config)
            discoveryStarted = true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            discoveryStarted = false
            Log.e(TAG, "Unable to start discovery", error)
        }
    }

    private fun promoteToForeground(notification: Notification): Boolean {
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
            return true
        } catch (security: SecurityException) {
            Log.w(TAG, "Both foreground service types were rejected", security)
        } catch (runtime: RuntimeException) {
            Log.w(TAG, "Foreground promotion failed", runtime)
        }

        val fallbackType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fallbackType)
            true
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to promote receiver to foreground", error)
            stopSelf()
            false
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.receiver_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = getString(R.string.receiver_channel_description)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(status: DiscoveryStatus) {
        if (!foregroundStarted) return
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val title = status.deviceName.ifBlank { getString(R.string.notification_title) }
        val text = status.ipAddress?.let { ipAddress ->
            getString(R.string.notification_listening, ipAddress)
        } ?: getString(R.string.notification_waiting)
        try {
            NotificationManagerCompat.from(this).notify(
                NOTIFICATION_ID,
                buildNotification(title, text)
            )
        } catch (security: SecurityException) {
            Log.d(TAG, "Notification update skipped", security)
        }
    }

    override fun onDestroy() {
        discoveryStarted = false
        try {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                    airPlayRtspServer.stop()
                }
                withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                    discoveryController.stop()
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to stop receiver during service destroy", error)
        }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "AirScreenService"
        const val CHANNEL_ID = "airscreen_receiver_channel"
        const val NOTIFICATION_ID = 1001
        const val AIRPLAY_PORT = 7000
        const val RAOP_PORT = 5000
        const val SHUTDOWN_TIMEOUT_MS = 3000L
    }
}