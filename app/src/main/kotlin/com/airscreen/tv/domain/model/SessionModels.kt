package com.airscreen.tv.domain.model

import com.airscreen.tv.media.decoder.HardwareVideoDecoder
import com.airscreen.tv.media.decoder.RaopAudioProcessor

@JvmInline
value class SessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "SessionId must not be blank" }
    }
}

@JvmInline
value class SurfaceSlotId(val index: Int) {
    init {
        require(index in 0..3) { "Surface slot must be in 0..3" }
    }
}

enum class SenderProtocol {
    AIRPLAY,
    RAOP,
    AIRPLAY_URL
}

enum class SessionState {
    CONNECTING,
    AWAITING_PIN,
    ACTIVE,
    CLOSED
}

enum class AspectRatioMode {
    FIT,
    FILL,
    STRETCH_16_9
}

enum class LayoutMode {
    IDLE,
    SINGLE_1X1,
    SPLIT_1X2,
    QUAD_2X2,
    PIP
}

data class SessionRequest(
    val protocol: SenderProtocol,
    val senderName: String,
    val remoteAddress: String,
    val requiresVideo: Boolean,
    val clientMac: String = "unknown"
)

data class SessionMetrics(
    val fps: Double = 0.0,
    val kbps: Double = 0.0,
    val width: Int = 0,
    val height: Int = 0,
    val codec: String = "",
    val audioGain: Float = 0f
)

typealias StreamMetrics = SessionMetrics

data class ClientSession(
    val id: SessionId,
    val protocol: SenderProtocol,
    val senderName: String,
    val state: SessionState,
    val surfaceSlot: SurfaceSlotId?,
    val connectedAtEpochMs: Long,
    val metrics: SessionMetrics = SessionMetrics(),
    val clientIp: String = "unknown",
    val clientMac: String = "unknown",
    val rotationDegrees: Int = 0,
    val aspectRatioMode: AspectRatioMode = AspectRatioMode.FIT,
    val audioMuted: Boolean = false,
    val videoDecoder: HardwareVideoDecoder? = null,
    val audioProcessor: RaopAudioProcessor? = null
) {
    val sessionId: String get() = id.value
    val slotIndex: Int get() = surfaceSlot?.index ?: -1
    val clientName: String get() = senderName
    val streamMetrics: SessionMetrics get() = metrics
}

typealias SessionInfo = ClientSession

data class MultiScreenState(
    val sessions: List<ClientSession> = emptyList(),
    val layoutMode: LayoutMode = LayoutMode.IDLE,
    val activeAudioSessionId: String? = null
)

@JvmInline
value class PinCode(val value: String) {
    init {
        require(value.length == 4 && value.all { it.isDigit() }) {
            "PIN must be exactly four digits"
        }
    }

    override fun toString(): String = "****"
}

data class PinChallenge(
    val id: String,
    val code: PinCode,
    val expiresAtEpochMs: Long,
    val attemptsRemaining: Int,
    val clientName: String = "AirPlay sender",
    val remoteAddress: String = "unknown"
)

sealed interface SessionAdmission {
    data class Accepted(val session: ClientSession) : SessionAdmission
    data class Rejected(val reason: String) : SessionAdmission
}

sealed interface PinVerification {
    data class Accepted(val challenge: PinChallenge) : PinVerification
    data class Incorrect(val attemptsRemaining: Int) : PinVerification
    data object Expired : PinVerification
    data object Locked : PinVerification
    data object UnknownChallenge : PinVerification
}
