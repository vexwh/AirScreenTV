package com.airscreen.tv.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.media3.exoplayer.ExoPlayer
import androidx.lifecycle.viewModelScope
import com.airscreen.tv.domain.contract.AccessControlManager
import com.airscreen.tv.domain.contract.AppSettingsRepository
import com.airscreen.tv.domain.contract.CastUrlController
import com.airscreen.tv.domain.contract.DiscoveryController
import com.airscreen.tv.domain.contract.RecordingsRepository
import com.airscreen.tv.domain.contract.SessionManager
import com.airscreen.tv.domain.contract.StreamRecorderManager
import com.airscreen.tv.domain.model.AppSettings
import com.airscreen.tv.domain.model.BlockedDevice
import com.airscreen.tv.domain.model.CastPlaybackState
import com.airscreen.tv.domain.model.FpsCap
import com.airscreen.tv.domain.model.MaxResolution
import com.airscreen.tv.domain.model.MultiScreenState
import com.airscreen.tv.domain.model.PinChallengeState
import com.airscreen.tv.domain.model.PlaybackProgress
import com.airscreen.tv.domain.model.RecordedVideoItem
import com.airscreen.tv.domain.model.RecordingState
import com.airscreen.tv.domain.model.SessionId
import com.airscreen.tv.domain.model.SessionInfo
import com.airscreen.tv.media.player.UnifiedExoPlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val discoveryController: DiscoveryController,
    private val sessionManager: SessionManager,
    private val castUrlController: CastUrlController,
    private val streamRecorderManager: StreamRecorderManager,
    private val accessControlManager: AccessControlManager,
    private val recordingsRepository: RecordingsRepository,
    private val settingsRepository: AppSettingsRepository
) : ViewModel() {

    val discoveryStatus = discoveryController.status
    val multiScreenState: StateFlow<MultiScreenState> = sessionManager.multiScreenState
    val castPlaybackState: StateFlow<CastPlaybackState> = castUrlController.playbackState
    val castProgress: StateFlow<PlaybackProgress> = castUrlController.playbackProgress
    val recordingState: StateFlow<RecordingState> = streamRecorderManager.state
    val pinChallengeState: StateFlow<PinChallengeState> = accessControlManager.pinChallengeState
    val blockedDevices: StateFlow<List<BlockedDevice>> = accessControlManager.blockedDevices
    val appSettings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val recordings: StateFlow<List<RecordedVideoItem>> = recordingsRepository.recordings
    val exoPlayer: ExoPlayer? get() = (castUrlController as? UnifiedExoPlayerManager)?.exoPlayer

    private val _sessionRotations = MutableStateFlow<Map<String, Int>>(emptyMap())
    val sessionRotations: StateFlow<Map<String, Int>> = _sessionRotations
    private val _sessionAspectRatios = MutableStateFlow<Map<String, String>>(emptyMap())
    val sessionAspectRatios: StateFlow<Map<String, String>> = _sessionAspectRatios

    fun updateDeviceName(deviceName: String) {
        viewModelScope.launch {
            discoveryController.updateDeviceName(deviceName.trim())
            discoveryController.refresh()
        }
    }

    fun updateMaxResolution(resolution: MaxResolution) {
        viewModelScope.launch {
            settingsRepository.setMaxResolution(resolution)
            discoveryController.refresh()
        }
    }

    fun updateFpsCap(fpsCap: FpsCap) {
        viewModelScope.launch {
            settingsRepository.setFpsCap(fpsCap)
            discoveryController.refresh()
        }
    }

    fun toggleHardwareAccel() {
        viewModelScope.launch {
            val current = settingsRepository.settings.first()
            settingsRepository.setHardwareDecodingEnabled(!current.hardwareDecodingEnabled)
        }
    }

    fun toggleAutoStart() {
        viewModelScope.launch {
            val current = settingsRepository.settings.first()
            settingsRepository.setAutoStartOnBoot(!current.autoStartOnBoot)
        }
    }

    fun togglePinSecurity() {
        viewModelScope.launch {
            val current = settingsRepository.settings.first()
            settingsRepository.setPinSecurityEnabled(!current.pinSecurityEnabled)
            discoveryController.refresh()
        }
    }

    fun approvePinChallenge(alwaysTrust: Boolean) {
        accessControlManager.approvePinChallenge(alwaysTrust)
    }

    fun rejectPinChallenge(blockDevice: Boolean) {
        accessControlManager.rejectPinChallenge(blockDevice)
    }

    fun unblockDevice(id: String) {
        accessControlManager.unblockDevice(id)
    }

    fun togglePlayPause() {
        viewModelScope.launch {
            when (castPlaybackState.value) {
                CastPlaybackState.Playing -> castUrlController.pause()
                CastPlaybackState.Paused -> castUrlController.play()
                else -> castUrlController.play()
            }
        }
    }

    fun seekBy(deltaMs: Long) {
        viewModelScope.launch {
            val target = (castProgress.value.positionMs + deltaMs).coerceAtLeast(0L)
            castUrlController.seekTo(target)
        }
    }

    fun stopCast() {
        viewModelScope.launch { castUrlController.stop() }
    }

    fun bindSurface(sessionId: String, surface: android.view.Surface) {
        sessionManager.bindSurface(sessionId, surface)
    }

    fun unbindSurface(sessionId: String) {
        sessionManager.unbindSurface(sessionId)
    }

    fun stopStagePlayback() {
        viewModelScope.launch {
            sessionManager.closeAll()
            castUrlController.stop()
        }
    }

    fun toggleRecording(sessionId: String) {
        viewModelScope.launch {
            streamRecorderManager.toggleRecording(sessionId)
        }
    }

    fun rotateSession(sessionId: String) {
        sessionManager.rotateSession(sessionId)
    }

    fun cycleAspectRatio(sessionId: String) {
        sessionManager.cycleAspectRatio(sessionId)
    }

    fun setAudioMuted(sessionId: String, muted: Boolean) {
        sessionManager.setAudioMuted(sessionId, muted)
    }

    fun setActiveAudioSession(sessionId: String?) {
        sessionManager.setActiveAudioSession(sessionId)
    }

    fun setCastVolume(percent: Int) {
        viewModelScope.launch { castUrlController.setVolume(percent.coerceIn(0, 100)) }
    }

    fun toggleCastMute() {
        viewModelScope.launch { castUrlController.setMuted(!castProgress.value.isMuted) }
    }

    fun kickSession(sessionId: String) {
        sessionManager.kickSession(sessionId)
        _sessionRotations.value = _sessionRotations.value - sessionId
        _sessionAspectRatios.value = _sessionAspectRatios.value - sessionId
    }

    fun playRecordedFile(item: RecordedVideoItem) {
        viewModelScope.launch {
            recordingsRepository.playRecording(item)
        }
    }

    fun deleteRecordedFile(item: RecordedVideoItem) {
        viewModelScope.launch {
            recordingsRepository.delete(item)
        }
    }

    fun refreshRecordings() {
        viewModelScope.launch { recordingsRepository.refresh() }
    }

    fun sessionById(sessionId: String): SessionInfo? =
        sessionManager.sessions.value.firstOrNull { it.id.value == sessionId }
}