package io.github.pgodlews.wormhole

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** One process-wide executor owns the process-wide JNI server, including listener and Wi-Fi lock. */
internal class ServerLifecycle(private val executor: Executor) {
    class Lease(val start: () -> Unit, val stop: () -> Unit, val failed: (Exception) -> Unit) {
        val cancelled = AtomicBoolean(false)
        internal val restarting = AtomicBoolean(false)
    }

    private var owner: Lease? = null // executor confined

    fun start(lease: Lease) = executor.execute {
        if (!lease.cancelled.get()) {
            stopOwner()
            startOwner(lease)
        }
    }

    fun stop(lease: Lease) {
        lease.cancelled.set(true)
        executor.execute { if (owner === lease) stopOwner() }
    }

    fun restart(lease: Lease) {
        if (lease.cancelled.get() || !lease.restarting.compareAndSet(false, true)) return
        executor.execute {
            try {
                if (owner === lease && !lease.cancelled.get()) {
                    stopOwner()
                    startOwner(lease)
                }
            } finally {
                lease.restarting.set(false)
            }
        }
    }

    private fun startOwner(lease: Lease) {
        if (lease.cancelled.get()) return
        owner = lease
        try {
            lease.start()
            if (lease.cancelled.get()) stopOwner()
        } catch (e: Exception) {
            stopOwner()
            if (!lease.cancelled.get()) lease.failed(e)
        }
    }

    private fun stopOwner() {
        val old = owner ?: return
        owner = null
        try { old.stop() } catch (e: Exception) { old.failed(e) }
    }
}
