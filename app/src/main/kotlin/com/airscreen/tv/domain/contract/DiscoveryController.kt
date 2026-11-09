package com.airscreen.tv.domain.contract

import com.airscreen.tv.domain.model.DiscoveryConfig
import com.airscreen.tv.domain.model.DiscoveryStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the local AirPlay discovery lifecycle: mDNS registration, multicast/wake
 * locks, and re-binding when the active network changes.
 *
 * Implementations must be safe to call from any coroutine and must be idempotent.
 */
interface DiscoveryController {
    val status: StateFlow<DiscoveryStatus>

    suspend fun start(config: DiscoveryConfig)

    suspend fun stop()

    suspend fun updateDeviceName(deviceName: String)

    suspend fun refresh()
}