package io.github.pgodlews.wormhole

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRendererTest {

    @Test
    fun `dbToLinear maps volume curve correctly`() {
        // 0 dB is unity gain (1.0)
        assertEquals(1.0f, AudioRenderer.dbToLinear(0.0f), 0.0001f)

        // Positive dB is clamped to 1.0
        assertEquals(1.0f, AudioRenderer.dbToLinear(6.0f), 0.0001f)

        // -6 dB is approx half amplitude (0.501)
        assertEquals(0.501187f, AudioRenderer.dbToLinear(-6.0f), 0.001f)

        // -20 dB is 0.1 amplitude
        assertEquals(0.1f, AudioRenderer.dbToLinear(-20.0f), 0.001f)

        // -40 dB is 0.01 amplitude
        assertEquals(0.01f, AudioRenderer.dbToLinear(-40.0f), 0.001f)

        // -144 dB and below is silence (0.0)
        assertEquals(0.0f, AudioRenderer.dbToLinear(-144.0f), 0.0f)
        assertEquals(0.0f, AudioRenderer.dbToLinear(-200.0f), 0.0f)
    }

    @Test
    fun `AAC ELD AudioSpecificConfig headers are formatted correctly`() {
        val csd480 = AudioRenderer.CSD_AAC_ELD_44100_STEREO_480
        assertEquals(4, csd480.size)
        assertEquals(0xF8.toByte(), csd480[0])
        assertEquals(0xE8.toByte(), csd480[1])
        assertEquals(0x50.toByte(), csd480[2])
        assertEquals(0x00.toByte(), csd480[3])

        val csd512 = AudioRenderer.CSD_AAC_ELD_44100_STEREO_512
        assertEquals(4, csd512.size)
        assertEquals(0xF8.toByte(), csd512[0])
        assertEquals(0xE8.toByte(), csd512[1])
        assertEquals(0x20.toByte(), csd512[2])
        assertEquals(0x00.toByte(), csd512[3])

        // The 480 spf vs 512 spf difference is in bit 4 of byte 2 (frameLengthFlag)
        val bit480 = (csd480[2].toInt() and 0x40) != 0
        val bit512 = (csd512[2].toInt() and 0x40) != 0
        assertTrue(bit480)
        assertTrue(!bit512)
    }

    @Test
    fun `audioEnabled defaults to true and can be toggled`() {
        val renderer = AudioRenderer()
        assertTrue(renderer.audioEnabled)
        renderer.audioEnabled = false
        assertTrue(!renderer.audioEnabled)
        renderer.close()
    }
}
