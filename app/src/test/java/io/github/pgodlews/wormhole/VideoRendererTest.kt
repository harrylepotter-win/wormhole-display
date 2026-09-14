package io.github.pgodlews.wormhole

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoRendererTest {

    @Test
    fun `parseDimensions extracts dimensions from crop rectangle`() {
        val map = mapOf(
            "crop-left" to 0,
            "crop-right" to 663,
            "crop-top" to 0,
            "crop-bottom" to 1439,
            "width" to 672,
            "height" to 1440
        )
        val (w, h) = VideoRenderer.parseDimensions(
            hasKey = { map.containsKey(it) },
            getInt = { map[it] ?: 0 }
        )
        assertEquals(664, w)
        assertEquals(1440, h)
    }

    @Test
    fun `parseDimensions falls back to width and height when crop is missing`() {
        val map = mapOf(
            "width" to 1920,
            "height" to 1080
        )
        val (w, h) = VideoRenderer.parseDimensions(
            hasKey = { map.containsKey(it) },
            getInt = { map[it] ?: 0 }
        )
        assertEquals(1920, w)
        assertEquals(1080, h)
    }

    @Test
    fun `parseDimensions falls back to width and height when crop coordinates are invalid`() {
        val map = mapOf(
            "crop-left" to 100,
            "crop-right" to 50,
            "crop-top" to 100,
            "crop-bottom" to 50,
            "width" to 1920,
            "height" to 1080
        )
        val (w, h) = VideoRenderer.parseDimensions(
            hasKey = { map.containsKey(it) },
            getInt = { map[it] ?: 0 }
        )
        assertEquals(1920, w)
        assertEquals(1080, h)
    }

    @Test
    fun `parseDimensions returns zeroes for empty format`() {
        val (w, h) = VideoRenderer.parseDimensions(
            hasKey = { false },
            getInt = { 0 }
        )
        assertEquals(0, w)
        assertEquals(0, h)
    }
}
