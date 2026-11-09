package com.airscreen.tv.domain.model

import java.net.InetAddress
import java.net.NetworkInterface

sealed interface NetworkState {
    data object Disconnected : NetworkState

    data class Connected(
        val ipAddress: String,
        val macAddress: String,
        val interfaceName: String,
        val ssid: String?,
        val inetAddress: InetAddress,
        val networkInterface: NetworkInterface,
        val prefixLength: Int
    ) : NetworkState {
        val networkName: String
            get() = ssid?.takeIf { it.isNotBlank() } ?: interfaceName

        val key: String
            get() = ipAddress + "|" + interfaceName + "|" + macAddress + "|" + prefixLength
    }
}
