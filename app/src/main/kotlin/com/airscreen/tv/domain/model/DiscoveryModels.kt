package com.airscreen.tv.domain.model

import org.bouncycastle.crypto.AsymmetricCipherKeyPair

data class DiscoveryConfig(
    val deviceName: String,
    val airPlayPort: Int = 7000,
    val raopPort: Int = 5000,
    val pinRequired: Boolean,
    val maxWidth: Int = 3840,
    val maxHeight: Int = 2160,
    val maxFps: Int = 60,
    val advertiseAirPlay: Boolean = true
)

data class DiscoveryFailure(
    val component: String,
    val message: String,
    val causeType: String? = null
)

data class DiscoveryStatus(
    val isServiceRunning: Boolean = false,
    val deviceName: String = "",
    val ipAddress: String? = null,
    val networkName: String? = null,
    val airplayReady: Boolean = false,
    val ed25519KeyPair: AsymmetricCipherKeyPair? = null,
    val failures: List<DiscoveryFailure> = emptyList()
)
