package com.airscreen.tv.airplay2

import android.annotation.SuppressLint

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import androidx.core.content.edit
import android.os.IBinder
import android.view.Surface
import androidx.core.content.ContextCompat
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.service.AirPlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object AirPlayEngineController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _connectionCount = MutableStateFlow(0)
    val connectionCount = _connectionCount.asStateFlow()
    private val _engineReady = MutableStateFlow(false)
    val engineReady = _engineReady.asStateFlow()
    private val _videoAspect = MutableStateFlow(16f / 9f)
    val videoAspect = _videoAspect.asStateFlow()
    private val _playbackAspect = MutableStateFlow(16f / 9f)
    val playbackAspect = _playbackAspect.asStateFlow()
    private val _playbackActive = MutableStateFlow(false)
    val playbackActive = _playbackActive.asStateFlow()
    private val _pin = MutableStateFlow<String?>(null)
    val pin = _pin.asStateFlow()
    private val _mirroring = MutableStateFlow(false)
    val mirroringActive = _mirroring.asStateFlow()

    // Process-wide binding; cleared in stop()/onServiceDisconnected().
    @SuppressLint("StaticFieldLeak")
    @Volatile private var service: AirPlayService? = null
    private var serviceJob: Job? = null
    private var mirrorJob: Job? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? AirPlayService.LocalBinder ?: return
            val connected = local.service
            service = connected
            serviceJob?.cancel()
            serviceJob = scope.launch {
                kotlinx.coroutines.flow.combine(
                    connected.connectionCount,
                    connected.videoAspect,
                    connected.videoPlaybackAspect,
                    connected.videoPlaybackActive,
                    connected.pin
                ) { count, aspect, playbackAspect, playbackActive, pin ->
                    _connectionCount.value = count
                    _videoAspect.value = aspect
                    _playbackAspect.value = playbackAspect
                    _playbackActive.value = playbackActive
                    _pin.value = pin
                }.collect { }
            }
            mirrorJob?.cancel()
            mirrorJob = scope.launch {
                connected.mirroringActive.collect { _mirroring.value = it }
            }
            connected.startServer()
            _engineReady.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceJob?.cancel()
            mirrorJob?.cancel()
            _mirroring.value = false
            service = null
            bound = false
            _engineReady.value = false
            _connectionCount.value = 0
        }
    }

    fun start(context: Context) {
        val app = context.applicationContext
        val intent = Intent(app, AirPlayService::class.java)
        ContextCompat.startForegroundService(app, intent)
        if (!bound) {
            bound = true
            app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    fun hasActiveSession(): Boolean = _playbackActive.value || _mirroring.value

    fun cancelPin() {
        service?.cancelPin()
    }

    fun disconnect() {
        service?.disconnectSenders()
        _connectionCount.value = 0
        _playbackActive.value = false
        _mirroring.value = false
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        serviceJob?.cancel()
        mirrorJob?.cancel()
        _mirroring.value = false
        service?.stopServer()
        service = null
        if (bound) {
            runCatching { app.unbindService(connection) }
            bound = false
        }
        app.stopService(Intent(app, AirPlayService::class.java))
        _engineReady.value = false
        _connectionCount.value = 0
    }

    fun configure(
        context: Context,
        maxFps: Int,
        width: Int,
        height: Int,
        name: String,
        requirePin: Boolean,
        hardware: Boolean = true
    ) {
        val prefs = context.applicationContext.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val resolution = if (width <= 0 || height <= 0) "auto" else width.toString() + "x" + height
        val editor = prefs.edit()
        var changed = false
        val previouslyRequired = prefs.getBoolean(Prefs.REQUIRE_PIN.key, false)
        if (requirePin && !previouslyRequired) {
            editor.putBoolean(Prefs.PIN_PAIRED.key, false)
            changed = true
        }
        if (prefs.getInt(Prefs.MAX_FPS.key, -1) != maxFps) {
            editor.putInt(Prefs.MAX_FPS.key, maxFps)
            changed = true
        }
        if (prefs.getString(Prefs.RESOLUTION.key, null) != resolution) {
            editor.putString(Prefs.RESOLUTION.key, resolution)
            changed = true
        }
        if (prefs.getString(Prefs.SERVER_NAME.key, null) != name) {
            editor.putString(Prefs.SERVER_NAME.key, name)
            changed = true
        }
        if (prefs.getBoolean(Prefs.HARDWARE_DECODING.key, true) != hardware) {
            editor.putBoolean(Prefs.HARDWARE_DECODING.key, hardware)
            changed = true
        }
        if (prefs.getBoolean(Prefs.REQUIRE_PIN.key, false) != requirePin) {
            editor.putBoolean(Prefs.REQUIRE_PIN.key, requirePin)
            changed = true
        }
        if (changed) editor.apply()
    }

    fun setSurface(surface: Surface) {
        val current = service ?: return
        current.setVideoSurface(surface)
    }

    fun clearSurface(surface: Surface) {
        val current = service ?: return
        current.clearVideoSurface(surface)
    }

    fun setPlaybackSurface(surface: Surface) {
        service?.setVideoPlaybackSurface(surface)
    }

    fun clearPlaybackSurface(surface: Surface) {
        service?.clearVideoPlaybackSurface(surface)
    }
}