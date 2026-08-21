package com.hpremote.clone.transfer

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    // Reads the OS interface list directly instead of WifiManager so this
    // also works when the phones are joined over hotspot/ethernet, not just
    // a normal Wi-Fi AP.
    fun getLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull()
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}
