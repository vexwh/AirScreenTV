package com.airscreen.tv.domain.model

enum class VideoCodec {
    H264,
    HEVC
}

enum class AudioCodec {
    AAC_ELD,
    AAC_LC,
    ALAC
}

data class VideoStreamConfig(
    val sessionId: SessionId,
    val surfaceSlot: SurfaceSlotId,
    val codec: VideoCodec,
    val width: Int,
    val height: Int,
    val sps: ByteArray,
    val pps: ByteArray,
    val vps: ByteArray? = null,
    val fps: Int = 60
)

data class EncodedVideoFrame(
    val sessionId: SessionId,
    val codec: VideoCodec,
    val ptsUs: Long,
    val bytes: ByteArray,
    val isKeyFrame: Boolean
)

data class AudioStreamConfig(
    val sessionId: SessionId,
    val codec: AudioCodec,
    val sampleRateHz: Int,
    val channelCount: Int,
    val samplesPerFrame: Int,
    val codecSpecificData: ByteArray? = null
)

data class EncodedAudioFrame(
    val sessionId: SessionId,
    val codec: AudioCodec,
    val ptsUs: Long,
    val bytes: ByteArray
)

enum class FrameDisposition {
    ACCEPTED,
    DROPPED_BACKPRESSURE,
    REJECTED
}

data class VideoDecoderMetrics(
    val fps: Double,
    val kbps: Double,
    val width: Int,
    val height: Int,
    val codecName: String
)
