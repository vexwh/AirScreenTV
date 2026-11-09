package com.airscreen.tv.core.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.airscreen.tv.di.ApplicationScope
import com.airscreen.tv.di.SettingsDataStore
import com.airscreen.tv.domain.contract.AccessControlManager
import com.airscreen.tv.domain.contract.AppSettingsRepository
import com.airscreen.tv.domain.contract.SessionManager
import com.airscreen.tv.domain.model.AccessDecision
import com.airscreen.tv.domain.model.AppSettings
import com.airscreen.tv.domain.model.DeviceIdentity
import com.airscreen.tv.domain.model.PinChallenge
import com.airscreen.tv.domain.model.PinChallengeState
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Singleton
class AccessControlManagerImpl @Inject constructor(
    private val sessionManager: SessionManager,
    private val settingsRepository: AppSettingsRepository,
    @SettingsDataStore private val dataStore: DataStore<Preferences>,
    @ApplicationScope private val scope: CoroutineScope
) : AccessControlManager {
    private val _pinChallengeState = MutableStateFlow<PinChallengeState>(PinChallengeState.Idle)
    private val _trustedDevices = MutableStateFlow<List<DeviceIdentity>>(emptyList())
    private val _blockedDevices = MutableStateFlow<List<DeviceIdentity>>(emptyList())

    override val pinChallengeState: StateFlow<PinChallengeState> =
        _pinChallengeState.asStateFlow()
    override val trustedDevices: StateFlow<List<DeviceIdentity>> =
        _trustedDevices.asStateFlow()
    override val blockedDevices: StateFlow<List<DeviceIdentity>> =
        _blockedDevices.asStateFlow()

    private val settings = settingsRepository.settings.stateIn(
        scope,
        SharingStarted.Eagerly,
        AppSettings()
    )
    private var ticker: Job? = null
    private var currentChallengeId: String? = null
    private var currentDeferred: CompletableDeferred<Boolean>? = null

    init {
        scope.launch {
            dataStore.data
                .catch { error ->
                    if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
                    else throw error
                }
                .collect { preferences ->
                    _trustedDevices.value = decode(preferences[KEY_TRUSTED])
                    _blockedDevices.value = decode(preferences[KEY_BLOCKED])
                }
        }
        scope.launch {
            sessionManager.pinChallenges.collect { challenges ->
                val challenge = challenges.firstOrNull()
                if (challenge == null) {
                    clearChallenge(completedValue = null)
                } else if (challenge.id != currentChallengeId) {
                    presentChallenge(challenge)
                }
            }
        }
    }

    override fun checkAccess(
        clientIp: String,
        clientId: String,
        clientName: String
    ): AccessDecision {
        val blocked = _blockedDevices.value.any {
            it.macOrIp.equals(clientIp, true) || it.macOrIp.equals(clientId, true)
        }
        if (blocked) return AccessDecision.BLOCK
        val trusted = _trustedDevices.value.any {
            it.macOrIp.equals(clientIp, true) || it.macOrIp.equals(clientId, true)
        }
        if (trusted) return AccessDecision.ALLOW
        return if (settings.value.pinSecurityEnabled) {
            AccessDecision.REQUIRE_PIN
        } else {
            AccessDecision.ALLOW
        }
    }

    override fun approvePinChallenge(alwaysTrust: Boolean) {
        val state = _pinChallengeState.value as? PinChallengeState.Visible ?: return
        if (alwaysTrust) {
            addTrusted(
                DeviceIdentity(
                    macOrIp = state.ipAddress,
                    displayName = state.clientName,
                    addedTimestamp = System.currentTimeMillis()
                )
            )
        }
        currentDeferred?.complete(true)
        sessionManager.completePinChallenge(state.challengeId)
        clearChallenge(completedValue = true)
    }

    override fun rejectPinChallenge(blockDevice: Boolean) {
        val state = _pinChallengeState.value as? PinChallengeState.Visible ?: return
        if (blockDevice) {
            addBlocked(
                DeviceIdentity(
                    macOrIp = state.ipAddress,
                    displayName = state.clientName,
                    addedTimestamp = System.currentTimeMillis()
                )
            )
        }
        currentDeferred?.complete(false)
        sessionManager.completePinChallenge(state.challengeId)
        clearChallenge(completedValue = false)
    }

    override fun unblockDevice(id: String) {
        val updated = _blockedDevices.value.filterNot { it.macOrIp == id }
        _blockedDevices.value = updated
        persist(updated, _trustedDevices.value)
    }

    private fun addTrusted(device: DeviceIdentity) {
        _trustedDevices.value = (_trustedDevices.value + device)
            .distinctBy { it.macOrIp }
        _blockedDevices.value = _blockedDevices.value.filterNot {
            it.macOrIp == device.macOrIp
        }
        persist(_blockedDevices.value, _trustedDevices.value)
    }

    private fun addBlocked(device: DeviceIdentity) {
        _blockedDevices.value = (_blockedDevices.value + device)
            .distinctBy { it.macOrIp }
        _trustedDevices.value = _trustedDevices.value.filterNot {
            it.macOrIp == device.macOrIp
        }
        persist(_blockedDevices.value, _trustedDevices.value)
    }

    private fun presentChallenge(challenge: PinChallenge) {
        ticker?.cancel()
        currentChallengeId = challenge.id
        val deferred = CompletableDeferred<Boolean>()
        currentDeferred = deferred
        _pinChallengeState.value = visibleState(challenge, deferred)
        ticker = scope.launch {
            while (isActive) {
                delay(1_000L)
                val latest = sessionManager.pinChallenges.value.firstOrNull {
                    it.id == challenge.id
                }
                if (latest == null) {
                    clearChallenge(completedValue = null)
                    break
                }
                val seconds = secondsRemaining(latest)
                if (seconds <= 0) {
                    currentDeferred?.complete(false)
                    sessionManager.completePinChallenge(latest.id)
                    clearChallenge(completedValue = false)
                    break
                }
                _pinChallengeState.value = visibleState(latest, deferred)
            }
        }
    }

    private fun clearChallenge(completedValue: Boolean?) {
        val deferred = currentDeferred
        if (deferred != null && !deferred.isCompleted) {
            deferred.complete(completedValue ?: true)
        }
        ticker?.cancel()
        ticker = null
        currentChallengeId = null
        currentDeferred = null
        _pinChallengeState.value = PinChallengeState.Idle
    }

    private fun visibleState(
        challenge: PinChallenge,
        deferred: CompletableDeferred<Boolean>
    ): PinChallengeState.Visible = PinChallengeState.Visible(
        challengeId = challenge.id,
        clientName = challenge.clientName,
        ipAddress = challenge.remoteAddress,
        pinCode = challenge.code.value,
        secondsRemaining = secondsRemaining(challenge),
        expiresAtEpochMs = challenge.expiresAtEpochMs,
        deferredResult = deferred
    )

    private fun secondsRemaining(challenge: PinChallenge): Int {
        val remaining = (challenge.expiresAtEpochMs - System.currentTimeMillis()) / 1000L
        return remaining.coerceIn(0L, 60L).toInt()
    }

    private fun persist(blocked: List<DeviceIdentity>, trusted: List<DeviceIdentity>) {
        scope.launch {
            dataStore.edit { preferences ->
                preferences[KEY_BLOCKED] = blocked.map(::encode).toSet()
                preferences[KEY_TRUSTED] = trusted.map(::encode).toSet()
            }
        }
    }

    private fun encode(device: DeviceIdentity): String =
        URLEncoder.encode(device.macOrIp, Charsets.UTF_8.name()) + "|" +
            URLEncoder.encode(device.displayName, Charsets.UTF_8.name()) + "|" +
            device.addedTimestamp

    private fun decode(values: Set<String>?): List<DeviceIdentity> =
        values.orEmpty().mapNotNull { value ->
            val parts = value.split('|')
            if (parts.size != 3) return@mapNotNull null
            try {
                DeviceIdentity(
                    macOrIp = URLDecoder.decode(parts[0], Charsets.UTF_8.name()),
                    displayName = URLDecoder.decode(parts[1], Charsets.UTF_8.name()),
                    addedTimestamp = parts[2].toLongOrNull() ?: 0L
                )
            } catch (_: Throwable) {
                null
            }
        }

    private companion object {
        val KEY_TRUSTED = stringSetPreferencesKey("trusted_devices")
        val KEY_BLOCKED = stringSetPreferencesKey("blocked_devices")
    }
}