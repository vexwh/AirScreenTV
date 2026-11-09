package com.airscreen.tv.discovery

import android.util.Log
import com.airscreen.tv.core.identity.DeviceIdentity
import com.airscreen.tv.core.identity.DeviceIdentityRepository
import com.airscreen.tv.core.network.NetworkEnvironmentManager
import com.airscreen.tv.di.ApplicationScope
import com.airscreen.tv.discovery.mdns.MdnsDiscoveryManager
import com.airscreen.tv.discovery.mdns.MdnsRegistration
import com.airscreen.tv.domain.contract.AppSettingsRepository
import com.airscreen.tv.domain.contract.DiscoveryController
import com.airscreen.tv.domain.model.AppSettings
import com.airscreen.tv.domain.model.DiscoveryConfig
import com.airscreen.tv.domain.model.DiscoveryFailure
import com.airscreen.tv.domain.model.DiscoveryStatus
import com.airscreen.tv.domain.model.NetworkState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class DiscoveryControllerImpl @Inject constructor(
    private val networkEnvironment: NetworkEnvironmentManager,
    private val mdns: MdnsDiscoveryManager,
    private val settingsRepository: AppSettingsRepository,
    private val identityRepository: DeviceIdentityRepository,
    @ApplicationScope private val applicationScope: CoroutineScope
) : DiscoveryController {

    private val _status = MutableStateFlow(DiscoveryStatus())
    override val status: StateFlow<DiscoveryStatus> = _status.asStateFlow()

    private val lifecycleMutex = Mutex()
    private val stateMutex = Mutex()

    private var runJob: Job? = null
    private var activeConfig: DiscoveryConfig? = null
    private var loadedIdentity: DeviceIdentity? = null

    private var mdnsRegistration = MdnsRegistration(false, false)
    private var managersNetworkKey: String? = null
    private var lastDeviceName: String? = null
    private var lastPinRequired: Boolean? = null
    private var failures: List<DiscoveryFailure> = emptyList()

    override suspend fun start(config: DiscoveryConfig) {
        lifecycleMutex.withLock {
            activeConfig = config
            if (runJob?.isActive == true) {
                _status.update { current -> current.copy(deviceName = config.deviceName) }
                return
            }

            val identity = identityRepository.identity()
            loadedIdentity = identity
            _status.value = DiscoveryStatus(
                isServiceRunning = true,
                deviceName = config.deviceName,
                ed25519KeyPair = identity.keyPair
            )

            runJob = applicationScope.launch {
                try {
                    observe(config, identity)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    Log.e(TAG, "Discovery controller loop failed", error)
                    _status.update { current ->
                        current.copy(
                            isServiceRunning = false,
                            airplayReady = false,
                            failures = listOf(failure("controller", error))
                        )
                    }
                } finally {
                    withContext(NonCancellable) {
                        stateMutex.withLock { stopManagersLocked() }
                    }
                }
            }
        }
    }

    override suspend fun stop() {
        val job = lifecycleMutex.withLock {
            activeConfig = null
            val current = runJob
            runJob = null
            current
        }
        job?.cancelAndJoin()
        _status.update { current ->
            current.copy(
                isServiceRunning = false,
                ipAddress = null,
                networkName = null,
                airplayReady = false
            )
        }
    }

    override suspend fun updateDeviceName(deviceName: String) {
        settingsRepository.setDeviceName(deviceName)
        val updated = settingsRepository.settings.first()
        _status.update { current -> current.copy(deviceName = updated.deviceName) }
    }

    override suspend fun refresh() {
        val config = lifecycleMutex.withLock { activeConfig } ?: return
        val settings = settingsRepository.settings.first()
        val updated = config.copy(
            deviceName = settings.deviceName,
            pinRequired = settings.pinSecurityEnabled,
            maxWidth = settings.maxResolution.width,
            maxHeight = settings.maxResolution.height,
            maxFps = settings.fpsCap.fps
        )
        stop()
        start(updated)
    }

    private suspend fun observe(config: DiscoveryConfig, identity: DeviceIdentity) {
        combine(
            networkEnvironment.state,
            settingsRepository.settings
        ) { network, settings -> network to settings }
            .conflate()
            .collect { pair ->
                stateMutex.withLock {
                    reconcile(config, pair.first, pair.second, identity)
                }
            }
    }

    private suspend fun reconcile(
        config: DiscoveryConfig,
        network: NetworkState,
        settings: AppSettings,
        identity: DeviceIdentity
    ) {
        val deviceName = settings.deviceName
        val pinRequired = settings.pinSecurityEnabled

        if (network is NetworkState.Disconnected) {
            stopManagersLocked()
            publishStatus(
                deviceName = deviceName,
                network = null,
                identity = identity,
                airplayReady = false
            )
            return
        }

        val connected = network as NetworkState.Connected
        val networkChanged = managersNetworkKey != connected.key
        val settingsChanged = lastDeviceName != deviceName ||
            lastPinRequired != pinRequired

        if (networkChanged) {
            stopManagersLocked()
            startManagersLocked(config, connected, deviceName, pinRequired, identity)
        } else if (settingsChanged) {
            val updated = try {
                mdns.updateAdvertisement(
                    deviceName = deviceName,
                    pinRequired = pinRequired,
                    deviceUuid = identity.deviceUuid,
                    hardwareId = connected.macAddress,
                    publicKeyHex = identity.publicKeyHex,
                    advertiseAirPlay = config.advertiseAirPlay
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to refresh mDNS advertisement", error)
                failures = failures.filterNot { it.component == "mDNS" } +
                    failure("mDNS", error)
                mdnsRegistration
            }
            mdnsRegistration = updated
            lastDeviceName = deviceName
            lastPinRequired = pinRequired
        }

        publishStatus(
            deviceName = deviceName,
            network = connected,
            identity = identity,
            airplayReady = mdnsRegistration.airplay
        )
    }

    private suspend fun startManagersLocked(
        config: DiscoveryConfig,
        network: NetworkState.Connected,
        deviceName: String,
        pinRequired: Boolean,
        identity: DeviceIdentity
    ) {
        networkEnvironment.acquireDiscoveryLocks()
        failures = emptyList()

        val mdnsResult = try {
            mdns.start(
                network = network,
                deviceName = deviceName,
                pinRequired = pinRequired,
                deviceUuid = identity.deviceUuid,
                hardwareId = network.macAddress,
                publicKeyHex = identity.publicKeyHex,
                advertiseAirPlay = config.advertiseAirPlay
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to start mDNS", error)
            failures = failures + failure("mDNS", error)
            MdnsRegistration(false, false)
        }

        mdnsRegistration = mdnsResult
        lastDeviceName = deviceName
        lastPinRequired = pinRequired

        if (mdnsResult.anyReady) {
            managersNetworkKey = network.key
        } else {
            managersNetworkKey = null
            networkEnvironment.releaseDiscoveryLocks()
        }

        Log.i(
            TAG,
            "Discovery started on " + network.ipAddress +
                " AirPlay=" + mdnsResult.airplay
        )
    }

    private suspend fun stopManagersLocked() {
        try {
            mdns.stop()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to stop mDNS cleanly", error)
        }

        networkEnvironment.releaseDiscoveryLocks()
        mdnsRegistration = MdnsRegistration(false, false)
        managersNetworkKey = null
        lastDeviceName = null
        lastPinRequired = null
    }

    private fun publishStatus(
        deviceName: String,
        network: NetworkState.Connected?,
        identity: DeviceIdentity,
        airplayReady: Boolean
    ) {
        _status.value = DiscoveryStatus(
            isServiceRunning = true,
            deviceName = deviceName,
            ipAddress = network?.ipAddress,
            networkName = network?.networkName,
            airplayReady = airplayReady,
            ed25519KeyPair = identity.keyPair,
            failures = failures
        )
    }

    private fun failure(component: String, error: Throwable): DiscoveryFailure =
        DiscoveryFailure(
            component = component,
            message = error.message ?: error.javaClass.simpleName,
            causeType = error.javaClass.name
        )

    private companion object {
        const val TAG = "AirScreenDiscovery"
    }
}
