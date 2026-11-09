package com.airscreen.tv.core.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.airscreen.tv.core.identity.DeviceIdentityRepository
import com.airscreen.tv.di.ApplicationScope
import com.airscreen.tv.domain.model.NetworkState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class NetworkEnvironmentManager @Inject constructor(
    @ApplicationContext context: Context,
    private val identityRepository: DeviceIdentityRepository,
    @ApplicationScope private val applicationScope: CoroutineScope
) {
    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val powerManager =
        context.getSystemService(PowerManager::class.java)

    private val _state = MutableStateFlow<NetworkState>(NetworkState.Disconnected)
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    private val refreshRequests = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val refreshMutex = Mutex()
    private val lockMutex = Mutex()

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var locksHeld = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            requestRefresh()
        }

        override fun onLost(network: Network) {
            requestRefresh()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            requestRefresh()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            requestRefresh()
        }

        override fun onUnavailable() {
            requestRefresh()
        }
    }

    init {
        registerNetworkCallback()
        applicationScope.launch {
            refreshRequests.collect {
                refreshNow()
            }
        }
        refreshRequests.tryEmit(Unit)
    }

    @SuppressLint("WakelockTimeout", "Wakelock")
    suspend fun acquireDiscoveryLocks() {
        lockMutex.withLock {
            if (locksHeld) return@withLock
            var acquiredAny = false

            val newWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            )
            newWakeLock.setReferenceCounted(false)
            try {
                newWakeLock.acquire()
                wakeLock = newWakeLock
                acquiredAny = true
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to acquire partial wake lock", error)
                runCatching { newWakeLock.release() }
            }

            val newMulticastLock = try {
                wifiManager?.createMulticastLock(MULTICAST_LOCK_TAG)
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to create multicast lock", error)
                null
            }
            if (newMulticastLock != null) {
                newMulticastLock.setReferenceCounted(false)
                try {
                    newMulticastLock.acquire()
                    multicastLock = newMulticastLock
                    acquiredAny = true
                } catch (error: Throwable) {
                    Log.w(TAG, "Unable to acquire multicast lock", error)
                    runCatching { newMulticastLock.release() }
                }
            }

            locksHeld = acquiredAny
        }
    }

    suspend fun releaseDiscoveryLocks() {
        lockMutex.withLock {
            runCatching { wakeLock?.release() }
                .onFailure { Log.w(TAG, "Unable to release wake lock", it) }
            runCatching { multicastLock?.release() }
                .onFailure { Log.w(TAG, "Unable to release multicast lock", it) }
            wakeLock = null
            multicastLock = null
            locksHeld = false
        }
    }

    private fun requestRefresh() {
        refreshRequests.tryEmit(Unit)
    }

    private fun registerNetworkCallback() {
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to register network callback", error)
        }
    }

    private suspend fun refreshNow() {
        refreshMutex.withLock {
            val resolved = try {
                withContext(Dispatchers.IO) { resolveNetwork() }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Log.w(TAG, "Network resolution failed", error)
                return@withLock
            }
            val current = _state.value
            if (sameEndpoint(current, resolved)) return@withLock
            _state.value = resolved
            Log.i(TAG, "Active network endpoint: " + resolved)
        }
    }

    private suspend fun resolveNetwork(): NetworkState {
        val activeNetwork = connectivityManager.activeNetwork
        val linkProperties = activeNetwork?.let { connectivityManager.getLinkProperties(it) }
        val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

        val candidates = collectCandidates(linkProperties)
        if (candidates.isEmpty()) return NetworkState.Disconnected

        val selected = candidates.maxByOrNull { it.score } ?: return NetworkState.Disconnected
        val hardwareId = identityRepository.ensureHardwareId(
            formatMacAddress(selected.networkInterface.hardwareAddress)
        )
        val ssid = readSsid(capabilities)

        return NetworkState.Connected(
            ipAddress = selected.address.hostAddress ?: selected.address.toString(),
            macAddress = hardwareId,
            interfaceName = selected.networkInterface.name,
            ssid = ssid,
            inetAddress = selected.address,
            networkInterface = selected.networkInterface,
            prefixLength = selected.prefixLength
        )
    }

    private fun collectCandidates(linkProperties: LinkProperties?): List<InterfaceCandidate> {
        val result = mutableListOf<InterfaceCandidate>()
        val interfaces = try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to enumerate network interfaces", error)
            return result
        }

        for (networkInterface in interfaces) {
            val interfaceName = networkInterface.name
            val baseScore = interfaceScore(interfaceName)
            if (baseScore < 0) continue

            val isUp = runCatching { networkInterface.isUp }.getOrDefault(false)
            if (!isUp) continue
            if (runCatching { networkInterface.isLoopback }.getOrDefault(true)) continue

            val supportsMulticast = runCatching {
                networkInterface.supportsMulticast()
            }.getOrDefault(false)
            val addresses = runCatching {
                networkInterface.interfaceAddresses
            }.getOrDefault(emptyList<InterfaceAddress>())

            for (interfaceAddress in addresses) {
                val address = interfaceAddress.address
                if (address !is Inet4Address) continue
                if (address.isLoopbackAddress) continue
                if (!address.isSiteLocalAddress) continue

                val score = baseScore +
                    if (supportsMulticast) MULTICAST_SCORE_BONUS else 0 +
                    if (linkProperties?.interfaceName == interfaceName) ACTIVE_INTERFACE_BONUS else 0

                result += InterfaceCandidate(
                    networkInterface = networkInterface,
                    address = address,
                    prefixLength = interfaceAddress.networkPrefixLength.toInt(),
                    score = score
                )
                break
            }
        }
        return result
    }

    @Suppress("DEPRECATION")
    private fun readSsid(capabilities: NetworkCapabilities?): String? {
        return try {
            val wifiTransport = capabilities?.hasTransport(
                NetworkCapabilities.TRANSPORT_WIFI
            ) == true
            if (!wifiTransport) {
                null
            } else {
                val fromCapabilities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    (capabilities?.transportInfo as? WifiInfo)?.ssid
                } else {
                    null
                }
                sanitizeSsid(fromCapabilities) ?: sanitizeSsid(wifiManager?.connectionInfo?.ssid)
            }
        } catch (error: Throwable) {
            Log.d(TAG, "SSID unavailable", error)
            null
        }
    }

    private fun sanitizeSsid(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim().removeSurrounding("\"")
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("<")) return null
        if (trimmed.equals("unknown", ignoreCase = true)) return null
        if (trimmed.startsWith("0x", ignoreCase = true)) return null
        return trimmed
    }

    private fun formatMacAddress(bytes: ByteArray?): String? {
        if (bytes == null || bytes.size != HARDWARE_ID_BYTES) return null
        if (bytes.all { it == 0.toByte() }) return null
        if (bytes.all { it == 0xFF.toByte() }) return null
        if (bytes[0] == 0x02.toByte() && bytes.drop(1).all { it == 0.toByte() }) return null
        return bytes.joinToString(":") { byte ->
            String.format(Locale.US, "%02X", byte.toInt() and 0xFF)
        }
    }

    private fun interfaceScore(name: String): Int {
        val lower = name.lowercase(Locale.US)
        return when {
            lower.startsWith("wlan") -> 100
            lower.startsWith("eth") -> 90
            lower.startsWith("ap") -> 80
            lower.startsWith("rndis") -> 70
            lower.startsWith("usb") -> 70
            lower.startsWith("br") -> 60
            lower.startsWith("tun") -> -1
            lower.startsWith("ppp") -> -1
            lower.startsWith("rmnet") -> -1
            lower.startsWith("dummy") -> -1
            lower.startsWith("p2p") -> -1
            lower.startsWith("sit") -> -1
            lower.startsWith("ip6") -> -1
            lower.startsWith("lo") -> -1
            else -> 10
        }
    }

    private fun sameEndpoint(current: NetworkState, resolved: NetworkState): Boolean {
        return when {
            current is NetworkState.Disconnected && resolved is NetworkState.Disconnected -> true
            current is NetworkState.Connected && resolved is NetworkState.Connected ->
                current.key == resolved.key
            else -> false
        }
    }

    private data class InterfaceCandidate(
        val networkInterface: NetworkInterface,
        val address: Inet4Address,
        val prefixLength: Int,
        val score: Int
    )

    private companion object {
        const val TAG = "AirScreenNetwork"
        const val WAKE_LOCK_TAG = "AirScreen:Discovery"
        const val MULTICAST_LOCK_TAG = "AirScreenDiscovery"
        const val HARDWARE_ID_BYTES = 6
        const val MULTICAST_SCORE_BONUS = 5
        const val ACTIVE_INTERFACE_BONUS = 20
    }
}