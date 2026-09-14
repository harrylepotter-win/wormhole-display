package io.github.pgodlews.wormhole

import org.junit.Assert.*
import org.junit.Test

class HevcFrameQueueTest {
    private fun nal(type: Int) = byteArrayOf(0, 0, 0, 1, (type shl 1).toByte(), 1, 0x55)
    private val config get() = nal(32) + nal(33) + nal(34)
    private fun VideoFrameQueue.hevc(bytes: ByteArray, now: Long = 0) = offer(bytes, now, codec = VideoCodec.HEVC)

    @Test fun `HEVC needs VPS SPS PPS and a random access picture`() {
        val q = VideoFrameQueue()
        q.hevc(nal(33)); q.hevc(nal(34)); q.hevc(nal(19))
        assertNull(q.peek())
        q.hevc(nal(32)); q.hevc(nal(1))
        assertNull(q.peek())
        q.hevc(nal(20))
        assertArrayEquals(config + nal(20), q.peek()!!.bytes)
        assertEquals(VideoCodec.HEVC, q.peek()!!.codec)
    }

    @Test fun `HEVC overflow rebuilds decoder from CRA and skips dependent leading RASL`() {
        val q = VideoFrameQueue(maxFrames = 2)
        q.hevc(config + nal(19)); q.hevc(nal(1))
        val old = q.peek()!!
        q.hevc(nal(1))
        assertNull(q.peek())
        q.hevc(nal(21))
        assertNotEquals(old.generation, q.currentGeneration())
        val cra = q.peek()!!
        assertArrayEquals(config + nal(21), cra.bytes)
        q.consumed(cra)
        q.hevc(nal(8)); q.hevc(nal(9))
        assertNull(q.peek())
        q.hevc(nal(1))
        assertNotNull(q.peek())
    }

    @Test fun `codec switch discards old configuration and pictures`() {
        val q = VideoFrameQueue()
        q.offer(byteArrayOf(0,0,1,7,42,0,0,1,8,42,0,0,1,5,42), 0)
        val old = q.peek()!!
        q.hevc(nal(19))
        assertNull(q.peek())
        q.hevc(config + nal(19))
        assertNotEquals(old.generation, q.peek()!!.generation)
        q.consumed(old)
        assertEquals(VideoCodec.HEVC, q.peek()!!.codec)
        q.offer(byteArrayOf(0,0,1,5,42), 1)
        assertNull(q.peek())
    }

    @Test fun `HEVC configuration participates in byte budget and resets between senders`() {
        val q = VideoFrameQueue(maxBytes = 27)
        q.hevc(config); q.hevc(nal(19)) // 21 config + 7 picture exceeds 27
        assertNull(q.peek())
        val other = VideoFrameQueue()
        other.hevc(config + nal(19)); other.reset(); other.hevc(nal(19))
        assertNull(other.peek())
        other.hevc(byteArrayOf(0,0,1,38)) // truncated two-byte HEVC header
        assertNull(other.peek())
    }
}
