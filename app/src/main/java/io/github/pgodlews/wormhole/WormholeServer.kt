package io.github.pgodlews.wormhole

import android.content.Context
import android.content.SharedPreferences
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide manager that owns the native AirPlay server, audio/video renderers,
 * connection history, and reactive state. Shared between [WormholeService] and [MainActivity].
 */
object WormholeServer {
    private const val TAG = "WormholeServer"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    lateinit var identity: WormholeIdentity
        private set
    lateinit var history: ConnectionHistory
        private set

    // State flows observed by UI and Service
    private val _isMirroring = MutableStateFlow(false)
    val isMirroring: StateFlow<Boolean> = _isMirroring.asStateFlow()

    private val _clientName = MutableStateFlow<String?>(null)
    val clientName: StateFlow<String?> = _clientName.asStateFlow()

    private val _statusText = MutableStateFlow("Starting receiver…")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _videoAspectRatio = MutableStateFlow<Float?>(null)
    val videoAspectRatio: StateFlow<Float?> = _videoAspectRatio.asStateFlow()

    private val _recentConnections = MutableStateFlow<List<ConnectionEntry>>(emptyList())
    val recentConnections: StateFlow<List<ConnectionEntry>> = _recentConnections.asStateFlow()

    private val _displayInfo = MutableStateFlow(DisplayInfo(1920, 1080, 60, "16:9", "1920 × 1080 (Default)"))
    val displayInfo: StateFlow<DisplayInfo> = _displayInfo.asStateFlow()

    var audioEnabled: Boolean = true
        private set
    var debugOverlayEnabled: Boolean = false
        private set
    var hevcEnabled: Boolean = false
        private set
    private val hevcFailedThisProcess = AtomicBoolean(false)
    var runInBackground: Boolean = true
        private set
    var startOnBoot: Boolean = true
        private set

    // Renderers
    val renderer = VideoRenderer(
        reconnectRequired = { requestReconnect() },
        onHevcFailure = {
            hevcFailedThisProcess.set(true)
            Log.w(TAG, "HEVC session failed; next connection will offer H.264 only until H.265 is toggled or app restarts")
        },
        onVideoSizeChanged = { w, h ->
            _videoAspectRatio.value = if (w > 0 && h > 0) w.toFloat() / h.toFloat() else null
        }
    )
    val audioRenderer = AudioRenderer(renderer.telemetry)

    // Activity state
    @Volatile
    var isActivityResumed: Boolean = false

    var onIncomingStreamBackgroundCallback: (() -> Unit)? = null

