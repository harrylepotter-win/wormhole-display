package io.github.pgodlews.wormhole

import org.junit.Assert.*
import org.junit.Test

class ConnectionHistoryTest {

    @Test
    fun `serialization and deserialization preserves data including special characters`() {
        val original = ConnectionEntry(
            clientName = "Piotr’s iPhone (iPhone16,2) | special % symbols\nnewline",
            timestamp = 1789245957000L,
            durationSeconds = 125L
        )
        val serialized = original.serialize()
        val deserialized = ConnectionEntry.deserialize(serialized)

        assertNotNull(deserialized)
        assertEquals(original.clientName, deserialized!!.clientName)
        assertEquals(original.timestamp, deserialized.timestamp)
        assertEquals(original.durationSeconds, deserialized.durationSeconds)
    }

    @Test
    fun `formatDuration handles varying duration ranges accurately`() {
        assertEquals("< 5s", ConnectionHistory.formatDuration(0))
        assertEquals("< 5s", ConnectionHistory.formatDuration(4))
        assertEquals("5s", ConnectionHistory.formatDuration(5))
        assertEquals("45s", ConnectionHistory.formatDuration(45))
        assertEquals("1m", ConnectionHistory.formatDuration(60))
        assertEquals("1m 15s", ConnectionHistory.formatDuration(75))
        assertEquals("10m 5s", ConnectionHistory.formatDuration(605))
    }

    @Test
    fun `formatTimestamp outputs relative time correctly`() {
        val now = 1_000_000_000L
        assertEquals("Just now", ConnectionHistory.formatTimestamp(now - 10_000, now))
        assertEquals("5m ago", ConnectionHistory.formatTimestamp(now - 300_000, now))
        assertEquals("2h ago", ConnectionHistory.formatTimestamp(now - 7_200_000, now))
    }
}
