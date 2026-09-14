package io.github.pgodlews.wormhole

import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class ServerLifecycleTest {
    private val tasks = ArrayDeque<Runnable>()
    private val controller = ServerLifecycle(Executor { tasks.add(it) })
    private val events = mutableListOf<String>()
    private fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    private fun lease(name: String) = ServerLifecycle.Lease(
        { events.add("start $name") }, { events.add("stop $name") }, { events.add("failed $name") }
    )

    @Test fun `destruction before scheduled startup never starts native server`() {
        val a = lease("A")
        controller.start(a); controller.stop(a); runAll()
        assertTrue(events.isEmpty())
    }

    @Test fun `retired activity shutdown cannot clear newer listener or stop newer server`() {
        val a = lease("A"); val b = lease("B")
        controller.start(a); runAll()
        controller.start(b); controller.stop(a); runAll()
        assertEquals(listOf("start A", "stop A", "start B"), events)
    }

    @Test fun `destruction during native startup cleans up exactly once`() {
        lateinit var a: ServerLifecycle.Lease
        a = ServerLifecycle.Lease({ events.add("start A"); controller.stop(a) },
            { events.add("stop A") }, { fail("unexpected error") })
        controller.start(a); runAll()
        assertEquals(listOf("start A", "stop A"), events)
    }

    @Test fun `queued recovery from old activity cannot restart a new owner`() {
        val a = lease("A"); val b = lease("B")
        controller.start(a); runAll()
        controller.start(b); controller.restart(a); runAll()
        assertEquals(listOf("start A", "stop A", "start B"), events)
    }

    @Test fun `multiple failure callbacks request only one restart`() {
        val a = lease("A")
        controller.start(a); runAll()
        controller.restart(a); controller.restart(a); runAll()
        assertEquals(listOf("start A", "stop A", "start A"), events)
    }

    @Test fun `failed startup releases listener lock and resources before reporting failure`() {
        val a = ServerLifecycle.Lease({ throw IllegalStateException() },
            { events.add("cleanup") }, { events.add("failed") })
        controller.start(a); runAll()
        assertEquals(listOf("cleanup", "failed"), events)
    }
}
