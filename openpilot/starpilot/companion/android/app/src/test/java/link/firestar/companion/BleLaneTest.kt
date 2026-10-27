package link.firestar.companion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The lane (BleLane.kt) with a fake link and a virtual clock: strict priority, the chunk cap from the measured rate, a stall that closes the link and fails what is
 * queued, the priority policy. The scenarios are those of `tools/btlab/contract/lane.py` and `tests/test_bt_contract_lane.py`.
 */
class BleLaneTest {
    /** Nanoseconds of virtual time, shared by the lane and the link. */
    private class Clock {
        @Volatile var ns = 0L
        fun advance(seconds: Double) { ns += (seconds * 1e9).toLong() }
        val read: () -> Long = { ns }
    }

    /** One link. [service] says how many virtual seconds a request takes; [during] runs at every slice of that time (periodic callers arrive while a request is in flight). */
    private class FakeLink(val clock: Clock) : LaneLink {
        val executed = CopyOnWriteArrayList<String>()
        val priorities = CopyOnWriteArrayList<Boolean>()
        @Volatile var closed = false
        var service: (JSONObject, ByteArray?) -> Double = { _, _ -> 0.1 }
        var gate: CountDownLatch? = null
        var fail: (JSONObject) -> Throwable? = { null }
        var during: (Double) -> Unit = {}
        override fun exchange(meta: JSONObject, body: ByteArray?, deadlineSeconds: Long): JSONObject {
            val name = meta.optString("name")
            executed += name
            gate?.takeIf { name == "gate" }?.await(10, TimeUnit.SECONDS)
            fail(meta)?.let { throw it }
            var left = service(meta, body)
            while (left > 1e-9) {
                val step = minOf(left, 0.25)
                clock.advance(step)
                left -= step
                during(clock.ns / 1e9)
            }
            return JSONObject().put("status", 200).put("name", name)
        }
        override fun control(action: String) = JSONObject().put("status", 200)
        override fun setHighPriority(on: Boolean) { priorities += on }
        override fun close() { closed = true }
    }

    private fun lane(link: FakeLink, clock: Clock, watchdog: Boolean = false) = BleLane(link, clock.read, watchdog)
    private fun request(name: String) = JSONObject().put("method", "GET").put("path", "/x").put("name", name)
    private fun BleLane.Ticket.settle(): JSONObject = future.get(10, TimeUnit.SECONDS)
    private fun BleLane.Ticket.failure(): Throwable = try { future.get(10, TimeUnit.SECONDS); throw AssertionError("expected a failure") } catch (e: ExecutionException) { e.cause!! }

    // ---- strict priority -------------------------------------------------------------------------------------------------------

    @Test fun strictPriorityAndFirstComeFirstServedInsideAClass() {
        val clock = Clock()
        val link = FakeLink(clock).apply { gate = CountDownLatch(1) }
        val lane = lane(link, clock)
        val first = lane.submit(LaneClass.INTERACTIVE, 20, request("gate"))
        while (link.executed.isEmpty()) Thread.sleep(2) // the owner thread is inside the first request now
        // a burst while the lane is busy: bulk first, then small, then two interactive ones
        val tickets = listOf(
            lane.submit(LaneClass.BULK, 30, request("b1")),
            lane.submit(LaneClass.SMALL, 10, request("s1")),
            lane.submit(LaneClass.INTERACTIVE, 20, request("i1")),
            lane.submit(LaneClass.INTERACTIVE, 20, request("i2")),
            lane.submit(LaneClass.SMALL, 10, request("s2")),
        )
        link.gate!!.countDown()
        first.settle(); tickets.forEach { it.settle() }
        assertEquals(listOf("gate", "i1", "i2", "s1", "s2", "b1"), link.executed.toList())
        lane.close()
    }

    @Test fun aRequestThatHasStartedIsNeverPreempted() {
        val clock = Clock()
        val link = FakeLink(clock).apply { gate = CountDownLatch(1) }
        val lane = lane(link, clock)
        val bulk = lane.submit(LaneClass.BULK, 30, request("gate")) // a bulk request is running
        while (link.executed.isEmpty()) Thread.sleep(2)
        val urgent = lane.submit(LaneClass.INTERACTIVE, 20, request("urgent"))
        Thread.sleep(50)
        assertEquals("the urgent request waits for the running one", listOf("gate"), link.executed.toList())
        link.gate!!.countDown()
        bulk.settle(); urgent.settle()
        assertEquals(listOf("gate", "urgent"), link.executed.toList())
        lane.close()
    }

