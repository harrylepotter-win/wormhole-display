package io.github.pgodlews.wormhole

import java.util.ArrayDeque

internal enum class VideoCodec(val mime: String) { AVC("video/avc"), HEVC("video/hevc") }

/** Bounded, ordered compressed input. Loss invalidates the prediction chain, not just one picture. */
internal class VideoFrameQueue(
    private val maxFrames: Int = 12,
    private val maxBytes: Int = 8 * 1024 * 1024,
    private val startupMaxFrames: Int = maxFrames,
    private val startupWindowNanos: Long = 1_000_000_000L
) {
    init { require(maxFrames > 0 && maxBytes > 0 && startupMaxFrames >= maxFrames && startupWindowNanos > 0) }
    data class Frame(
        val bytes: ByteArray, val receivedNanos: Long, val generation: Long,
        val ntpLocalNanos: Long = 0, val ntpRemoteNanos: Long = 0,
        val ingressNanos: Long = receivedNanos,
        val codec: VideoCodec = VideoCodec.AVC
    )
    data class Stats(
        val depth: Int, val bytes: Int, val oldestAgeNanos: Long,
        val peakDepth: Int, val peakBytes: Int, val overflowEvents: Long,
        val overflowDiscarded: Long, val rejectedPictures: Long, val oversizedPackets: Long
    )
    private var peakDepth = 0
    private var peakBytes = 0
    private var overflowEvents = 0L
    private var overflowDiscarded = 0L
    private var rejectedPictures = 0L
    private var oversizedPackets = 0L
    private val frames = ArrayDeque<Frame>()
    private var bytes = 0
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var vps: ByteArray? = null
    private var codec = VideoCodec.AVC
    private var suppressRasl = false
    private var needsIdr = true
    private var waitingSince: Long? = null
    private var warmingUp = true
    private var startupSince: Long? = null
    var generation = 0L
        private set

    @Synchronized fun reset(clearConfig: Boolean = true) {
        generation++
        frames.clear()
        bytes = 0
        needsIdr = true
        waitingSince = null
        warmingUp = true
        startupSince = null
        suppressRasl = false
        if (clearConfig) { sps = null; pps = null; vps = null }
    }

    @Synchronized fun offer(data: ByteArray, now: Long, ntpLocalNanos: Long = 0, ntpRemoteNanos: Long = 0, ingressNanos: Long = now, codec: VideoCodec = VideoCodec.AVC) {
        if (this.codec != codec) {
            reset()
            this.codec = codec
        }
        if (data.size > maxBytes) {
            oversizedPackets++
            recordOverflow()
            reset()
            waitingSince = now
            return
        }
        var idr = false
        var picture = false
        var rasl = false
        var pos = startCode(data, 0)
        while (pos >= 0) {
            val prefix = if (data[pos + 2].toInt() == 1) 3 else 4
            val header = pos + prefix
            if (header >= data.size) break
            val next = startCode(data, header + 1)
            val end = if (next < 0) data.size else next
            if (codec == VideoCodec.HEVC) {
                if (header + 1 >= end) { pos = next; continue }
                val type = (data[header].toInt() ushr 1) and 63
                when (type) {
                    in 16..21 -> { idr = true; picture = true } // BLA, IDR, CRA random access
                    in 0..9 -> { picture = true; if (type == 8 || type == 9) rasl = true }
                    32 -> vps = data.copyOfRange(pos, end)
                    33 -> sps = data.copyOfRange(pos, end)
                    34 -> pps = data.copyOfRange(pos, end)
                }
            } else when (data[header].toInt() and 31) {
                5 -> { idr = true; picture = true }
                1, 2, 3, 4 -> picture = true
                7 -> sps = data.copyOfRange(pos, end)
                8 -> pps = data.copyOfRange(pos, end)
            }
            pos = next
        }
        if ((sps?.size ?: 0).toLong() + (pps?.size ?: 0) + (vps?.size ?: 0) > maxBytes) {
            oversizedPackets++
            recordOverflow()
            reset()
            waitingSince = now
            return
        }
        // Configuration can arrive in separate packets; never replace SPS with PPS alone.
        if (!picture) return
        // CRA may be followed in decode order by leading pictures referring to
        // pre-CRA data. A fresh decoder must not consume those RASL pictures.
        if (suppressRasl && rasl && !idr) return
        if (!rasl) suppressRasl = false
        // Surface/codec creation can take longer than the steady-state queue budget.
        // Keep that initial prediction chain briefly, then restore the normal limit.
        if (startupSince?.let { now - it >= startupWindowNanos } == true) warmingUp = false
        val frameLimit = if (warmingUp) startupMaxFrames else maxFrames
        if (frames.size >= frameLimit || data.size > maxBytes - bytes) {
            recordOverflow()
            reset(clearConfig = false)
        }
        if (needsIdr && (!idr || sps == null || pps == null || (codec == VideoCodec.HEVC && vps == null))) {
            rejectedPictures++
            if (waitingSince == null) waitingSince = now
            return
        }
        val inputSize = data.size.toLong() + if (needsIdr) sps!!.size.toLong() + pps!!.size + (vps?.size ?: 0) else 0L
        if (inputSize > maxBytes - bytes) {
            rejectedPictures++
            recordOverflow()
            reset(clearConfig = false)
            waitingSince = now
            return
        }
        if (needsIdr && codec == VideoCodec.HEVC) suppressRasl = true
        val input = if (needsIdr) (vps ?: byteArrayOf()) + sps!! + pps!! + data else data
        frames.addLast(Frame(input, now, generation, ntpLocalNanos, ntpRemoteNanos, ingressNanos, codec))
        if (startupSince == null) startupSince = now
        bytes += input.size
        peakDepth = maxOf(peakDepth, frames.size)
        peakBytes = maxOf(peakBytes, bytes)
        needsIdr = false
        waitingSince = null
    }

    @Synchronized fun peek(): Frame? = frames.peekFirst()
    @Synchronized fun consumed(frame: Frame) {
        if (frames.peekFirst() === frame) {
            frames.removeFirst()
            bytes -= frame.bytes.size
            // Do not invalidate the remaining startup frames on the first submission.
            if (frames.size < maxFrames) warmingUp = false
        }
    }
    @Synchronized fun currentGeneration(): Long = generation
    @Synchronized fun stalled(now: Long, timeout: Long): Boolean {
        val since = waitingSince ?: frames.peekFirst()?.receivedNanos ?: return false
        return now - since >= timeout
    }

    @Synchronized fun resetStatistics() {
        peakDepth = frames.size
        peakBytes = bytes
        overflowEvents = 0
        overflowDiscarded = 0
        rejectedPictures = 0
        oversizedPackets = 0
    }

    @Synchronized fun stats(now: Long): Stats = Stats(
        frames.size, bytes, frames.peekFirst()?.let { (now - it.receivedNanos).coerceAtLeast(0) } ?: 0,
        peakDepth, peakBytes, overflowEvents, overflowDiscarded, rejectedPictures, oversizedPackets
    )

    private fun recordOverflow() {
        overflowEvents++
        overflowDiscarded += frames.size
    }

    private fun startCode(data: ByteArray, from: Int): Int {
        for (i in from until data.size - 2) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 &&
                (data[i + 2].toInt() == 1 ||
                 (i + 3 < data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1))) return i
        }
        return -1
    }
}
