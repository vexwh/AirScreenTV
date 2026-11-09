package com.airscreen.tv.domain.contract

import com.airscreen.tv.domain.model.EncodedVideoFrame
import com.airscreen.tv.domain.model.FrameDisposition
import com.airscreen.tv.domain.model.VideoStreamConfig

interface MirroringVideoSink {
    fun onCodecConfig(config: VideoStreamConfig)

    fun onVideoFrame(frame: EncodedVideoFrame): FrameDisposition

    fun onFlush()

    fun onStreamEnd()
}
