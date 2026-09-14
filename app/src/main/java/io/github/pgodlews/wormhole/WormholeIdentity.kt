package io.github.pgodlews.wormhole

import android.content.Context
import android.net.wifi.WifiManager
import java.security.SecureRandom

/**
 * Device identity for AirPlay plus the Wi-Fi multicast lock that the native
 * mDNS responder (UxPlay's embedded mdnsd) needs to receive Bonjour queries.
 */
class WormholeIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    companion object {
        val DEFAULT_SERVICE_NAME: String
            get() = when {
                android.os.Build.MODEL?.contains("Go", ignoreCase = true) == true -> "Wormhole Go"
                android.os.Build.MODEL?.contains("TV", ignoreCase = true) == true -> "Wormhole TV"
                android.os.Build.MODEL?.contains("+") == true -> "Wormhole Plus"
                else -> "Wormhole Display"
            }

        fun sanitizeServiceName(raw: String?): String {
            val trimmed = raw?.trim().orEmpty()
            val def = DEFAULT_SERVICE_NAME
            return if (trimmed.isBlank() || trimmed == def) {
                def
            } else {
                trimmed.take(60)
            }
        }
    }

    var serviceName: String
        get() = prefs.getString("service_name", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVICE_NAME
        set(value) {
            val sanitized = sanitizeServiceName(value)
            if (sanitized != DEFAULT_SERVICE_NAME) {
                prefs.edit().putString("service_name", sanitized).apply()
            } else {
                prefs.edit().remove("service_name").apply()
            }
        }

    /** Stable, locally administered 6-byte identity used as AirPlay deviceid. */
    val deviceIdHex: String by lazy {
        prefs.getString("deviceid", null)?.takeIf { it.length == 12 }
            ?: ByteArray(6).also { bytes ->
                SecureRandom().nextBytes(bytes)
                bytes[0] = ((bytes[0].toInt() or 0x02) and 0xFE).toByte()
            }.joinToString("") { "%02x".format(it) }
                .also { prefs.edit().putString("deviceid", it).apply() }
    }

    @Synchronized
    fun holdMulticastLock() {
        if (multicastLock == null) {
            multicastLock = wifi.createMulticastLock("wormhole").apply {
                setReferenceCounted(true); acquire()
            }
        }
    }

    @Synchronized
    fun releaseMulticastLock() {
        runCatching { multicastLock?.release() }
        multicastLock = null
    }
}