    @Test fun callersOnOtherThreadsAreServedOneAtATimeInPriorityOrder() {
        val clock = Clock()
        val concurrent = AtomicInteger()
        val worst = AtomicInteger()
        val link = object : LaneLink {
            val delegate = FakeLink(clock)
            override fun exchange(meta: JSONObject, body: ByteArray?, deadlineSeconds: Long): JSONObject {
                worst.accumulateAndGet(concurrent.incrementAndGet(), ::maxOf)
                try { Thread.sleep(1); return delegate.exchange(meta, body, deadlineSeconds) } finally { concurrent.decrementAndGet() }
            }
            override fun control(action: String) = delegate.control(action)
            override fun setHighPriority(on: Boolean) = delegate.setHighPriority(on)
            override fun close() = delegate.close()
        }
        val lane = BleLane(link, clock.read, false)
        val threads = (0 until 12).map { n -> Thread { lane.call(LaneClass.values()[n % 3], 30, request("t$n")) } }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }
        assertEquals("one request in flight", 1, worst.get())
        assertEquals(12, link.delegate.executed.size)
        lane.close()
    }

    // ---- the chunk cap -------------------------------------------------------------------------------------------------------------

    /** A route upload in planned chunks next to the guidance poll (3 s), the state poll (15 s) and the job poll (10 s), on a link of 10 KB/s. */
    private fun uploadWithTraffic(cap: Double): Triple<List<LaneEvent>, Double, Boolean> {
        val clock = Clock()
        val link = FakeLink(clock)
        val lane = BleLane(link, clock.read, false, ChunkPlanner(cap))
        val events = CopyOnWriteArrayList<LaneEvent>()
        lane.observer = { events += it }
        link.service = { meta, body -> if (meta.optString("name") == "chunk") (body!!.size / 10_000.0) + 0.4 else 0.6 }
        val periodic = listOf(Triple("guidance", LaneClass.INTERACTIVE, 3.0), Triple("state", LaneClass.INTERACTIVE, 15.0), Triple("poll", LaneClass.SMALL, 10.0))
        val next = periodic.map { 0.0 }.toMutableList()
        link.during = { now ->
            periodic.forEachIndexed { i, (name, cls, period) ->
                while (next[i] <= now) { lane.submit(cls, 20, request(name), label = name); next[i] += period }
            }
        }
        var remaining = 200_000
        val start = clock.ns / 1e9
        var closed = false
        while (remaining > 0) {
            val size = lane.planBulkChunk(remaining)
            try { lane.call(LaneClass.BULK, 30, request("chunk"), ByteArray(size), size, "chunk") } catch (_: IOException) { closed = true; break }
            remaining -= size
        }
        val seconds = clock.ns / 1e9 - start
        lane.close()
        return Triple(events.toList(), seconds, closed)
    }

    @Test fun everyChunkRespectsTheCapOnceTheRateIsKnown() {
        val (events, _, closed) = uploadWithTraffic(5.0)
        assertFalse(closed)
        val chunks = events.filter { it.cls == LaneClass.BULK }
        assertTrue("several chunks", chunks.size >= 5)
        // the first chunk is the start size; after it the planner sizes each chunk to the cap. Chunks are one chunk time plus the small requests that jump ahead of them.
        for (chunk in chunks.drop(1)) assertTrue("chunk took ${chunk.serviceSeconds} s", chunk.serviceSeconds <= 5.0 * 1.15)
        assertTrue("sizes grow with the measured rate", chunks.size < 200_000 / 6144)
    }

    @Test fun nothingWaitsLongerThanOneChunkBehindTheUpload() {
        val (events, _, _) = uploadWithTraffic(5.0)
        val interactive = events.filter { it.cls == LaneClass.INTERACTIVE }.maxOf { it.waitSeconds }
        val small = events.filter { it.cls == LaneClass.SMALL }.maxOf { it.waitSeconds }
        assertTrue("interactive waited $interactive s", interactive <= 5.0 + 1.0)
        assertTrue("small waited $small s", small <= 5.0 + 2.0)
    }

    @Test fun aShorterCapShortensTheWaitAndCostsLittleOnTheSameLink() {
        val long = uploadWithTraffic(5.0)
        val short = uploadWithTraffic(2.0)
        assertTrue(short.first.filter { it.cls == LaneClass.INTERACTIVE }.maxOf { it.waitSeconds } <= 2.0 + 1.0)
        assertTrue("a 2 s cap costs less than a third more time", short.second < long.second * 1.35)
    }

    @Test fun theBulkRequestIsNeverStarvedByPeriodicTraffic() {
        val (events, seconds, closed) = uploadWithTraffic(5.0)
        assertFalse(closed)
        assertTrue(events.any { it.cls == LaneClass.BULK })
        assertTrue("the 200 KB route finished in $seconds s", seconds < 120)
    }

    @Test fun plannerTableAgreesWithThePythonReference() {
        fun planner(cap: Double, vararg observations: Pair<Int, Double>) = ChunkPlanner(cap).also { p -> observations.forEach { p.observe(it.first, it.second) } }
        // values computed with tools/btlab/contract/lane.py ChunkPlanner (see the dev note)
        assertEquals(listOf(5975, 5000, 500, 0), listOf(1_000_000, 5000, 500, 0).map { planner(5.0).nextBytes(it) })
        assertEquals(listOf(14818, 14818, 100), listOf(1_000_000, 20_000, 100).map { planner(5.0, 6000 to 2.0).nextBytes(it) })
        assertEquals(listOf(65486, 65486, 3000), listOf(1_000_000, 70_000, 3000).map { planner(5.0, 6000 to 2.0, 60000 to 1.0).nextBytes(it) })
        assertEquals(listOf(1024, 500, 1024, 1024, 1024, 0), listOf(1_000_000, 500, 1024, 1025, 2000, 0).map { planner(5.0, 1000 to 10.0).nextBytes(it) })
        assertEquals(14818, planner(5.0, 10 to 0.0, 6000 to 2.0).nextBytes(1_000_000))
        assertEquals(43737, planner(5.0, 6144 to 0.8144, 37720 to 5.0, 40000 to 4.0).nextBytes(1_000_000))
        assertEquals(5975, planner(2.0, 6000 to 2.0).nextBytes(1_000_000))
        assertEquals(3000.0, planner(5.0, 6000 to 2.0).rate!!, 1e-9)
    }

    @Test fun plannerStaysInsideItsBoundsForAnyRate() {
        val rng = java.util.Random(5)
        repeat(2000) {
            val p = ChunkPlanner(listOf(1.0, 2.0, 5.0, 10.0)[rng.nextInt(4)])
            repeat(rng.nextInt(4)) { p.observe(rng.nextInt(200_000) - 100, rng.nextDouble() * 20 - 1) }
            val remaining = rng.nextInt(2_000_000)
            val size = p.nextBytes(remaining)
            assertTrue(size in 0..remaining)
            if (remaining > ChunkPlanner.MIN_CHUNK) assertTrue(size in ChunkPlanner.MIN_CHUNK..ChunkPlanner.MAX_CHUNK)
            if (remaining > ChunkPlanner.MAX_CHUNK && size > ChunkPlanner.MIN_CHUNK) assertEquals(0, size % ChunkPlanner.PACKET_DATA)
        }
    }

    // ---- link loss: fail closed ------------------------------------------------------------------------------------------------------

    @Test fun aStalledRequestClosesTheLinkAndFailsWhatIsQueuedNothingIsReplayed() {
        val clock = Clock()
        val link = FakeLink(clock).apply {
            gate = CountDownLatch(1)
            // the transport's own timeout: the chunk runs 60 s of virtual time past its deadline and gives up with an IOException
            fail = { meta -> if (meta.optString("name") == "gate") IOException("Bluetooth response timed out").also { clock.advance(60.0) } else null }
        }
        val lane = lane(link, clock)
        val closedCalls = AtomicInteger()
        lane.onLinkClosed = { closedCalls.incrementAndGet() }
        val chunk = lane.submit(LaneClass.BULK, 30, request("gate"))
        while (link.executed.isEmpty()) Thread.sleep(2)
        val queued = listOf(
            lane.submit(LaneClass.INTERACTIVE, 20, request("guidance")),
            lane.submit(LaneClass.SMALL, 10, request("poll")),
            lane.submit(LaneClass.BULK, 30, request("chunk2")),
        )
        link.gate!!.countDown()
        assertTrue("the request in flight fails with the link's error", chunk.failure() is IOException)
        for (ticket in queued) assertTrue("${ticket.label} fails at once as a closed lane", ticket.failure() is LaneClosed)
        assertEquals("only the stalled request ran: nothing is replayed", listOf("gate"), link.executed.toList())
        assertTrue(link.closed)
        assertEquals(1, closedCalls.get())
        assertFalse(lane.isOpen)
        assertTrue("a new request on the closed lane fails at once", lane.submit(LaneClass.INTERACTIVE, 20, request("late")).failure() is LaneClosed)
        assertEquals(listOf("gate"), link.executed.toList())
    }

    @Test fun theWatchdogClosesALinkThatNeverAnswers() {
        val clock = Clock()
        val release = CountDownLatch(1)
        val link = object : LaneLink {
            val closedByLane = AtomicInteger()
            val ran = CopyOnWriteArrayList<String>()
            override fun exchange(meta: JSONObject, body: ByteArray?, deadlineSeconds: Long): JSONObject {
                ran += meta.optString("name")
                release.await(10, TimeUnit.SECONDS) // a GATT callback that never comes
                return JSONObject().put("status", 200)
            }
            override fun control(action: String) = JSONObject().put("status", 200)
            override fun setHighPriority(on: Boolean) = Unit
            override fun close() { closedByLane.incrementAndGet() }
        }
        val lane = BleLane(link, clock.read, false, watchdogGraceSeconds = 5)
        val reported = AtomicInteger()
        lane.onLinkClosed = { reported.incrementAndGet() }
        val stuck = lane.submit(LaneClass.INTERACTIVE, 20, request("stuck"))
        while (link.ran.isEmpty()) Thread.sleep(2)
        val waiting = lane.submit(LaneClass.SMALL, 10, request("poll"))
        clock.advance(24.0)
        lane.checkDeadline()
        assertTrue("within the deadline plus the grace nothing is closed", lane.isOpen)
        clock.advance(2.0)
        lane.checkDeadline()
        assertTrue(stuck.failure() is LaneClosed)
        assertTrue(waiting.failure() is LaneClosed)
        assertEquals(1, link.closedByLane.get())
        assertEquals(1, reported.get())
        release.countDown() // the owner thread comes back from the dead exchange: it must neither crash nor answer twice
        Thread.sleep(50)
        assertEquals(listOf("stuck"), link.ran.toList())
        lane.checkDeadline()
        assertEquals("the lane reports the loss once", 1, reported.get())
    }

    @Test fun aRequestThatWaitedLongerThanItsOwnDeadlineIsDroppedWithoutClosingTheLink() {
        val clock = Clock()
        val link = FakeLink(clock).apply { gate = CountDownLatch(1); service = { meta, _ -> if (meta.optString("name") == "gate") 0.0 else 0.1 } }
        val lane = lane(link, clock)
        val running = lane.submit(LaneClass.BULK, 30, request("gate"))
        while (link.executed.isEmpty()) Thread.sleep(2)
        val impatient = lane.submit(LaneClass.INTERACTIVE, 5, request("impatient"))
        val patient = lane.submit(LaneClass.INTERACTIVE, 50, request("patient"))
        clock.advance(8.0) // the running request held the link longer than the first caller was willing to wait
        link.gate!!.countDown()
        running.settle()
        assertTrue(impatient.failure() is LaneWaitExceeded)
        patient.settle()
        assertTrue("the link stays open", lane.isOpen && !link.closed)
        assertEquals(listOf("gate", "patient"), link.executed.toList())
        lane.close()
    }

    @Test fun closingTheLaneFailsEveryWaiterAndDoesNotCloseTheLinkAgain() {
        val clock = Clock()
        val link = FakeLink(clock).apply { gate = CountDownLatch(1) }
        val lane = lane(link, clock)
        val running = lane.submit(LaneClass.INTERACTIVE, 20, request("gate"))
        while (link.executed.isEmpty()) Thread.sleep(2)
        val queued = lane.submit(LaneClass.SMALL, 10, request("poll"))
        lane.close()
        assertTrue(queued.failure() is LaneClosed)
        assertTrue(running.failure() is LaneClosed)
        assertFalse("the owner of the link closes it, not the lane", link.closed)
        link.gate!!.countDown()
    }

    @Test fun aCallerThatIsInterruptedLeavesTheLaneUsable() {
        val clock = Clock()
        val link = FakeLink(clock).apply { gate = CountDownLatch(1) }
        val lane = lane(link, clock)
        val running = lane.submit(LaneClass.INTERACTIVE, 20, request("gate"))
        while (link.executed.isEmpty()) Thread.sleep(2)
        var caught: Throwable? = null
        val caller = Thread { try { lane.call(LaneClass.SMALL, 10, request("waiting")) } catch (e: Throwable) { caught = e } }
        caller.start(); Thread.sleep(50); caller.interrupt(); caller.join(5000)
        assertTrue(caught is IOException)
        link.gate!!.countDown()
        running.settle()
        assertEquals("the abandoned request never ran", listOf("gate"), link.executed.toList())
        lane.call(LaneClass.INTERACTIVE, 20, request("after"))
        lane.close()
    }

    @Test fun anExceptionInTheLinkThatIsNotAnIoErrorStillEndsTheLane() {
        val clock = Clock()
        val link = FakeLink(clock).apply { fail = { IllegalStateException("the transport was closed under the request") } }
        val lane = lane(link, clock)
        val ticket = lane.submit(LaneClass.INTERACTIVE, 20, request("x"))
        assertTrue(ticket.failure() is IllegalStateException)
        assertFalse(lane.isOpen)
        assertTrue(link.closed)
    }

    // ---- the connection priority policy -------------------------------------------------------------------------------------------

    @Test fun highPriorityOnlyForBulkAndJobsAndGivenBackAfterEightSecondsOfIdle() {
        val clock = Clock()
        val link = FakeLink(clock)
        val lane = lane(link, clock)
        lane.call(LaneClass.INTERACTIVE, 20, request("state"))
        lane.call(LaneClass.SMALL, 10, request("poll2"))
        lane.checkPriority()
        assertTrue("single exchanges never ask for the high priority", link.priorities.isEmpty())
        lane.call(LaneClass.BULK, 30, request("chunk"))
        assertEquals(listOf(true), link.priorities.toList())
        lane.call(LaneClass.BULK, 30, request("chunk"))
        assertEquals("one request is enough while it stays on", listOf(true), link.priorities.toList())
        clock.advance(7.0); lane.checkPriority()
        assertEquals("seven seconds of idle keep it", listOf(true), link.priorities.toList())
        clock.advance(1.5); lane.checkPriority()
        assertEquals(listOf(true, false), link.priorities.toList())
        lane.checkPriority()
        assertEquals("given back once", listOf(true, false), link.priorities.toList())
        lane.wantHigh() // a job list that is not empty
        assertEquals(listOf(true, false, true), link.priorities.toList())
        lane.close()
    }

    @Test fun priorityIsNotGivenBackWhileTheLaneIsBusy() {
        val clock = Clock()
        val link = FakeLink(clock).apply { gate = CountDownLatch(1) }
        val lane = lane(link, clock)
        lane.wantHigh()
        val busy = lane.submit(LaneClass.INTERACTIVE, 120, request("gate"))
        while (link.executed.isEmpty()) Thread.sleep(2)
        clock.advance(60.0)
        lane.checkPriority()
        assertEquals(listOf(true), link.priorities.toList())
        link.gate!!.countDown(); busy.settle()
        lane.close()
    }

    @Test fun aRefusedPriorityChangeIsNotALinkFailure() {
        val clock = Clock()
        val link = object : LaneLink {
            override fun exchange(meta: JSONObject, body: ByteArray?, deadlineSeconds: Long) = JSONObject().put("status", 200)
            override fun control(action: String) = JSONObject().put("status", 200)
            override fun setHighPriority(on: Boolean) { throw SecurityException("no permission") }
            override fun close() = Unit
        }
        val lane = BleLane(link, clock.read, false)
        lane.call(LaneClass.BULK, 30, request("chunk"))
        assertTrue(lane.isOpen)
        lane.close()
    }

    @Test fun controlOperationsTakeTheirTurnOnTheLane() {
        val clock = Clock()
        val link = FakeLink(clock).apply { gate = CountDownLatch(1) }
        val ran = CopyOnWriteArrayList<String>()
        val lane = BleLane(object : LaneLink by link {
            override fun control(action: String): JSONObject { ran += "control:$action:after=${link.executed.size}"; return JSONObject().put("status", 200) }
        }, clock.read, false)
        val running = lane.submit(LaneClass.INTERACTIVE, 20, request("gate"))
        while (link.executed.isEmpty()) Thread.sleep(2)
        val control = Thread { lane.control(LaneClass.SMALL, 12, "subscribe") }
        control.start(); Thread.sleep(50)
        assertTrue("a control operation waits for the request in flight", ran.isEmpty())
        link.gate!!.countDown(); running.settle(); control.join(5000)
        assertEquals(listOf("control:subscribe:after=1"), ran.toList())
        assertNotNull(lane)
        assertNull(null)
        lane.close()
    }
}
