package io.github.pgodlews.wormhole

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkInfoHelper {
    data class NetworkDetails(
        val ipAddress: String,
        val wifiSsid: String,
        val isConnected: Boolean
    )

    fun getNetworkDetails(context: Context): NetworkDetails {
        val ip = getLocalIpAddress()
        val (ssid, connected) = getWifiInfo(context)
        return NetworkDetails(
            ipAddress = ip ?: "Not connected",
            wifiSsid = ssid,
            isConnected = ip != null
        )
    }

    fun getLocalIpAddress(): String? {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val sorted = interfaces.sortedByDescending {
                it.name.startsWith("wlan") || it.name.startsWith("eth")
            }
            for (intf in sorted) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress
                        if (!host.isNullOrEmpty() && host != "127.0.0.1") {
                            return host
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun getWifiInfo(context: Context): Pair<String, Boolean> {
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wifi?.connectionInfo
            val ssid = info?.ssid?.removeSurrounding("\"")
            if (ssid != null && ssid != "<unknown ssid>" && ssid.isNotBlank()) {
                Pair(ssid, true)
            } else if (getLocalIpAddress() != null) {
                Pair("Wi-Fi Connected", true)
            } else {
                Pair("Disconnected", false)
            }
        } catch (e: Exception) {
            Pair("Wi-Fi", false)
        }
    }
}
