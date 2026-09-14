package io.github.pgodlews.wormhole

import java.util.ArrayDeque
import java.util.Locale

/** Bounded per-session measurements. NTP values are never subtracted from the local clock. */
internal class VideoDiagnostics(
    private val session: Long,
    private val startedNanos: Long = System.nanoTime(),
    private val sampleCapacity: Int = 512,
    private val maxPending: Int = 256
) {
    init { require(sampleCapacity > 0 && maxPending > 0) }

    internal class Samples(private val capacity: Int) {
        private val values = LongArray(capacity)
        private var next = 0
        private var size = 0
        fun add(nanos: Long) {
            if (nanos < 0) return
            values[next] = nanos
            next = (next + 1) % capacity
            size = minOf(size + 1, capacity)
        }
        fun snapshot(): LongArray = values.copyOf(size)
        fun summary(): String = summarize(snapshot())

        companion object {
            fun summarize(sorted: LongArray): String {
                if (sorted.isEmpty()) return "0,na,na"
                sorted.sort()
                fun percentile(percent: Int) = sorted[((sorted.size * percent + 99) / 100) - 1] / 1000.0
                return String.format(Locale.US, "%d,%.1f,%.1f", sorted.size, percentile(50), percentile(95))
            }
        }
    }

    private data class Pending(val pts: Long, val ingress: Long, val submitted: Long)
    private val pending = ArrayDeque<Pending>()
    private val handoff = Samples(sampleCapacity)
    private val queueAge = Samples(sampleCapacity)
    private val inputCall = Samples(sampleCapacity)
    private val decode = Samples(sampleCapacity)
    private val releaseCall = Samples(sampleCapacity)
    private val ingressToRelease = Samples(sampleCapacity)
    private var received = 0L
    private var receivedBytes = 0L
    private var peakPacketBytes = 0
    private var submitted = 0L
    private var inputRetries = 0L
    private var decoded = 0L
    private var released = 0L
    private var skipped = 0L
    private var unmatchedOutput = 0L
    private var trackingEvicted = 0L
    private var abandoned = 0L
    private var ntpLocal = 0L
    private var ntpRemote = 0L
    private var minInputCapacity = Int.MAX_VALUE
    private var maxInputCapacity = 0

    @Synchronized fun received(bytes: Int, ingress: Long, local: Long, remote: Long, now: Long) {
        received++
        receivedBytes += bytes
        peakPacketBytes = maxOf(peakPacketBytes, bytes)
        handoff.add(now - ingress)
        ntpLocal = local
        ntpRemote = remote
    }

    @Synchronized fun inputAttempt(duration: Long, capacity: Int? = null) {
        inputCall.add(duration)
        if (capacity == null) inputRetries++ else {
            minInputCapacity = minOf(minInputCapacity, capacity)
            maxInputCapacity = maxOf(maxInputCapacity, capacity)
        }
    }

    @Synchronized fun submitted(frame: VideoFrameQueue.Frame, now: Long) {
        submitted++
        queueAge.add(now - frame.receivedNanos)
        if (pending.size == maxPending) { pending.removeFirst(); trackingEvicted++ }
        pending.addLast(Pending(frame.receivedNanos / 1000, frame.ingressNanos, now))
    }

    @Synchronized fun output(pts: Long, render: Boolean, available: Long, releasedAt: Long) {
        decoded++
        if (render) released++ else skipped++
        releaseCall.add(releasedAt - available)
        // Match by PTS, not FIFO: reordered output and duplicate microsecond PTS are possible.
        val iterator = pending.iterator()
        var match: Pending? = null
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate.pts == pts) { match = candidate; iterator.remove(); break }
        }
        if (match == null) unmatchedOutput++ else {
            decode.add(available - match.submitted)
            if (render) ingressToRelease.add(releasedAt - match.ingress)
        }
    }

    @Synchronized fun codecClosed() {
        abandoned += pending.size
        pending.clear()
    }

    class Report(private val fields: String, private val samples: List<Pair<String, LongArray>>) {
        // Sorting and formatting run on the codec worker, outside receive/diagnostics locks.
        fun format(): String = fields + samples.joinToString(" ") { (name, values) -> "$name=${Samples.summarize(values)}" }
    }

    fun report(now: Long, queue: VideoFrameQueue.Stats, reason: String): String = captureReport(now, queue, reason).format()

    /** Counts are cumulative; each latency triplet is count,p50_us,p95_us for the last 512 samples. */
    @Synchronized fun captureReport(now: Long, queue: VideoFrameQueue.Stats, reason: String): Report = Report(
        "video_perf session=$session reason=$reason elapsedMs=${(now - startedNanos) / 1_000_000} " +
        "received=$received bytes=$receivedBytes peakPacketBytes=$peakPacketBytes submitted=$submitted " +
        "inputRetries=$inputRetries decoded=$decoded released=$released skipped=$skipped " +
        "pending=${pending.size} unmatchedOutput=$unmatchedOutput trackingEvicted=$trackingEvicted abandoned=$abandoned " +
        "queueDepth=${queue.depth} queueBytes=${queue.bytes} queueAgeUs=${queue.oldestAgeNanos / 1000} " +
        "peakQueueDepth=${queue.peakDepth} peakQueueBytes=${queue.peakBytes} " +
        "overflows=${queue.overflowEvents} overflowDiscarded=${queue.overflowDiscarded} " +
        "rejectedPictures=${queue.rejectedPictures} oversizedPackets=${queue.oversizedPackets} " +
        "minInputCapacity=${if (minInputCapacity == Int.MAX_VALUE) 0 else minInputCapacity} maxInputCapacity=$maxInputCapacity " +
        "ntpLocalNs=$ntpLocal ntpRemoteNs=$ntpRemote ",
        listOf("handoffUs" to handoff.snapshot(), "queueToSubmitUs" to queueAge.snapshot(),
            "inputCallUs" to inputCall.snapshot(), "submitToOutputUs" to decode.snapshot(),
            "releaseCallUs" to releaseCall.snapshot(), "ingressToReleaseUs" to ingressToRelease.snapshot())
    )
}
