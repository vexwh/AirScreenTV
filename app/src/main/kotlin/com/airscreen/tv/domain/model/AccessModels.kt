package com.airscreen.tv.domain.model

import kotlinx.coroutines.CompletableDeferred

data class DeviceIdentity(
    val macOrIp: String,
    val displayName: String,
    val addedTimestamp: Long
) {
    val id: String get() = macOrIp
    val name: String get() = displayName
    val ipAddress: String get() = macOrIp
}

typealias BlockedDevice = DeviceIdentity

enum class AccessDecision {
    ALLOW,
    BLOCK,
    REQUIRE_PIN
}

sealed interface PinChallengeState {
    data object Idle : PinChallengeState

    data class Visible(
        val challengeId: String,
        val clientName: String,
        val ipAddress: String,
        val pinCode: String,
        val secondsRemaining: Int,
        val expiresAtEpochMs: Long,
        val deferredResult: CompletableDeferred<Boolean>
    ) : PinChallengeState
}
