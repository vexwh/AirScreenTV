package com.airscreen.tv.core.session

import android.view.Surface
import com.airscreen.tv.domain.contract.SessionManager
import com.airscreen.tv.domain.model.AspectRatioMode
import com.airscreen.tv.domain.model.ClientSession
import com.airscreen.tv.domain.model.LayoutMode
import com.airscreen.tv.domain.model.MultiScreenState
import com.airscreen.tv.domain.model.PinChallenge
import com.airscreen.tv.domain.model.PinCode
import com.airscreen.tv.domain.model.PinVerification
import com.airscreen.tv.domain.model.SenderProtocol
import com.airscreen.tv.domain.model.SessionAdmission
import com.airscreen.tv.domain.model.SessionId
import com.airscreen.tv.domain.model.SessionMetrics
import com.airscreen.tv.domain.model.SessionRequest
import com.airscreen.tv.domain.model.SessionState
import com.airscreen.tv.domain.model.SurfaceSlotId
import com.airscreen.tv.media.decoder.HardwareVideoDecoder
import com.airscreen.tv.media.decoder.RaopAudioProcessor
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class SessionManagerImpl @Inject constructor() : SessionManager {
    private val lock = Any()
    private val random = SecureRandom()
    private val activeSessions = LinkedHashMap<SessionId, ClientSession>()
    private val challenges = LinkedHashMap<String, PinChallenge>()
    private val pendingSurfaces = HashMap<String, Surface>()

    private val _sessions = MutableStateFlow<List<ClientSession>>(emptyList())
    override val sessions: StateFlow<List<ClientSession>> = _sessions.asStateFlow()

    private val _multiScreenState = MutableStateFlow(MultiScreenState())
    override val multiScreenState: StateFlow<MultiScreenState> =
        _multiScreenState.asStateFlow()

    private val _pinChallenges = MutableStateFlow<List<PinChallenge>>(emptyList())
    override val pinChallenges: StateFlow<List<PinChallenge>> =
        _pinChallenges.asStateFlow()

    private var pipEnabled = false

    fun requestPinChallenge(): PinChallenge = requestPinChallenge("AirPlay sender", "unknown")

    override fun requestPinChallenge(
        clientName: String,
        remoteAddress: String
    ): PinChallenge = synchronized(lock) {
        cleanupExpiredLocked()
        val code = PinCode(String.format(Locale.US, "%04d", 1000 + random.nextInt(9000)))
        val challenge = PinChallenge(
            id = UUID.randomUUID().toString(),
            code = code,
            expiresAtEpochMs = System.currentTimeMillis() + PIN_TTL_MS,
            attemptsRemaining = MAX_PIN_ATTEMPTS,
            clientName = clientName.ifBlank { "AirPlay sender" },
            remoteAddress = remoteAddress.ifBlank { "unknown" }
        )
        challenges[challenge.id] = challenge
        publishChallengesLocked()
        challenge
    }

    override fun verifyPin(challengeId: String, pin: PinCode): PinVerification = synchronized(lock) {
        cleanupExpiredLocked()
        val challenge = challenges[challengeId]
            ?: return@synchronized PinVerification.UnknownChallenge
        if (System.currentTimeMillis() > challenge.expiresAtEpochMs) {
            challenges.remove(challengeId)
            publishChallengesLocked()
            return@synchronized PinVerification.Expired
        }
        if (!constantTimeEquals(challenge.code.value, pin.value)) {
            val remaining = challenge.attemptsRemaining - 1
            if (remaining <= 0) {
                challenges.remove(challengeId)
                publishChallengesLocked()
                return@synchronized PinVerification.Locked
            }
            challenges[challengeId] = challenge.copy(attemptsRemaining = remaining)
            publishChallengesLocked()
            return@synchronized PinVerification.Incorrect(remaining)
        }
        PinVerification.Accepted(challenge)
    }

    override fun completePinChallenge(challengeId: String) {
        synchronized(lock) {
            challenges.remove(challengeId)
            publishChallengesLocked()
        }
    }

    override fun allocateSession(request: SessionRequest): SessionAdmission = synchronized(lock) {
        if (activeSessions.size >= MAX_SESSIONS) {
            return@synchronized SessionAdmission.Rejected(
                "Maximum of four concurrent sessions reached"
            )
        }
        val slot = if (request.requiresVideo) firstFreeSlotLocked() else null
        if (request.requiresVideo && slot == null) {
            return@synchronized SessionAdmission.Rejected("No free video surface slot")
        }
        val session = ClientSession(
            id = SessionId(UUID.randomUUID().toString()),
            protocol = request.protocol,
            senderName = request.senderName.ifBlank {
                when (request.protocol) {
                    SenderProtocol.AIRPLAY -> "AirPlay sender"
                    SenderProtocol.RAOP -> "RAOP sender"
                    SenderProtocol.AIRPLAY_URL -> "AirPlay URL sender"
                }
            },
            state = SessionState.ACTIVE,
            surfaceSlot = slot,
            connectedAtEpochMs = System.currentTimeMillis(),
            clientIp = request.remoteAddress.ifBlank { "unknown" },
            clientMac = request.clientMac.ifBlank { "unknown" }
        )
        activeSessions[session.id] = session
        ensureActiveAudioLocked()
        publishSessionsLocked()
        SessionAdmission.Accepted(session)
    }

    override fun releaseSession(sessionId: SessionId) = synchronized(lock) {
        releaseLocked(sessionId, closeMedia = false)
    }

    override fun updateMetrics(sessionId: SessionId, metrics: SessionMetrics) {
        synchronized(lock) {
            val existing = activeSessions[sessionId] ?: return
            activeSessions[sessionId] = existing.copy(metrics = metrics)
            publishSessionsLocked()
        }
    }

    override fun attachVideoSink(sessionId: SessionId, decoder: HardwareVideoDecoder) {
        synchronized(lock) {
            val existing = activeSessions[sessionId] ?: return
            activeSessions[sessionId] = existing.copy(videoDecoder = decoder)
            // The stage SurfaceView can be created before the decoder attaches.
            // Re-apply the surface that was registered while no decoder existed.
            pendingSurfaces[sessionId.value]?.let { decoder.setOutputSurface(it) }
            publishSessionsLocked()
        }
    }

    override fun attachAudioSink(sessionId: SessionId, processor: RaopAudioProcessor) {
        synchronized(lock) {
            val existing = activeSessions[sessionId] ?: return
            activeSessions[sessionId] = existing.copy(audioProcessor = processor)
            ensureActiveAudioLocked()
            publishSessionsLocked()
        }
    }

    override fun bindSurface(sessionId: String, surface: Surface) {
        synchronized(lock) {
            pendingSurfaces[sessionId] = surface
            decoderFor(sessionId)?.setOutputSurface(surface)
        }
    }

    override fun unbindSurface(sessionId: String) {
        synchronized(lock) {
            pendingSurfaces.remove(sessionId)
            decoderFor(sessionId)?.setOutputSurface(null)
        }
    }

    override fun rotateSession(sessionId: String) {
        updateSession(sessionId) { it.copy(rotationDegrees = (it.rotationDegrees + 90) % 360) }
    }

    override fun cycleAspectRatio(sessionId: String) {
        updateSession(sessionId) {
            val next = when (it.aspectRatioMode) {
                AspectRatioMode.FIT -> AspectRatioMode.FILL
                AspectRatioMode.FILL -> AspectRatioMode.STRETCH_16_9
                AspectRatioMode.STRETCH_16_9 -> AspectRatioMode.FIT
            }
            it.copy(aspectRatioMode = next)
        }
    }

    override fun setAudioMuted(sessionId: String, muted: Boolean) {
        updateSession(sessionId) { session ->
            session.audioProcessor?.setMuted(muted)
            session.copy(audioMuted = muted)
        }
    }

    override fun kickSession(sessionId: String) {
        synchronized(lock) {
            releaseLocked(SessionId(sessionId), closeMedia = true)
        }
    }

    override fun setActiveAudioSession(sessionId: String?) {
        synchronized(lock) {
            if (sessionId != null && activeSessions[SessionId(sessionId)] == null) return
            activeSessions.replaceAll { id, session ->
                val muted = sessionId != null && id.value != sessionId
                session.audioProcessor?.setMuted(muted)
                session.copy(audioMuted = muted)
            }
            publishSessionsLocked(audioSessionId = sessionId)
        }
    }

    override fun togglePip() {
        synchronized(lock) {
            pipEnabled = !pipEnabled
            publishSessionsLocked()
        }
    }

    override fun closeAll() {
        synchronized(lock) {
            activeSessions.values.toList().forEach { session ->
                runCatching { session.videoDecoder?.onStreamEnd() }
                runCatching { session.audioProcessor?.close() }
            }
            activeSessions.clear()
            challenges.clear()
            pipEnabled = false
            publishSessionsLocked()
            publishChallengesLocked()
        }
    }

    private fun decoderFor(sessionId: String): HardwareVideoDecoder? = synchronized(lock) {
        activeSessions[SessionId(sessionId)]?.videoDecoder
    }

    private fun updateSession(sessionId: String, transform: (ClientSession) -> ClientSession) {
        synchronized(lock) {
            val existing = activeSessions[SessionId(sessionId)] ?: return
            activeSessions[existing.id] = transform(existing)
            publishSessionsLocked()
        }
    }

    private fun releaseLocked(sessionId: SessionId, closeMedia: Boolean) {
        pendingSurfaces.remove(sessionId.value)
        val removed = activeSessions.remove(sessionId) ?: return
        if (closeMedia) {
            runCatching { removed.videoDecoder?.onStreamEnd() }
            runCatching { removed.audioProcessor?.close() }
        }
        ensureActiveAudioLocked()
        publishSessionsLocked()
    }

    private fun ensureActiveAudioLocked() {
        val current = _multiScreenState.value.activeAudioSessionId
        if (current != null && activeSessions[SessionId(current)] != null) return
        val next = activeSessions.values.firstOrNull { it.audioProcessor != null }?.id?.value
        publishSessionsLocked(audioSessionId = next)
    }

    private fun firstFreeSlotLocked(): SurfaceSlotId? {
        val used = activeSessions.values.mapNotNull { it.surfaceSlot }.toSet()
        return (0..3).firstOrNull { index -> SurfaceSlotId(index) !in used }
            ?.let { SurfaceSlotId(it) }
    }

    private fun cleanupExpiredLocked() {
        val now = System.currentTimeMillis()
        val expired = challenges.values
            .filter { it.expiresAtEpochMs < now }
            .map { it.id }
        if (expired.isNotEmpty()) {
            expired.forEach { challenges.remove(it) }
            publishChallengesLocked()
        }
    }

    private fun publishSessionsLocked(
        audioSessionId: String? = _multiScreenState.value.activeAudioSessionId
    ) {
        val list = activeSessions.values.toList()
        _sessions.value = list
        val layout = if (pipEnabled) {
            LayoutMode.PIP
        } else {
            when (list.size) {
                0 -> LayoutMode.IDLE
                1 -> LayoutMode.SINGLE_1X1
                2 -> LayoutMode.SPLIT_1X2
                else -> LayoutMode.QUAD_2X2
            }
        }
        val activeAudio = audioSessionId?.takeIf { id ->
            activeSessions[SessionId(id)] != null
        }
        _multiScreenState.value = MultiScreenState(
            sessions = list,
            layoutMode = layout,
            activeAudioSessionId = activeAudio
        )
    }

    private fun publishChallengesLocked() {
        _pinChallenges.value = challenges.values.toList()
    }

    private fun constantTimeEquals(left: String, right: String): Boolean {
        if (left.length != right.length) return false
        var difference = 0
        for (index in left.indices) {
            difference = difference or (left[index].code xor right[index].code)
        }
        return difference == 0
    }

    private companion object {
        const val MAX_SESSIONS = 4
        const val PIN_TTL_MS = 120_000L
        const val MAX_PIN_ATTEMPTS = 5
    }
}