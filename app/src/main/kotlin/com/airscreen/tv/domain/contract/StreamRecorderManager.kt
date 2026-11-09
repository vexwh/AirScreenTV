package com.airscreen.tv.domain.contract

import com.airscreen.tv.domain.model.AudioStreamConfig
import com.airscreen.tv.domain.model.EncodedAudioFrame
import com.airscreen.tv.domain.model.EncodedVideoFrame
import com.airscreen.tv.domain.model.RecordingState
import com.airscreen.tv.domain.model.VideoStreamConfig
import kotlinx.coroutines.flow.StateFlow

interface StreamRecorderManager {
    val state: StateFlow<RecordingState>

    fun startRecording(sessionId: String)
    fun stopRecording()
    suspend fun toggleRecording(sessionId: String)

    fun onVideoConfig(sessionId: String, config: VideoStreamConfig)
    fun onVideoFrame(sessionId: String, frame: EncodedVideoFrame)
    fun onAudioConfig(sessionId: String, config: AudioStreamConfig)
    fun onAudioFrame(sessionId: String, frame: EncodedAudioFrame)
}
