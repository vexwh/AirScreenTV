package com.airscreen.tv.domain.model

import android.graphics.Bitmap

sealed class CastPlaybackState {
    data object Idle : CastPlaybackState()
    data object Loading : CastPlaybackState()
    data object Playing : CastPlaybackState()
    data object Paused : CastPlaybackState()
    data object Ended : CastPlaybackState()
    data class PhotoDisplayed(val bitmap: Bitmap) : CastPlaybackState()
    data class Error(val reason: String) : CastPlaybackState()
}

data class PlaybackProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isMuted: Boolean = false,
    val volumePercent: Int = 100
)

enum class CastMediaKind {
    VIDEO,
    AUDIO,
    IMAGE,
    UNKNOWN
}
