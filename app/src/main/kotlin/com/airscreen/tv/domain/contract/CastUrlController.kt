package com.airscreen.tv.domain.contract

import com.airscreen.tv.domain.model.CastPlaybackState
import com.airscreen.tv.domain.model.PlaybackProgress
import kotlinx.coroutines.flow.StateFlow

interface CastUrlController {
    val playbackState: StateFlow<CastPlaybackState>
    val playbackProgress: StateFlow<PlaybackProgress>

    suspend fun loadMedia(
        url: String,
        title: String? = null,
        mimeType: String? = null,
        startPositionMs: Long = 0L,
        autoPlay: Boolean = true
    )

    suspend fun displayPhoto(jpegOrPngBytes: ByteArray)

    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    suspend fun seekTo(positionMs: Long)
    fun systemVolumePercent(): Int
    suspend fun setVolume(percent: Int)
    suspend fun setMuted(muted: Boolean)
}