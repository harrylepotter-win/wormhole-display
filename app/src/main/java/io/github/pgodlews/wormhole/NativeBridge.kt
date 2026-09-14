package io.github.pgodlews.wormhole

object NativeBridge {
    interface Listener {
        /** ingressNanos is monotonic; core NTP nanoseconds are diagnostic metadata only. */
        fun onVideoFrame(data: ByteArray, ingressNanos: Long, ntpLocalNanos: Long, ntpRemoteNanos: Long, hevc: Boolean)
        fun onAudioFrame(data: ByteArray, ntpTimestamp: Long, ct: Int) {}
        fun onAudioVolume(volume: Float) {}
        fun onAudioFlush() {}
        fun onClientConnected(name: String)

        /** Any control socket closed — diagnostic only, not a mirror-stop signal. */
        fun onClientDisconnected()

        /** Verified mirror-stream lifetime; owns renderer state. */
        fun onMirrorRunning(running: Boolean)

        /** Unexpected mirror failure; the controller resets the server outside native callbacks. */
        fun onStreamError()
    }

    init { System.loadLibrary("wormhole") }

    external fun nativeSetListener(listener: Listener?)

    /** Starts the AirPlay server; returns the RTSP port (7000) or a negative error. */
    external fun nativeStart(
        keyFile: String,
        deviceId: String,
        serviceName: String,
        width: Int,
        height: Int,
        maxFps: Int,
        allowHevc: Boolean
    ): Int

    /** Hex ed25519 public key owned by the server; advertise it as pk=. */
    external fun nativePublicKey(): String?

    external fun nativeStop()
}
