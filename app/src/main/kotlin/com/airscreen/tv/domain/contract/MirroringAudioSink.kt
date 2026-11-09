package com.airscreen.tv.domain.contract

import com.airscreen.tv.domain.model.AudioStreamConfig
import com.airscreen.tv.domain.model.EncodedAudioFrame
import com.airscreen.tv.domain.model.FrameDisposition

interface MirroringAudioSink {
    fun onAudioConfig(config: AudioStreamConfig)

    fun onAudioFrame(frame: EncodedAudioFrame): FrameDisposition

    fun onFlush()

    fun onStreamEnd()
}
