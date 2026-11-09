package com.airscreen.tv.media

import com.airscreen.tv.domain.contract.SessionManager
import com.airscreen.tv.domain.contract.StreamRecorderManager
import com.airscreen.tv.domain.model.SessionInfo
import com.airscreen.tv.media.decoder.HardwareVideoDecoder
import com.airscreen.tv.media.decoder.SurfaceSlotRegistry
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaPipelineFactory @Inject constructor(
    private val surfaceSlotRegistry: SurfaceSlotRegistry,
    private val sessionManager: SessionManager,
    private val streamRecorderManager: StreamRecorderManager
) {
    fun createVideoSink(session: SessionInfo, hardwarePreferred: Boolean): HardwareVideoDecoder {
        val slot = session.surfaceSlot
            ?: throw IllegalStateException("Session has no video surface slot")
        return HardwareVideoDecoder(
            sessionId = session.id,
            surfaceSlot = slot,
            surfaceRegistry = surfaceSlotRegistry,
            sessionManager = sessionManager,
            streamRecorderManager = streamRecorderManager,
            hardwarePreferred = hardwarePreferred
        )
    }

    fun closeAll() {
        surfaceSlotRegistry.closeAll()
    }
}