    // Server lifecycle internals
    private val serverExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "Wormhole server") }
    private val serverLifecycle = ServerLifecycle(serverExecutor)
    private var lease: ServerLifecycle.Lease? = null

    private val destroyed = AtomicBoolean(false)
    private val callbacksEnabled = AtomicBoolean(false)
    private val callbackGeneration = AtomicLong()
    private val mirrorGeneration = AtomicLong()

    private var sessionStartTime = 0L
    private var sessionClientName: String? = null

    val idleStatus: String
        get() = "Visible in Screen Mirroring as “${identity.serviceName}”"

    @Synchronized
    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

        identity = WormholeIdentity(appContext)
        history = ConnectionHistory(appContext)
        _recentConnections.value = history.getRecent()

        audioEnabled = prefs.getBoolean("audio_enabled", true)
        debugOverlayEnabled = prefs.getBoolean("debug_overlay_enabled", false)
        hevcEnabled = prefs.getBoolean("hevc_enabled", false)
        runInBackground = prefs.getBoolean("run_in_background", true)
        startOnBoot = prefs.getBoolean("start_on_boot", true)

        audioRenderer.audioEnabled = audioEnabled

        _displayInfo.value = queryDisplayInfo(appContext)
    }

    fun setSurface(surface: Surface?) {
        renderer.setSurface(surface)
    }

    fun setAudioEnabled(enabled: Boolean) {
        audioEnabled = enabled
        audioRenderer.audioEnabled = enabled
        prefs.edit().putBoolean("audio_enabled", enabled).apply()
    }

    fun setDebugOverlayEnabled(enabled: Boolean) {
        debugOverlayEnabled = enabled
        prefs.edit().putBoolean("debug_overlay_enabled", enabled).apply()
    }

    fun setHevcEnabled(enabled: Boolean) {
        if (hevcEnabled == enabled) return
        hevcEnabled = enabled
        hevcFailedThisProcess.set(false)
        prefs.edit().putBoolean("hevc_enabled", enabled).apply()
        restartServer()
    }

    fun setRunInBackground(enabled: Boolean) {
        runInBackground = enabled
        prefs.edit().putBoolean("run_in_background", enabled).apply()
    }

    fun setStartOnBoot(enabled: Boolean) {
        startOnBoot = enabled
        prefs.edit().putBoolean("start_on_boot", enabled).apply()
    }

    fun updateServiceName(newName: String) {
        identity.serviceName = newName
        restartServer()
    }

    fun updateDisplayMetrics(width: Int, height: Int, fps: Int) {
        val computed = computeDisplayInfo(width, height, fps)
        if (computed != _displayInfo.value) {
            _displayInfo.value = computed
            if (isServerRunning()) {
                restartServer()
            }
        }
    }

    fun isServerRunning(): Boolean = callbacksEnabled.get()

    fun startServer() {
        check(::appContext.isInitialized) { "WormholeServer must be initialized with context" }
        if (callbacksEnabled.get()) return

        val keyFile = File(appContext.filesDir, "server-key.pem").absolutePath
        val currentDisplay = _displayInfo.value
        val requestHevc = hevcEnabled

        lease = ServerLifecycle.Lease(
            start = {
                callbackGeneration.incrementAndGet()
                val startEvent = mirrorGeneration.incrementAndGet()
                if (mirrorGeneration.get() == startEvent) {
                    _isMirroring.value = false
                    _clientName.value = null
                    _statusText.value = "Starting receiver…"
                }
                identity.holdMulticastLock()
                callbacksEnabled.set(true)
                NativeBridge.nativeSetListener(listener)
                renderer.hevcDecoder = if (requestHevc && !hevcFailedThisProcess.get()) VideoDecoderSupport.findHevc(
                    currentDisplay.width, currentDisplay.height, currentDisplay.refreshRate
                ) else null
                Log.i(TAG, "HEVC requested=$requestHevc hardware=${renderer.hevcDecoder}")
                val port = NativeBridge.nativeStart(
                    keyFile, identity.deviceIdHex, identity.serviceName,
                    currentDisplay.width, currentDisplay.height, currentDisplay.refreshRate,
                    renderer.hevcDecoder != null
                )
                check(port > 0) { "Server failed to start ($port)" }
                if (mirrorGeneration.get() == startEvent) {
                    _isMirroring.value = false
                    _clientName.value = null
                    _statusText.value = "$idleStatus — select it to connect"
                }
            },
            stop = {
                callbacksEnabled.set(false)
                callbackGeneration.incrementAndGet()
                renderer.endSession()
                audioRenderer.endSession()
                NativeBridge.nativeSetListener(null)
                try {
                    NativeBridge.nativeStop()
                } finally {
                    identity.releaseMulticastLock()
                }
            },
            failed = { error ->
                _isMirroring.value = false
                _statusText.value = error.message ?: "Server failed"
            }
        )
        lease?.let { serverLifecycle.start(it) }
    }

    fun stopServer() {
        callbacksEnabled.set(false)
        callbackGeneration.incrementAndGet()
        lease?.let { serverLifecycle.stop(it) }
        lease = null
        _isMirroring.value = false
        _clientName.value = null
        _statusText.value = "Receiver stopped"
    }

    fun restartServer() {
        val currentLease = lease
        if (currentLease != null && callbacksEnabled.get()) {
            recordSessionEnd()
            renderer.endSession()
            audioRenderer.endSession()
            mirrorGeneration.incrementAndGet()
            _isMirroring.value = false
            _videoAspectRatio.value = null
            _clientName.value = null
            _statusText.value = "Restarting receiver…"
            serverLifecycle.restart(currentLease)
        } else {
            startServer()
        }
    }

    /** The user left the stream on the device (Back/Home): drop the sender by restarting the receiver. */
    fun disconnectClient(reason: String) {
        if (!_isMirroring.value) return
        Log.i("Wormhole", "Disconnecting client: $reason")
        restartServer()
    }

    private fun requestReconnect() {
        if (destroyed.get() || !callbacksEnabled.compareAndSet(true, false)) return
        recordSessionEnd()
        renderer.endSession()
        audioRenderer.endSession()
        mirrorGeneration.incrementAndGet()
        _isMirroring.value = false
        _videoAspectRatio.value = null
        _clientName.value = null
        _statusText.value = "Stream interrupted — reconnect from Screen Mirroring"
        lease?.let { serverLifecycle.restart(it) }
    }

    private fun recordSessionEnd() {
        if (sessionStartTime > 0L) {
            val duration = (System.currentTimeMillis() - sessionStartTime) / 1000
            val name = sessionClientName ?: _clientName.value ?: "Unknown device"
            history.recordConnection(name, sessionStartTime, duration)
            _recentConnections.value = history.getRecent()
            sessionStartTime = 0L
            sessionClientName = null
        }
    }

    private val listener = object : NativeBridge.Listener {
        override fun onVideoFrame(data: ByteArray, ingressNanos: Long, ntpLocalNanos: Long, ntpRemoteNanos: Long, hevc: Boolean) {
            if (callbacksEnabled.get()) renderer.onFrame(data, ingressNanos, ntpLocalNanos, ntpRemoteNanos, hevc)
        }

        override fun onAudioFrame(data: ByteArray, ntpTimestamp: Long, ct: Int) {
            if (callbacksEnabled.get()) audioRenderer.onFrame(data, ntpTimestamp, ct)
        }

        override fun onAudioVolume(volume: Float) {
            if (callbacksEnabled.get()) audioRenderer.setVolume(volume)
        }

        override fun onAudioFlush() {
            if (callbacksEnabled.get()) audioRenderer.flush()
        }

        override fun onClientConnected(name: String) {
            sessionClientName = name
            if (callbacksEnabled.get()) {
                _clientName.value = name
                _statusText.value = "Connected: $name"
            }
        }

        override fun onClientDisconnected() {
            // Socket close is diagnostic only
        }

        override fun onMirrorRunning(running: Boolean) {
            if (!callbacksEnabled.get()) return
            if (running) {
                sessionStartTime = System.currentTimeMillis()
                renderer.beginSession()
                audioRenderer.beginSession()
            } else {
                recordSessionEnd()
                renderer.endSession()
                audioRenderer.endSession()
                _videoAspectRatio.value = null
            }
            val event = mirrorGeneration.incrementAndGet()
            if (event == mirrorGeneration.get()) {
                _isMirroring.value = running
                if (running) {
                    _statusText.value = "Mirroring ${_clientName.value ?: "client"}"
                    if (!isActivityResumed) {
                        onIncomingStreamBackgroundCallback?.invoke()
                    }
                } else {
                    _clientName.value = null
                    _statusText.value = idleStatus
                }
            }
        }

        override fun onStreamError() = requestReconnect()
    }

    private fun queryDisplayInfo(context: Context): DisplayInfo {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val metrics = DisplayMetrics()
        val display = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            context.display
        } else {
            @Suppress("DEPRECATION")
            wm?.defaultDisplay
        }
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)
        @Suppress("DEPRECATION")
        val fps = display?.mode?.refreshRate?.toInt()
            ?: display?.refreshRate?.toInt()
            ?: 60
        val w = if (metrics.widthPixels > 0) metrics.widthPixels else 1920
        val h = if (metrics.heightPixels > 0) metrics.heightPixels else 1080
        return computeDisplayInfo(w, h, fps)
    }
}
