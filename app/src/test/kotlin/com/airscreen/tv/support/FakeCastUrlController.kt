package com.airscreen.tv.support

import com.airscreen.tv.domain.contract.CastUrlController
import com.airscreen.tv.domain.model.CastPlaybackState
import com.airscreen.tv.domain.model.PlaybackProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeCastUrlController : CastUrlController {
    private val _playbackState = MutableStateFlow<CastPlaybackState>(CastPlaybackState.Idle)
    private val _playbackProgress = MutableStateFlow(PlaybackProgress())
    override val playbackState: StateFlow<CastPlaybackState> = _playbackState.asStateFlow()
    override val playbackProgress: StateFlow<PlaybackProgress> = _playbackProgress.asStateFlow()

    var lastUrl: String? = null
    var lastTitle: String? = null
    var lastMimeType: String? = null
    var lastStartPositionMs: Long = 0L
    var lastSeekPositionMs: Long = 0L
    var lastVolumePercent: Int = 100
    var lastMuted: Boolean = false
    var photoBytes: ByteArray? = null
    var playCount: Int = 0
    var pauseCount: Int = 0
    var stopCount: Int = 0

    override suspend fun loadMedia(
        url: String,
        title: String?,
        mimeType: String?,
        startPositionMs: Long,
        autoPlay: Boolean
    ) {
        lastUrl = url
        lastTitle = title
        lastMimeType = mimeType
        lastStartPositionMs = startPositionMs
        _playbackState.value = if (autoPlay) CastPlaybackState.Playing else CastPlaybackState.Paused
        _playbackProgress.value = PlaybackProgress(
            positionMs = startPositionMs,
            durationMs = 100_000L,
            isMuted = false,
            volumePercent = 100
        )
    }

    override suspend fun displayPhoto(jpegOrPngBytes: ByteArray) {
        photoBytes = jpegOrPngBytes.copyOf()
        _playbackState.value = CastPlaybackState.Paused
        _playbackProgress.value = PlaybackProgress()
    }

    override suspend fun play() {
        playCount++
        _playbackState.value = CastPlaybackState.Playing
    }

    override suspend fun pause() {
        pauseCount++
        _playbackState.value = CastPlaybackState.Paused
    }

    override suspend fun stop() {
        stopCount++
        _playbackState.value = CastPlaybackState.Idle
        _playbackProgress.value = PlaybackProgress()
    }

    override suspend fun seekTo(positionMs: Long) {
        lastSeekPositionMs = positionMs
        _playbackProgress.value = _playbackProgress.value.copy(positionMs = positionMs)
    }

    override fun systemVolumePercent(): Int = lastVolumePercent

    override suspend fun setVolume(percent: Int) {
        lastVolumePercent = percent
        _playbackProgress.value = _playbackProgress.value.copy(volumePercent = percent)
    }

    override suspend fun setMuted(muted: Boolean) {
        lastMuted = muted
        _playbackProgress.value = _playbackProgress.value.copy(isMuted = muted)
    }
}