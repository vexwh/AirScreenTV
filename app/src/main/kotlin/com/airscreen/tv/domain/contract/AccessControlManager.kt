package com.airscreen.tv.domain.contract

import com.airscreen.tv.domain.model.AccessDecision
import com.airscreen.tv.domain.model.DeviceIdentity
import com.airscreen.tv.domain.model.PinChallengeState
import kotlinx.coroutines.flow.StateFlow

interface AccessControlManager {
    val pinChallengeState: StateFlow<PinChallengeState>
    val trustedDevices: StateFlow<List<DeviceIdentity>>
    val blockedDevices: StateFlow<List<DeviceIdentity>>

    fun checkAccess(clientIp: String, clientId: String, clientName: String): AccessDecision
    fun approvePinChallenge(alwaysTrust: Boolean)
    fun rejectPinChallenge(blockDevice: Boolean)
    fun unblockDevice(id: String)
}
