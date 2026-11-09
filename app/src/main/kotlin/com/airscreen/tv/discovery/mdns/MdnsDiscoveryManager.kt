package com.airscreen.tv.discovery.mdns

import android.util.Log
import com.airscreen.tv.discovery.mdns.NsdAdvertisement
import com.airscreen.tv.domain.model.NetworkState
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class MdnsRegistration(
    val airplay: Boolean,
    val raop: Boolean
) {
    val anyReady: Boolean
        get() = airplay || raop
}

@Singleton
class MdnsDiscoveryManager @Inject constructor(
    private val nsdAdvertiser: NsdAdvertiser
) {
    private val mutex = Mutex()
    private var jmDns: JmDNS? = null

    suspend fun start(
        network: NetworkState.Connected,
        deviceName: String,
        pinRequired: Boolean,
        deviceUuid: String,
        hardwareId: String,
        publicKeyHex: String,
        advertiseAirPlay: Boolean = true
    ): MdnsRegistration = withContext(Dispatchers.IO) {
        mutex.withLock {
            stopLocked()
            val state = Advertisement(
                deviceName = deviceName.ifBlank { DEFAULT_DEVICE_NAME },
                pinRequired = pinRequired,
                deviceUuid = deviceUuid,
                hardwareId = hardwareId,
                publicKeyHex = publicKeyHex
            )
            val nsd = if (advertiseAirPlay) {
                nsdAdvertiser.start(
                    deviceName = state.deviceName,
                    pinRequired = state.pinRequired,
                    deviceUuid = state.deviceUuid,
                    hardwareId = state.hardwareId,
                    publicKeyHex = state.publicKeyHex
                )
            } else {
                NsdAdvertisement(false, false)
            }
            var airplay = nsd.airplay
            var raop = nsd.raop
            try {
                val created = JmDNS.create(
                    network.inetAddress,
                    hostNameFor(hardwareId)
                )
                jmDns = created
                val jm = registerLocked(
                    created,
                    state,
                    includeAirplay = advertiseAirPlay && !nsd.airplay,
                    includeRaop = advertiseAirPlay && !nsd.raop
                )
                airplay = airplay || jm.airplay
                raop = raop || jm.raop
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to start JmDNS advertisement", error)
            }
            MdnsRegistration(airplay, raop)
        }
    }

    suspend fun updateAdvertisement(
        deviceName: String,
        pinRequired: Boolean,
        deviceUuid: String,
        hardwareId: String,
        publicKeyHex: String,
        advertiseAirPlay: Boolean = true
    ): MdnsRegistration = withContext(Dispatchers.IO) {
        mutex.withLock {
            val existing = jmDns
                ?: return@withLock MdnsRegistration(false, false)
            val state = Advertisement(
                deviceName = deviceName.ifBlank { DEFAULT_DEVICE_NAME },
                pinRequired = pinRequired,
                deviceUuid = deviceUuid,
                hardwareId = hardwareId,
                publicKeyHex = publicKeyHex
            )
            val nsd = if (advertiseAirPlay) {
                nsdAdvertiser.start(
                    deviceName = state.deviceName,
                    pinRequired = state.pinRequired,
                    deviceUuid = state.deviceUuid,
                    hardwareId = state.hardwareId,
                    publicKeyHex = state.publicKeyHex
                )
            } else {
                NsdAdvertisement(false, false)
            }
            unregisterAllServices(existing)
            val jm = registerLocked(
                existing,
                state,
                includeAirplay = advertiseAirPlay && !nsd.airplay,
                includeRaop = advertiseAirPlay && !nsd.raop
            )
            MdnsRegistration(
                airplay = nsd.airplay || jm.airplay,
                raop = nsd.raop || jm.raop
            )
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        mutex.withLock {
            stopLocked()
        }
    }

    private fun registerLocked(
        jmDns: JmDNS,
        state: Advertisement,
        includeAirplay: Boolean,
        includeRaop: Boolean
    ): MdnsRegistration {
        var airplayReady = false
        var raopReady = false

        if (includeAirplay) {
            try {
                jmDns.registerService(airPlayServiceInfo(state))
                airplayReady = true
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to register AirPlay mDNS service", error)
            }
        }

        if (includeRaop) {
            try {
                jmDns.registerService(raopServiceInfo(state))
                raopReady = true
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to register RAOP mDNS service", error)
            }
        }

        return MdnsRegistration(
            airplay = airplayReady,
            raop = raopReady
        )
    }

    private fun airPlayServiceInfo(state: Advertisement): ServiceInfo {
        val txt = MdnsTxtRecords.airplay(
            deviceUuid = state.deviceUuid,
            hardwareId = state.hardwareId,
            publicKeyHex = state.publicKeyHex
        )
        return ServiceInfo.create(
            AIRPLAY_TYPE,
            state.deviceName,
            AIRPLAY_PORT,
            0,
            0,
            txt
        )
    }

    private fun raopServiceInfo(state: Advertisement): ServiceInfo {
        val txt = MdnsTxtRecords.raop(
            pinRequired = state.pinRequired,
            hardwareId = state.hardwareId,
            publicKeyHex = state.publicKeyHex
        )
        return ServiceInfo.create(
            RAOP_TYPE,
            compactHardwareId(state.hardwareId) + "@" + state.deviceName,
            RAOP_PORT,
            0,
            0,
            txt
        )
    }

    private fun unregisterAllServices(jmDns: JmDNS) {
        try {
            jmDns.unregisterAllServices()
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to unregister mDNS services", error)
        }
    }

    private fun stopLocked() {
        nsdAdvertiser.stop()
        val existing = jmDns
        if (existing != null) {
            unregisterAllServices(existing)
            try {
                existing.close()
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to close JmDNS", error)
            }
        }
        jmDns = null
    }

    private fun hostNameFor(hardwareId: String): String {
        val suffix = compactHardwareId(hardwareId).takeLast(6)
        return "AirScreen-" + suffix.ifBlank { "TV" }
    }

    private fun compactHardwareId(hardwareId: String): String =
        hardwareId.replace(":", "").uppercase(Locale.US)

    private data class Advertisement(
        val deviceName: String,
        val pinRequired: Boolean,
        val deviceUuid: String,
        val hardwareId: String,
        val publicKeyHex: String
    )

    private companion object {
        const val TAG = "AirScreenMdns"
        const val DEFAULT_DEVICE_NAME = "AirScreen TV"
        const val AIRPLAY_TYPE = "_airplay._tcp.local."
        const val RAOP_TYPE = "_raop._tcp.local."
        const val AIRPLAY_PORT = 7000
        const val RAOP_PORT = 5000
    }
}