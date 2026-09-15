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

        val isTv: Boolean
            get() = isTvDevice()

        fun isTvDevice(
            model: String? = android.os.Build.MODEL,
            device: String? = android.os.Build.DEVICE,
            product: String? = android.os.Build.PRODUCT,
            context: Context? = null
        ): Boolean {
            val m = model.orEmpty()
            val d = device.orEmpty()
            val p = product.orEmpty()
            if (m.contains("TV", ignoreCase = true) ||
                d.contains("ripley", ignoreCase = true) ||
                p.contains("ripley", ignoreCase = true)) {
                return true
            }
            if (context != null) {
                val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
                if (uiModeManager?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) {
                    return true
                }
                if (context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
                    context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEVISION)) {
                    return true
                }
            }
            return false
        }

        fun isPortalGo(
            model: String? = android.os.Build.MODEL,
            device: String? = android.os.Build.DEVICE,
            product: String? = android.os.Build.PRODUCT
        ): Boolean {
            val m = model.orEmpty()
            val d = device.orEmpty()
            val p = product.orEmpty()
            return m.contains("Go", ignoreCase = true) ||
                   d.contains("terry", ignoreCase = true) ||
                   p.contains("terry", ignoreCase = true) ||
                   d.contains("panam", ignoreCase = true) ||
                   p.contains("panam", ignoreCase = true)
        }

        fun isPortalPlusGen2(
            model: String? = android.os.Build.MODEL,
            device: String? = android.os.Build.DEVICE,
            product: String? = android.os.Build.PRODUCT,
            displayWidth: Int = 0,
            displayHeight: Int = 0
        ): Boolean {
            val m = model.orEmpty()
            val d = device.orEmpty()
            val p = product.orEmpty()
            // Codename cipher is Portal+ Gen 2 (fixed tilt stand, no swivel)
            if (d.contains("cipher", ignoreCase = true) || p.contains("cipher", ignoreCase = true)) {
                return true
            }
            // Portal+ Gen 2 has a 2160x1440 panel; Gen 1 is 1920x1080
            val maxDim = maxOf(displayWidth, displayHeight)
            if (maxDim >= 2160 && (m.contains("+") || m.contains("Plus", ignoreCase = true))) {
                return true
            }
            return false
        }

        fun supportsAutoOrientation(
            context: Context? = null,
            model: String? = android.os.Build.MODEL,
            device: String? = android.os.Build.DEVICE,
            product: String? = android.os.Build.PRODUCT,
            displayWidth: Int = 0,
            displayHeight: Int = 0
        ): Boolean {
            if (isTvDevice(model, device, product, context)) return false
            if (isPortalGo(model, device, product)) return false
            if (isPortalPlusGen2(model, device, product, displayWidth, displayHeight)) return false
            if (context != null) {
                val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
                if (sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) == null) {
                    return false
                }
            }
            return true
        }

        fun hasOrientationSensor(
            context: Context,
            displayWidth: Int = 0,
            displayHeight: Int = 0
        ): Boolean {
            val w = if (displayWidth > 0) displayWidth else context.resources.displayMetrics.widthPixels
            val h = if (displayHeight > 0) displayHeight else context.resources.displayMetrics.heightPixels
            return supportsAutoOrientation(
                context = context,
                displayWidth = w,
                displayHeight = h
            )
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
