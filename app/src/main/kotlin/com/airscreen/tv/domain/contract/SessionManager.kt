package com.airscreen.tv.domain.contract

import android.view.Surface
import com.airscreen.tv.domain.model.ClientSession
import com.airscreen.tv.domain.model.MultiScreenState
import com.airscreen.tv.domain.model.PinChallenge
import com.airscreen.tv.domain.model.PinCode
import com.airscreen.tv.domain.model.PinVerification
import com.airscreen.tv.domain.model.SessionAdmission
import com.airscreen.tv.domain.model.SessionId
import com.airscreen.tv.domain.model.SessionMetrics
import com.airscreen.tv.domain.model.SessionRequest
import com.airscreen.tv.media.decoder.HardwareVideoDecoder
import com.airscreen.tv.media.decoder.RaopAudioProcessor
import kotlinx.coroutines.flow.StateFlow

interface SessionManager {
    val sessions: StateFlow<List<ClientSession>>
    val multiScreenState: StateFlow<MultiScreenState>
    val pinChallenges: StateFlow<List<PinChallenge>>

    fun requestPinChallenge(
        clientName: String = "AirPlay sender",
        remoteAddress: String = "unknown"
    ): PinChallenge

    fun verifyPin(challengeId: String, pin: PinCode): PinVerification
    fun completePinChallenge(challengeId: String)

    fun allocateSession(request: SessionRequest): SessionAdmission
    fun releaseSession(sessionId: SessionId)
    fun updateMetrics(sessionId: SessionId, metrics: SessionMetrics)
    fun attachVideoSink(sessionId: SessionId, decoder: HardwareVideoDecoder)
    fun attachAudioSink(sessionId: SessionId, processor: RaopAudioProcessor)

    fun bindSurface(sessionId: String, surface: Surface)
    fun unbindSurface(sessionId: String)

    fun rotateSession(sessionId: String)
    fun cycleAspectRatio(sessionId: String)
    fun setAudioMuted(sessionId: String, muted: Boolean)
    fun kickSession(sessionId: String)
    fun setActiveAudioSession(sessionId: String?)
    fun togglePip()
    fun closeAll()
}
