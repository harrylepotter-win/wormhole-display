package io.github.pgodlews.wormhole

import java.util.ArrayDeque
import java.util.Locale

class StreamTelemetry(private val windowNanos: Long = 10_000_000_000L /* 10 seconds */) {

    data class Snapshot(
        val fps: Float,
        val bytesPerSec: Long,
        val totalFramesRendered: Long,
        val totalBytesReceived: Long,
        val width: Int,
        val height: Int,
        val codecName: String,
        val audioActive: Boolean,
        val audioEnabled: Boolean,
        val audioCodec: String,
        val audioVolumePercent: Int,
        val audioVolumeDb: Float,
        val audioFramesPlayed: Long,
        val audioBytesPerSec: Long
    ) {
        val bitrateMbps: Float
            get() = (bytesPerSec * 8f) / 1_000_000f

        val formattedThroughput: String
            get() = when {
                bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.1f Mbps (%.2f MB/s)", bitrateMbps, bytesPerSec / (1024f * 1024f))
                bytesPerSec >= 1024 -> String.format(Locale.US, "%.1f kbps (%.1f KB/s)", (bytesPerSec * 8f) / 1000f, bytesPerSec / 1024f)
                else -> "$bytesPerSec B/s"
            }

        val formattedResolution: String
            get() = if (width > 0 && height > 0) "${width} × ${height}" else "Detecting…"

        val formattedAudioStatus: String
            get() = when {
                !audioEnabled -> "Disabled / Muted"
                audioActive && audioFramesPlayed > 0 -> "Streaming"
                audioActive -> "Standby"
                else -> "Inactive"
            }

        val formattedAudioVolume: String
            get() = "$audioVolumePercent% (${String.format(Locale.US, "%.1f", audioVolumeDb)} dB)"
    }

    private val frameTimestamps = ArrayDeque<Long>()
    private val byteEvents = ArrayDeque<Pair<Long, Int>>()
    private val audioByteEvents = ArrayDeque<Pair<Long, Int>>()
    private var totalFrames = 0L
    private var totalBytes = 0L
    private var currentWidth = 0
    private var currentHeight = 0
    private var codec = ""

    private var audioActive = false
    private var audioEnabled = true
    private var audioCodec = "AAC-ELD (44.1 kHz)"
    private var audioVolumePercent = 100
    private var audioVolumeDb = 0.0f
    private var audioFramesPlayed = 0L

    @Synchronized
    fun onFrameReceived(bytes: Int, now: Long = System.nanoTime()) {
        totalBytes += bytes
        byteEvents.addLast(Pair(now, bytes))
        trimOld(now)
    }

    @Synchronized
    fun onFrameRendered(now: Long = System.nanoTime()) {
        totalFrames++
        frameTimestamps.addLast(now)
        trimOld(now)
    }

    @Synchronized
    fun onDimensionsChanged(w: Int, h: Int) {
        currentWidth = w
        currentHeight = h
    }

    @Synchronized
    fun onCodecStarted(name: String) {
        codec = name
    }

    @Synchronized
    fun onAudioFrameReceived(bytes: Int, now: Long = System.nanoTime()) {
        audioByteEvents.addLast(Pair(now, bytes))
        trimOld(now)
    }

    @Synchronized
    fun onAudioFramePlayed() {
        audioFramesPlayed++
    }

    @Synchronized
    fun onAudioVolumeChanged(volumeDb: Float) {
        audioVolumeDb = volumeDb
        val linear = AudioRenderer.dbToLinear(volumeDb)
        audioVolumePercent = (linear * 100f).toInt().coerceIn(0, 100)
    }

    @Synchronized
    fun onAudioStateChanged(enabled: Boolean, active: Boolean) {
        audioEnabled = enabled
        audioActive = active
    }

    @Synchronized
    fun onAudioFormatConfigured(codec: String, sampleRate: Int, channels: Int) {
        val ch = if (channels == 2) "Stereo" else if (channels == 1) "Mono" else "${channels}ch"
        audioCodec = "$codec (${sampleRate / 1000.0} kHz $ch)"
    }

    @Synchronized
    fun reset() {
        frameTimestamps.clear()
        byteEvents.clear()
        audioByteEvents.clear()
        totalFrames = 0L
        totalBytes = 0L
        currentWidth = 0
        currentHeight = 0
        codec = ""
        audioFramesPlayed = 0L
        audioActive = false
    }

    private fun trimOld(now: Long) {
        val cutoff = now - windowNanos
        while (frameTimestamps.isNotEmpty() && frameTimestamps.first() < cutoff) {
            frameTimestamps.removeFirst()
        }
        while (byteEvents.isNotEmpty() && byteEvents.first().first < cutoff) {
            byteEvents.removeFirst()
        }
        while (audioByteEvents.isNotEmpty() && audioByteEvents.first().first < cutoff) {
            audioByteEvents.removeFirst()
        }
    }

    @Synchronized
    fun getSnapshot(now: Long = System.nanoTime()): Snapshot {
        trimOld(now)
        val windowSec = (windowNanos.toDouble() / 1_000_000_000.0).toFloat()
        val fps = if (frameTimestamps.isEmpty()) 0f else (frameTimestamps.size / windowSec)
        val windowBytes = byteEvents.sumOf { it.second.toLong() }
        val bytesPerSec = (windowBytes / windowSec).toLong()
        val audioWindowBytes = audioByteEvents.sumOf { it.second.toLong() }
        val audioBytesPerSec = (audioWindowBytes / windowSec).toLong()

        return Snapshot(
            fps = fps,
            bytesPerSec = bytesPerSec,
            totalFramesRendered = totalFrames,
            totalBytesReceived = totalBytes,
            width = currentWidth,
            height = currentHeight,
            codecName = codec,
            audioActive = audioActive,
            audioEnabled = audioEnabled,
            audioCodec = audioCodec,
            audioVolumePercent = audioVolumePercent,
            audioVolumeDb = audioVolumeDb,
            audioFramesPlayed = audioFramesPlayed,
            audioBytesPerSec = audioBytesPerSec
        )
    }
}
