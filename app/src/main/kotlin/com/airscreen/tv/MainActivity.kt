package com.airscreen.tv

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airscreen.tv.airplay2.AirPlayEngineController
import com.airscreen.tv.core.service.ReceiverForegroundService
import com.airscreen.tv.domain.model.CastPlaybackState
import com.airscreen.tv.ui.components.AirPlayPinPrompt
import com.airscreen.tv.ui.components.PinChallengeModal
import com.airscreen.tv.ui.components.ProSettingsDrawer
import com.airscreen.tv.ui.dashboard.HomeDashboardRoute
import com.airscreen.tv.ui.stage.AirPlay2StageScreen
import io.github.jqssun.airplay.service.StageVisibility
import com.airscreen.tv.ui.stage.MirroringAndCastStageRoute
import com.airscreen.tv.ui.theme.AirScreenTvTheme
import com.airscreen.tv.ui.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (AirPlayEngineController.pin.value != null) {
                AirPlayEngineController.cancelPin()
            } else if (AirPlayEngineController.hasActiveSession()) {
                AirPlayEngineController.disconnect()
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestNotificationPermission()
        onBackPressedDispatcher.addCallback(this, backCallback)
        startReceiverService()
        AirPlayEngineController.start(this)
        setContent {
            AirScreenTvTheme {
                AirScreenRoot(viewModel)
            }
        }
    }

    @Composable
    private fun AirScreenRoot(viewModel: MainViewModel) {
        val multiScreen by viewModel.multiScreenState.collectAsStateWithLifecycle()
        val engineConnections by AirPlayEngineController.connectionCount.collectAsStateWithLifecycle()
        val engineVideo by AirPlayEngineController.playbackActive.collectAsStateWithLifecycle()
        val engineMirroring by AirPlayEngineController.mirroringActive.collectAsStateWithLifecycle()
        val enginePin by AirPlayEngineController.pin.collectAsStateWithLifecycle()
        val castState by viewModel.castPlaybackState.collectAsStateWithLifecycle()
        val pinState by viewModel.pinChallengeState.collectAsStateWithLifecycle()
        val settings by viewModel.appSettings.collectAsStateWithLifecycle()
        var settingsOpen by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(
            settings.fpsCap,
            settings.maxResolution,
            settings.deviceName,
            settings.pinSecurityEnabled,
            settings.hardwareDecodingEnabled
        ) {
            AirPlayEngineController.configure(
                this@MainActivity,
                settings.fpsCap.fps,
                settings.maxResolution.width,
                settings.maxResolution.height,
                settings.deviceName,
                settings.pinSecurityEnabled,
                settings.hardwareDecodingEnabled
            )
        }

        val stageActive = multiScreen.sessions.isNotEmpty() ||
            castState is CastPlaybackState.Loading ||
            castState is CastPlaybackState.Playing ||
            castState is CastPlaybackState.Paused ||
            castState is CastPlaybackState.PhotoDisplayed
        val screen = when {
            engineVideo || engineMirroring -> AppScreen.AIRPLAY2_STAGE
            stageActive -> AppScreen.STAGE
            else -> AppScreen.DASHBOARD
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = screen,
                label = "root-screen",
                transitionSpec = {
                    (
                        fadeIn(tween(220)) +
                            slideInHorizontally(
                                animationSpec = tween(220),
                                initialOffsetX = { it / 14 }
                            )
                        ) togetherWith fadeOut(tween(160))
                }
            ) { target ->
                when (target) {
                    AppScreen.AIRPLAY2_STAGE -> AirPlay2StageScreen(
                        onExit = { AirPlayEngineController.disconnect() }
                    )
                    AppScreen.STAGE -> MirroringAndCastStageRoute(
                        viewModel = viewModel,
                        onExit = { }
                    )
                    AppScreen.DASHBOARD -> HomeDashboardRoute(
                        viewModel = viewModel,
                        onOpenRecordings = { },
                        onOpenSettings = { settingsOpen = true }
                    )
                }
            }
            ProSettingsDrawer(
                visible = settingsOpen,
                settings = settings,
                onDismiss = { settingsOpen = false },
                onDeviceNameChange = viewModel::updateDeviceName,
                onSelectResolution = viewModel::updateMaxResolution,
                onSelectFps = viewModel::updateFpsCap,
                onToggleHardwareAcceleration = viewModel::toggleHardwareAccel,
                onToggleAutoStart = viewModel::toggleAutoStart,
                onTogglePinSecurity = viewModel::togglePinSecurity
            )
            PinChallengeModal(
                state = pinState,
                onAllowOnce = { viewModel.approvePinChallenge(false) },
                onAlwaysTrust = { viewModel.approvePinChallenge(true) },
                onBlockDevice = { viewModel.rejectPinChallenge(true) }
            )
            enginePin?.let { pin ->
                AirPlayPinPrompt(pin) { AirPlayEngineController.cancelPin() }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            Log.i(TAG, "Back pressed")
            if (AirPlayEngineController.pin.value != null) {
                AirPlayEngineController.cancelPin()
            } else if (AirPlayEngineController.hasActiveSession()) {
                AirPlayEngineController.disconnect()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        StageVisibility.appVisible.value = true
    }

    override fun onStop() {
        StageVisibility.appVisible.value = false
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startReceiverService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, ReceiverForegroundService::class.java)
        )
    }

    private enum class AppScreen {
        DASHBOARD,
        STAGE,
        AIRPLAY2_STAGE
    }

    private companion object {
        const val TAG = "AirScreenActivity"
    }
}