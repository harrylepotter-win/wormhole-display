package io.github.pgodlews.wormhole

import org.junit.Assert.assertEquals
import org.junit.Test

class WormholeIdentityTest {

    @Test
    fun `sanitizeServiceName returns default name for null blank or default strings`() {
        assertEquals(WormholeIdentity.DEFAULT_SERVICE_NAME, WormholeIdentity.sanitizeServiceName(null))
        assertEquals(WormholeIdentity.DEFAULT_SERVICE_NAME, WormholeIdentity.sanitizeServiceName(""))
        assertEquals(WormholeIdentity.DEFAULT_SERVICE_NAME, WormholeIdentity.sanitizeServiceName("   "))
        assertEquals(WormholeIdentity.DEFAULT_SERVICE_NAME, WormholeIdentity.sanitizeServiceName("Wormhole Display"))
        assertEquals(WormholeIdentity.DEFAULT_SERVICE_NAME, WormholeIdentity.sanitizeServiceName("  Wormhole Display  "))
    }

    @Test
    fun `sanitizeServiceName trims custom names and limits length`() {
        assertEquals("Living Room Display", WormholeIdentity.sanitizeServiceName("  Living Room Display  "))
        val longName = "A".repeat(80)
        assertEquals("A".repeat(60), WormholeIdentity.sanitizeServiceName(longName))
    }
}
