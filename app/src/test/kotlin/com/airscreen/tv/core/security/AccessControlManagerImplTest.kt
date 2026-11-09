package com.airscreen.tv.core.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.airscreen.tv.core.session.SessionManagerImpl
import com.airscreen.tv.domain.contract.AppSettingsRepository
import com.airscreen.tv.domain.model.AccessDecision
import com.airscreen.tv.domain.model.AppSettings
import com.airscreen.tv.domain.model.FpsCap
import com.airscreen.tv.domain.model.MaxResolution
import com.airscreen.tv.domain.model.PinChallengeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessControlManagerImplTest {

    @Test
    fun pinChallengeLifecycleApproveBlockAndUnblock() = runBlocking {
        val sessions = SessionManagerImpl()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val access = AccessControlManagerImpl(
                sessions,
                FakeSettings(),
                FakePreferences(),
                scope
            )
            val first = sessions.requestPinChallenge("iPhone", "192.168.1.20")
            val visible = withTimeout(2_000L) {
                access.pinChallengeState.first { it is PinChallengeState.Visible }
            } as PinChallengeState.Visible
            assertEquals("iPhone", visible.clientName)
            assertEquals("192.168.1.20", visible.ipAddress)
            assertEquals(first.code.value, visible.pinCode)

            access.approvePinChallenge(alwaysTrust = true)
            assertTrue(sessions.pinChallenges.value.isEmpty())
            assertEquals(PinChallengeState.Idle, access.pinChallengeState.value)
            assertEquals(
                AccessDecision.ALLOW,
                access.checkAccess("192.168.1.20", "192.168.1.20", "iPhone")
            )

            sessions.requestPinChallenge("iPad", "192.168.1.21")
            withTimeout(2_000L) {
                access.pinChallengeState.first { it is PinChallengeState.Visible }
            }
            access.rejectPinChallenge(blockDevice = true)
            assertEquals(1, access.blockedDevices.value.size)
            assertEquals("192.168.1.21", access.blockedDevices.value.first().ipAddress)
            assertEquals(
                AccessDecision.BLOCK,
                access.checkAccess("192.168.1.21", "192.168.1.21", "iPad")
            )

            access.unblockDevice("192.168.1.21")
            assertTrue(access.blockedDevices.value.isEmpty())
            assertEquals(
                AccessDecision.REQUIRE_PIN,
                access.checkAccess("192.168.1.21", "192.168.1.21", "iPad")
            )
        } finally {
            scope.cancel()
        }
    }
}

private class FakePreferences : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences
    ): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

private class FakeSettings : AppSettingsRepository {
    private val state = MutableStateFlow(AppSettings(pinSecurityEnabled = true))
    override val settings: Flow<AppSettings> = state
    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }
    override suspend fun setDeviceName(deviceName: String) = Unit
    override suspend fun setMaxResolution(value: MaxResolution) = Unit
    override suspend fun setFpsCap(value: FpsCap) = Unit
    override suspend fun setHardwareDecodingEnabled(enabled: Boolean) = Unit
    override suspend fun setPinSecurityEnabled(enabled: Boolean) = Unit
    override suspend fun setAutoStartOnBoot(enabled: Boolean) = Unit
    override suspend fun resetToDefaults() = Unit
}