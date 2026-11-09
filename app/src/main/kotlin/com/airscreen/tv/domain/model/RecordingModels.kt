package com.airscreen.tv.domain.model

import android.graphics.Bitmap

data class RecordedVideoItem(
    val id: String,
    val title: String,
    val fileName: String,
    val filePath: String,
    val fileSizeBytes: Long,
    val durationMs: Long,
    val resolution: String,
    val createdAtEpochMs: Long,
    val mimeType: String = "video/mp4",
    val thumbnailBitmap: Bitmap? = null
) {
    val sizeBytes: Long get() = fileSizeBytes
}

sealed interface RecordingState {
    data object Idle : RecordingState

    data class Recording(
        val sessionId: String,
        val filePath: String,
        val elapsedMs: Long,
        val bytesWritten: Long
    ) : RecordingState

    data class Completed(
        val fileUri: String,
        val durationMs: Long
    ) : RecordingState

    data class Error(val message: String) : RecordingState
}