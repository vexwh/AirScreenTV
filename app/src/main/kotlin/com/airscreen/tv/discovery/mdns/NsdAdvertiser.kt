package com.airscreen.tv.discovery.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class NsdAdvertisement(
    val airplay: Boolean,
    val raop: Boolean
)

/**
 * Uses the platform NSD daemon for AirPlay/RAOP discovery. JmDNS has proven
 * unreliable on this Android TV: its registration stops answering queries after
 * the first service TTL. NSD is backed by the system responder and is shared
 * with native Cast services.
 */
@Singleton
class NsdAdvertiser @Inject constructor(
    @ApplicationContext context: Context
) {
    private val nsdManager =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val lock = Any()
    private val activeListeners = mutableMapOf<String, NsdManager.RegistrationListener>()

    suspend fun start(
        deviceName: String,
        pinRequired: Boolean,
        deviceUuid: String,
        hardwareId: String,
        publicKeyHex: String
    ): NsdAdvertisement {
        stop()
        val manager = nsdManager
        if (manager == null) {
            Log.w(TAG, "Android NSD service is unavailable")
            return NsdAdvertisement(false, false)
        }
        val safeName = deviceName.ifBlank { "AirScreen TV" } + SERVICE_SUFFIX
        val airplay = register(
            key = "airplay",
            manager = manager,
            info = airplayInfo(safeName, deviceUuid, hardwareId, publicKeyHex)
        )
        val raop = register(
            key = "raop",
            manager = manager,
            info = raopInfo(safeName, pinRequired, hardwareId, publicKeyHex)
        )
        Log.i(TAG, "NSD advertisement airplay=" + airplay + " raop=" + raop)
        return NsdAdvertisement(airplay, raop)
    }

    fun stop() {
        val manager = nsdManager ?: return
        val listeners = synchronized(lock) {
            val copy = activeListeners.values.toList()
            activeListeners.clear()
            copy
        }
        listeners.forEach { listener ->
            runCatching { manager.unregisterService(listener) }
                .onFailure { Log.w(TAG, "Unable to unregister NSD service", it) }
        }
    }

    private suspend fun register(
        key: String,
        manager: NsdManager,
        info: NsdServiceInfo
    ): Boolean {
        val result = withTimeoutOrNull(REGISTRATION_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                var resumed = false
                val listener = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(registered: NsdServiceInfo) {
                        synchronized(lock) {
                            activeListeners[key] = this
                        }
                        Log.i(TAG, "NSD registered " + registered.serviceName +
                            " " + registered.serviceType)
                        if (!resumed) {
                            resumed = true
                            continuation.resume(true)
                        }
                    }

                    override fun onRegistrationFailed(failed: NsdServiceInfo, errorCode: Int) {
                        synchronized(lock) { activeListeners.remove(key) }
                        Log.w(
                            TAG,
                            "NSD registration failed for " + failed.serviceType +
                                " error=" + errorCode
                        )
                        if (!resumed) {
                            resumed = true
                            continuation.resume(false)
                        }
                    }

                    override fun onServiceUnregistered(unregistered: NsdServiceInfo) {
                        synchronized(lock) { activeListeners.remove(key) }
                    }

                    override fun onUnregistrationFailed(
                        unregistered: NsdServiceInfo,
                        errorCode: Int
                    ) {
                        synchronized(lock) { activeListeners.remove(key) }
                    }
                }
                synchronized(lock) { activeListeners[key] = listener }
                runCatching {
                    manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
                }.onFailure {
                    synchronized(lock) { activeListeners.remove(key) }
                    if (!resumed) {
                        resumed = true
                        continuation.resume(false)
                    }
                }
            }
        } ?: false
        if (!result) {
            synchronized(lock) { activeListeners.remove(key) }
        }
        return result
    }

    private fun airplayInfo(
        deviceName: String,
        deviceUuid: String,
        hardwareId: String,
        publicKeyHex: String
    ): NsdServiceInfo = NsdServiceInfo().apply {
        serviceType = "_airplay._tcp"
        serviceName = deviceName
        port = 7000
        attributes(
            "deviceid" to hardwareId,
            "features" to MdnsTxtRecords.AIRPLAY_FEATURES,
            "flags" to MdnsTxtRecords.AIRPLAY_FLAGS,
            "model" to MdnsTxtRecords.APPLE_TV_MODEL,
            "pi" to deviceUuid,
            "pk" to publicKeyHex,
            "srcvers" to MdnsTxtRecords.AIRPLAY_VERSION,
            "vv" to "2"
        )
    }

    private fun raopInfo(
        deviceName: String,
        pinRequired: Boolean,
        hardwareId: String,
        publicKeyHex: String
    ): NsdServiceInfo = NsdServiceInfo().apply {
        serviceType = "_raop._tcp"
        serviceName = compactHardwareId(hardwareId) + "@" + deviceName
        port = 5000
        attributes(
            "ch" to "2",
            "cn" to "0,1,2,3",
            "da" to "true",
            "et" to "0,3,5",
            "ft" to MdnsTxtRecords.AIRPLAY_FEATURES,
            "md" to "0,1,2",
            "pk" to publicKeyHex,
            "pw" to if (pinRequired) "true" else "false",
            "sf" to MdnsTxtRecords.AIRPLAY_FLAGS,
            "sm" to "false",
            "sr" to "44100",
            "ss" to "16",
            "tp" to "UDP",
            "vn" to "65537",
            "vs" to MdnsTxtRecords.AIRPLAY_VERSION,
            "am" to MdnsTxtRecords.APPLE_TV_MODEL
        )
    }

    private fun NsdServiceInfo.attributes(vararg entries: Pair<String, String>) {
        entries.forEach { (key, value) ->
            runCatching { setAttribute(key, value) }
                .onFailure { Log.w(TAG, "Unable to set NSD attribute " + key, it) }
        }
    }

    private fun compactHardwareId(hardwareId: String): String =
        hardwareId.replace(":", "").uppercase(Locale.US)

    private companion object {
        const val TAG = "AirScreenNsd"
        const val SERVICE_SUFFIX = " Mirror"
        const val REGISTRATION_TIMEOUT_MS = 6_000L
    }
}