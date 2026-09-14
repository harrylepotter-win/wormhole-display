package io.github.pgodlews.wormhole

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTelemetryTest {

    @Test
    fun `telemetry calculates exact FPS and byte rate within sliding window`() {
        val telemetry = StreamTelemetry(windowNanos = 10_000_000_000L) // 10s
        val baseTime = 100_000_000_000L

        // Feed 300 frames evenly spaced over 5 seconds (60 fps)
        for (i in 0 until 300) {
            val t = baseTime + (i * 1_000_000_000L / 60)
            telemetry.onFrameReceived(10_000, now = t) // 10 KB per frame
            telemetry.onFrameRendered(now = t)
        }

        val snapshot = telemetry.getSnapshot(now = baseTime + 5_000_000_000L)
        // Over the 10-second window, 300 frames were rendered: 300 / 10s = 30.0 fps
        assertEquals(30.0f, snapshot.fps, 0.1f)
        assertEquals(300L, snapshot.totalFramesRendered)
        assertEquals(3_000_000L, snapshot.totalBytesReceived)
        // 3,000,000 bytes over 10s = 300,000 bytes/sec
        assertEquals(300_000L, snapshot.bytesPerSec)
        assertEquals(2.4f, snapshot.bitrateMbps, 0.05f)
    }

    @Test
    fun `telemetry trims events older than 10 seconds`() {
        val telemetry = StreamTelemetry(windowNanos = 10_000_000_000L) // 10s
        val baseTime = 100_000_000_000L

        // Event at t = 0s
        telemetry.onFrameReceived(50_000, now = baseTime)
        telemetry.onFrameRendered(now = baseTime)

        // Event at t = 5s
        telemetry.onFrameReceived(50_000, now = baseTime + 5_000_000_000L)
        telemetry.onFrameRendered(now = baseTime + 5_000_000_000L)

        // Query at t = 12s -> the t = 0s event is older than 10s and should be trimmed
        val snapshot = telemetry.getSnapshot(now = baseTime + 12_000_000_000L)
        // Only 1 frame left in the 10s window
        assertEquals(0.1f, snapshot.fps, 0.01f)
        assertEquals(5_000L, snapshot.bytesPerSec) // 50,000 / 10s = 5,000 B/s
        // Totals keep cumulative counts
        assertEquals(2L, snapshot.totalFramesRendered)
        assertEquals(100_000L, snapshot.totalBytesReceived)
    }

    @Test
    fun `formatted throughput outputs appropriate units`() {
        val telemetry = StreamTelemetry()
        telemetry.onDimensionsChanged(1920, 1080)
        telemetry.onCodecStarted("OMX.qcom.video.decoder.avc")

        val snapshot = telemetry.getSnapshot()
        assertEquals("1920 × 1080", snapshot.formattedResolution)
        assertEquals("OMX.qcom.video.decoder.avc", snapshot.codecName)
    }

    @Test
    fun `telemetry tracks and formats audio metrics correctly`() {
        val telemetry = StreamTelemetry()
        telemetry.onAudioStateChanged(enabled = true, active = true)
        telemetry.onAudioFormatConfigured("AAC-ELD", 44100, 2)
        telemetry.onAudioVolumeChanged(0.0f)
        telemetry.onAudioFramePlayed()
        telemetry.onAudioFramePlayed()

        val snapshot = telemetry.getSnapshot()
        assertEquals(true, snapshot.audioActive)
        assertEquals(true, snapshot.audioEnabled)
        assertEquals("AAC-ELD (44.1 kHz Stereo)", snapshot.audioCodec)
        assertEquals("100% (0.0 dB)", snapshot.formattedAudioVolume)
        assertEquals("Streaming", snapshot.formattedAudioStatus)
        assertEquals(2L, snapshot.audioFramesPlayed)
    }
}
