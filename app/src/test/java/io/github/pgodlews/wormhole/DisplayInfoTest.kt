package io.github.pgodlews.wormhole

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayInfoTest {

    @Test
    fun `computeDisplayInfo landscape 1080p calculates 16-9 mode`() {
        val info = computeDisplayInfo(1920, 1080, 60, isPortrait = false)
        assertEquals(1920, info.width)
        assertEquals(1080, info.height)
        assertEquals(60, info.refreshRate)
        assertEquals("16:9", info.aspectRatioLabel)
        assertTrue(info.recommendedResolutions.contains("1920 × 1080 (Default)"))
        assertTrue(info.recommendedResolutions.contains("1280 × 720"))
    }

    @Test
    fun `computeDisplayInfo portrait 1080p calculates 9-16 mode`() {
        val info = computeDisplayInfo(1920, 1080, 60, isPortrait = true)
        assertEquals(1080, info.width)
        assertEquals(1920, info.height)
        assertEquals(60, info.refreshRate)
        assertEquals("9:16", info.aspectRatioLabel)
        assertTrue(info.recommendedResolutions.contains("1080 × 1920 (Default)"))
        assertTrue(info.recommendedResolutions.contains("720 × 1280"))
    }

    @Test
    fun `computeDisplayInfo landscape 800x1280 calculates 16-10 mode`() {
        val info = computeDisplayInfo(800, 1280, 60, isPortrait = false)
        assertEquals(1280, info.width)
        assertEquals(800, info.height)
        assertEquals(60, info.refreshRate)
        assertEquals("16:10", info.aspectRatioLabel)
        assertTrue(info.recommendedResolutions.contains("1280 × 800 (Default)"))
    }

    @Test
    fun `computeDisplayInfo portrait 800x1280 calculates 10-16 mode`() {
        val info = computeDisplayInfo(800, 1280, 60, isPortrait = true)
        assertEquals(800, info.width)
        assertEquals(1280, info.height)
        assertEquals(60, info.refreshRate)
        assertEquals("10:16", info.aspectRatioLabel)
        assertTrue(info.recommendedResolutions.contains("800 × 1280 (Default)"))
    }

    @Test
    fun `ScreenOrientation fromId parses correctly`() {
        assertEquals(ScreenOrientation.LANDSCAPE, ScreenOrientation.fromId("landscape"))
        assertEquals(ScreenOrientation.PORTRAIT, ScreenOrientation.fromId("portrait"))
        assertEquals(ScreenOrientation.AUTO, ScreenOrientation.fromId("auto"))
        assertEquals(ScreenOrientation.LANDSCAPE, ScreenOrientation.fromId("unknown"))
        assertEquals(ScreenOrientation.LANDSCAPE, ScreenOrientation.fromId(null))
    }

    @Test
    fun `isTvDevice detects Portal TV from model device and product`() {
        assertTrue(WormholeIdentity.isTvDevice(model = "Portal TV", device = "ripley", product = "ripley_prod"))
        assertTrue(WormholeIdentity.isTvDevice(model = "Portal TV", device = "unknown", product = "unknown"))
        assertTrue(WormholeIdentity.isTvDevice(model = "Meta Portal", device = "ripley", product = "unknown"))
        assertTrue(WormholeIdentity.isTvDevice(model = "Android TV", device = "box", product = "tv_prod"))
        org.junit.Assert.assertFalse(WormholeIdentity.isTvDevice(model = "Portal Mini", device = "omni", product = "omni_prod"))
        org.junit.Assert.assertFalse(WormholeIdentity.isTvDevice(model = "Portal+", device = "aloha", product = "aloha_prod"))
        org.junit.Assert.assertFalse(WormholeIdentity.isTvDevice(model = "Portal Go", device = "panam", product = "panam_prod"))
    }

    @Test
    fun `isPortalGo detects Portal Go`() {
        assertTrue(WormholeIdentity.isPortalGo(model = "Portal Go", device = "terry", product = "terry_prod"))
        assertTrue(WormholeIdentity.isPortalGo(model = "Portal Go", device = "unknown", product = "unknown"))
        assertTrue(WormholeIdentity.isPortalGo(model = "Unknown", device = "panam", product = "panam_prod"))
        assertTrue(WormholeIdentity.isPortalGo(model = "Unknown", device = "terry", product = "terry_prod"))
        org.junit.Assert.assertFalse(WormholeIdentity.isPortalGo(model = "Portal Mini", device = "omni", product = "omni_prod"))
        org.junit.Assert.assertFalse(WormholeIdentity.isPortalGo(model = "Portal+", device = "ohana", product = "ohana_prod"))
    }

    @Test
    fun `isPortalPlusGen2 detects Portal Plus Gen 2 and distinguishes from Gen 1`() {
        // Gen 2 by codename cipher
        assertTrue(WormholeIdentity.isPortalPlusGen2(model = "Portal+", device = "cipher", product = "cipher_prod"))
        // Gen 2 by 2160x1440 panel size
        assertTrue(WormholeIdentity.isPortalPlusGen2(model = "Portal+", device = "unknown", product = "unknown", displayWidth = 2160, displayHeight = 1440))
        assertTrue(WormholeIdentity.isPortalPlusGen2(model = "Portal Plus", device = "unknown", product = "unknown", displayWidth = 1440, displayHeight = 2160))
        // Gen 1 has 1920x1080 panel and aloha/ohana codename
        org.junit.Assert.assertFalse(WormholeIdentity.isPortalPlusGen2(model = "Portal+", device = "ohana", product = "ohana_prod", displayWidth = 1920, displayHeight = 1080))
        org.junit.Assert.assertFalse(WormholeIdentity.isPortalPlusGen2(model = "Portal+", device = "aloha", product = "aloha_prod", displayWidth = 1920, displayHeight = 1080))
        org.junit.Assert.assertFalse(WormholeIdentity.isPortalPlusGen2(model = "Portal Mini", device = "omni", product = "omni_prod", displayWidth = 1280, displayHeight = 800))
    }

    @Test
    fun `supportsAutoOrientation disables auto on TV, Go, and Plus Gen 2 while allowing on Plus Gen 1 and Mini`() {
        // TV: disabled
        org.junit.Assert.assertFalse(WormholeIdentity.supportsAutoOrientation(model = "Portal TV", device = "ripley", product = "ripley_prod"))
        // Go: disabled
        org.junit.Assert.assertFalse(WormholeIdentity.supportsAutoOrientation(model = "Portal Go", device = "terry", product = "terry_prod"))
        // Plus Gen 2: disabled
        org.junit.Assert.assertFalse(WormholeIdentity.supportsAutoOrientation(model = "Portal+", device = "cipher", product = "cipher_prod", displayWidth = 2160, displayHeight = 1440))
        // Plus Gen 1: supported! (context=null bypasses sensor check in unit test)
        assertTrue(WormholeIdentity.supportsAutoOrientation(model = "Portal+", device = "ohana", product = "ohana_prod", displayWidth = 1920, displayHeight = 1080))
        // Mini: supported!
        assertTrue(WormholeIdentity.supportsAutoOrientation(model = "Portal Mini", device = "omni", product = "omni_prod", displayWidth = 1280, displayHeight = 800))
    }
}
