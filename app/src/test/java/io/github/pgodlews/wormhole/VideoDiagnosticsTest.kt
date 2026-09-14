package io.github.pgodlews.wormhole

import org.junit.Assert.*
import org.junit.Test

class VideoDiagnosticsTest {
    private fun report(d: VideoDiagnostics) = d.report(1_000_000, VideoFrameQueue().stats(0), "test")
    private fun frame(ptsNanos: Long, ingress: Long = ptsNanos) =
        VideoFrameQueue.Frame(byteArrayOf(1), ptsNanos, 1, Long.MAX_VALUE, Long.MIN_VALUE, ingress)

    @Test fun `stage timing matches reordered output by PTS and ignores NTP clocks`() {
        val d = VideoDiagnostics(1, 0)
        d.received(1, 1_000, Long.MAX_VALUE, Long.MIN_VALUE, 3_000)
        d.submitted(frame(2_000, 1_000), 5_000)
        d.submitted(frame(3_000, 2_000), 6_000)
        d.output(3, true, 10_000, 11_000)
        d.output(2, true, 12_000, 14_000)
        val log = report(d)
        assertTrue(log, log.contains("handoffUs=1,2.0,2.0"))
        assertTrue(log, log.contains("submitToOutputUs=2,4.0,7.0"))
        assertTrue(log, log.contains("ingressToReleaseUs=2,9.0,13.0"))
        assertTrue(log, log.contains("pending=0 unmatchedOutput=0"))
    }

    @Test fun `missing output cannot grow tracking or pollute recovered decoder timings`() {
        val d = VideoDiagnostics(1, 0, maxPending = 2)
        repeat(3) { d.submitted(frame(it * 1_000L), 10_000) }
        d.output(0, true, 20_000, 21_000) // oldest tracking was evicted
        d.codecClosed()
        d.output(1, false, 30_000, 31_000) // old decoder timing is gone
        val log = report(d)
        assertTrue(log, log.contains("released=1 skipped=1 pending=0 unmatchedOutput=2 trackingEvicted=1 abandoned=2"))
        assertTrue(log, log.contains("ingressToReleaseUs=0,na,na"))
    }

    @Test fun `duplicate PTS output is tracked once per submitted buffer`() {
        val d = VideoDiagnostics(1, 0)
        d.submitted(frame(1_000), 2_000)
        d.submitted(frame(1_001), 3_000)
        repeat(2) { d.output(1, true, 5_000, 6_000) }
        assertTrue(report(d).contains("pending=0 unmatchedOutput=0"))
    }

    @Test fun `percentiles retain only bounded recent samples and reject negative durations`() {
        val samples = VideoDiagnostics.Samples(3)
        listOf(100_000L, 1_000, 3_000, -1, 2_000).forEach(samples::add)
        assertEquals("3,2.0,3.0", samples.summary())
    }

    @Test fun `input retries are distinct from submissions and report real capacity`() {
        val d = VideoDiagnostics(1, 0)
        d.inputAttempt(500)
        d.inputAttempt(1_000, 4096)
        d.inputAttempt(2_000, 8192)
        val log = report(d)
        assertTrue(log, log.contains("submitted=0 inputRetries=1"))
        assertTrue(log, log.contains("minInputCapacity=4096 maxInputCapacity=8192"))
        assertTrue(log, log.contains("inputCallUs=3,1.0,2.0"))
    }

    @Test fun `new diagnostics instance cannot inherit previous session measurements`() {
        val old = VideoDiagnostics(1, 0)
        old.submitted(frame(1_000), 2_000)
        val current = VideoDiagnostics(2, 0)
        old.output(1, true, 3_000, 4_000)
        val log = report(current)
        assertTrue(log, log.contains("session=2"))
        assertTrue(log, log.contains("submitted=0 inputRetries=0 decoded=0 released=0"))
    }
}
