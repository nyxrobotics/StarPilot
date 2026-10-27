package link.firestar.companion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The class `map` of the REAL [BleLane] on a virtual clock (docs/dev/bluetooth-map-contract.md 2.6 and 7.2 step 3): the six scripts of `lane-map.json`, a few hundred random workloads
 * against an independent model of the contract's scheduling (strict priority interactive > small > route > map, first come first served inside a class, a started request is never
 * preempted, all arrivals of one instant are in the queue before the next request starts), the bound "a map chunk delays anything else by at most one chunk", the planners of the classes
 * and the priority policy. Times are whole milliseconds, so the model and the lane agree exactly.
 */
class BleLaneMapTest {
    /** One request of a workload: class, arrival time and time on the link, in milliseconds of the virtual clock. */
    private data class Req(val name: String, val cls: LaneClass, val atMs: Long, val serviceMs: Long)

    private class Outcome(val startOrder: List<String>, val waitMs: Map<String, Long>, val priorities: List<Boolean>)

    private fun classOf(name: String) = when (name) { "interactive" -> LaneClass.INTERACTIVE; "small" -> LaneClass.SMALL; "bulk" -> LaneClass.BULK; "map" -> LaneClass.MAP; else -> error(name) }
    private fun ms(seconds: Double) = Math.round(seconds * 1000.0)

    /** Runs a workload through a real lane on a virtual clock: the link takes the scripted time, later arrivals are submitted at their exact virtual time, one instant's arrivals go in together. */
    private fun run(workload: List<Req>): Outcome {
        val lock = Any()
        val clock = object { @Volatile var ns = 0L }
        val executed = CopyOnWriteArrayList<String>()
        val priorities = CopyOnWriteArrayList<Boolean>()
        val pending = ArrayList(workload.sortedBy { it.atMs }) // a stable sort: the order of the list breaks ties
        val tickets = CopyOnWriteArrayList<BleLane.Ticket>()
        val waits = HashMap<String, Long>()
        var gate = CountDownLatch(0)
        lateinit var lane: BleLane

        fun submitDue(nowMs: Long) {
            synchronized(lock) {
                while (pending.isNotEmpty() && pending.first().atMs <= nowMs) {
                    val r = pending.removeAt(0)
                    tickets += lane.submit(r.cls, 3600, JSONObject().put("method", "POST").put("path", "/x").put("name", r.name).put("service_ms", r.serviceMs), label = r.name)
                }
            }
        }

        val link = object : LaneLink {
            override fun exchange(meta: JSONObject, body: ByteArray?, deadlineSeconds: Long): JSONObject {
                val name = meta.getString("name")
                if (name == "gate") { executed += "gate"; gate.await(10, TimeUnit.SECONDS); return JSONObject().put("status", 200) }
                executed += name
                var left = meta.getLong("service_ms")
                var nowMs = clock.ns / 1_000_000
                while (left > 0) {
                    val next = synchronized(lock) { pending.firstOrNull()?.atMs }
                    val step = if (next != null && next > nowMs) minOf(left, next - nowMs) else left
                    clock.ns += step * 1_000_000; left -= step; nowMs += step
                    submitDue(nowMs)
                }
                return JSONObject().put("status", 200)
            }
            override fun control(action: String) = JSONObject().put("status", 200)
            override fun setHighPriority(on: Boolean) { priorities += on }
            override fun close() = Unit
        }
        lane = BleLane(link, { clock.ns }, false)
        lane.observer = { event -> if (event.label != "gate") synchronized(waits) { waits[event.label] = Math.round(event.waitSeconds * 1000.0) } }
        try {
            while (true) {
                val nextAt = synchronized(lock) { pending.firstOrNull()?.atMs } ?: break
                clock.ns = maxOf(clock.ns, nextAt * 1_000_000)
                // the lane is idle: hold its owner in a zero-time request while the arrivals of this instant go in, so that they are ordered by priority and not by who came first
                gate = CountDownLatch(1)
                val before = executed.size
                val gateTicket = lane.submit(LaneClass.INTERACTIVE, 3600, JSONObject().put("method", "POST").put("path", "/x").put("name", "gate"), label = "gate")
                val begun = System.nanoTime()
                while (executed.size == before && System.nanoTime() - begun < 10_000_000_000L) Thread.sleep(1)
                submitDue(clock.ns / 1_000_000)
                gate.countDown()
                gateTicket.future.get(10, TimeUnit.SECONDS)
                // everything submitted so far, and what the link submits while it runs, ends before the clock moves on
                while (true) {
                    val snapshot = tickets.toList()
                    snapshot.forEach { it.future.get(30, TimeUnit.SECONDS) }
                    if (tickets.size == snapshot.size) break
                }
            }
        } finally { lane.close() }
        return Outcome(executed.filter { it != "gate" }, synchronized(waits) { HashMap(waits) }, priorities.toList())
    }

    /** The contract's scheduling as a plain model (lane.py's rules), independent of the lane's code: what the lane must give for any workload. */
    private fun model(workload: List<Req>): Pair<List<String>, Map<String, Long>> {
        val sorted = workload.sortedBy { it.atMs }
        val queue = ArrayList<Req>()
        val order = ArrayList<String>(); val waits = HashMap<String, Long>()
        var now = 0L; var index = 0
        while (order.size < sorted.size) {
            while (index < sorted.size && sorted[index].atMs <= now) queue += sorted[index++]
            if (queue.isEmpty()) { now = sorted[index].atMs; continue }
            val pick = queue.withIndex().minWith(compareBy({ it.value.cls.ordinal }, { sorted.indexOf(it.value) })).value
            queue.remove(pick)
            order += pick.name; waits[pick.name] = now - pick.atMs
            now += pick.serviceMs
        }
        return order to waits
    }

    // ---- the six scripts of lane-map.json ---------------------------------------------------------------------------------------------

    @Test fun theSixLaneScriptsOfTheVectorsRunThroughTheRealLane() {
        val scripts = (TestVectors.file("lane-map.json")["lane_scripts"] as List<*>).map { it as StrictJson.Obj }
        assertEquals(6, scripts.size)
        for (script in scripts) {
            val requests = (script["requests"] as List<*>).map { row ->
                val r = row as List<*>
                Req(r[0] as String, classOf(r[1] as String), ms((r[2] as StrictJson.Num).text.toDouble()), ms((r[3] as StrictJson.Num).text.toDouble()))
            }
            val outcome = run(requests)
            val name = script["name"] as String
            assertEquals(name, (script["expect_start_order"] as List<*>).map { it as String }, outcome.startOrder)
            (script["expect_wait_s"] as? StrictJson.Obj)?.members?.forEach { (request, seconds) ->
                assertEquals("$name: wait of $request", ms((seconds as StrictJson.Num).text.toDouble()), outcome.waitMs[request])
            }
            val (modelOrder, modelWaits) = model(requests)
            assertEquals("$name: the model of the contract agrees with the vector", modelOrder, outcome.startOrder)
            assertEquals("$name: waits", modelWaits, outcome.waitMs)
        }
    }

    // ---- random workloads against the model ------------------------------------------------------------------------------------------

    private fun randomWorkload(seed: Long): List<Req> {
        val rng = Random(seed)
        val classes = LaneClass.values()
        val horizon = 6_000L
        val count = 6 + rng.nextInt(30)
        return (0 until count).map { i ->
            val cls = classes[rng.nextInt(classes.size)]
            // a map chunk is at most one second of the link (the planner's cap), the others are short; arrivals on a 50 ms grid, many at the same instant
            val service = when (cls) { LaneClass.MAP -> 200L + 50 * rng.nextInt(17); LaneClass.BULK -> 300L + 50 * rng.nextInt(95); else -> 50L + 50 * rng.nextInt(8) }
            Req("r$i-${cls.name.lowercase()}", cls, 50L * rng.nextInt((horizon / 50).toInt()), service)
        }
    }

    @Test fun twoHundredRandomWorkloadsRunExactlyAsTheContractsModelSays() {
        for (seed in 1L..200L) {
            val workload = randomWorkload(seed)
            val outcome = run(workload)
            val (order, waits) = model(workload)
            assertEquals("seed $seed: the order in which the requests started", order, outcome.startOrder)
            assertEquals("seed $seed: how long each waited", waits, outcome.waitMs)
        }
    }

    @Test fun aMapChunkDelaysAnythingElseByAtMostOneChunk() {
        var checked = 0
        for (seed in 1000L..1150L) {
            val workload = randomWorkload(seed)
            val outcome = run(workload)
            val startedAt = workload.associate { it.name to it.atMs + outcome.waitMs.getValue(it.name) }
            val maps = workload.filter { it.cls == LaneClass.MAP }
            // The time a request spends waiting behind map requests: the overlap of its wait [arrival, start) with the time of every map request on the link. It is the rest of the ONE map
            // chunk that was in flight when it arrived: no map request starts while anything else is queued (strict priority), and a chunk is at most one second of the link (the planner's cap).
            // (A comparison with the same workload without map traffic would not hold for the route: a map chunk can make a long route chunk start later and so let later requests overtake it.)
            for (r in workload.filter { it.cls != LaneClass.MAP }) {
                val from = r.atMs; val to = startedAt.getValue(r.name)
                val overlaps = maps.map { m -> val s = startedAt.getValue(m.name); minOf(to, s + m.serviceMs) - maxOf(from, s) }.filter { it > 0 }
                assertTrue("seed $seed: ${r.name} (${r.cls}) waited behind ${overlaps.size} map requests", overlaps.size <= 1)
                assertTrue("seed $seed: ${r.name} waited ${overlaps.sum()} ms behind map traffic, a chunk is at most 1,000 ms", overlaps.sum() <= 1000)
                checked++
            }
            // a map request starts only when nothing else is queued: every non-map request that arrived no later than it started had started before
            for (m in maps) for (o in workload.filter { it.cls != LaneClass.MAP }) {
                if (o.atMs <= startedAt.getValue(m.name) && startedAt.getValue(o.name) > startedAt.getValue(m.name))
                    throw AssertionError("seed $seed: map request ${m.name} started at ${startedAt[m.name]} ms while ${o.name} (${o.cls}), queued since ${o.atMs} ms, had not")
            }
        }
        assertTrue("the workloads held enough requests", checked > 1500)
    }

    // ---- the planners and the policy -------------------------------------------------------------------------------------------------

    private class QuietLink : LaneLink {
        val priorities = CopyOnWriteArrayList<Boolean>()
        @Volatile var serviceNs = 0L
        @Volatile var clock: (Long) -> Unit = {}
        override fun exchange(meta: JSONObject, body: ByteArray?, deadlineSeconds: Long): JSONObject { clock(serviceNs); return JSONObject().put("status", 200) }
        override fun control(action: String) = JSONObject().put("status", 200)
        override fun setHighPriority(on: Boolean) { priorities += on }
        override fun close() = Unit
    }

    @Test fun everyClassMeasuresItsOwnRateAndPlansItsOwnChunk() {
        val now = object { @Volatile var ns = 0L }
        val link = QuietLink().apply { clock = { now.ns += it } }
        val lane = BleLane(link, { now.ns }, false)
        try {
            val req = JSONObject().put("method", "POST").put("path", "/x")
            // a map chunk of 12,000 bytes in one second: 12 KB/s, so the next map chunk is 12,000 bytes (50 packets of 239 are 11,950)
            link.serviceNs = 1_000_000_000L
            lane.call(LaneClass.MAP, 30, req, ByteArray(12_000), 12_000, "chunk")
            assertEquals(11_950, lane.planChunk(LaneClass.MAP, 1_000_000))
            assertNull("the route has measured nothing", lane.planner.rate)
            assertEquals("and plans from its own start", 5975, lane.planChunk(LaneClass.BULK, 1_000_000))
            // a route chunk of 50,000 bytes in two seconds: 25 KB/s times 5 s is 125 KB, capped at 64 KiB
            link.serviceNs = 2_000_000_000L
            lane.call(LaneClass.BULK, 30, req, ByteArray(50_000), 50_000, "chunk")
            assertEquals(65_486, lane.planChunk(LaneClass.BULK, 1_000_000))
            assertEquals("the map planner did not move", 11_950, lane.planChunk(LaneClass.MAP, 1_000_000))
            // the answer of a search: 6,000 bytes in half a second is 12 KB/s, one second of it is 11,950
            link.serviceNs = 500_000_000L
            lane.call(LaneClass.SMALL, 30, req, ByteArray(6_000), 6_000, "chunk")
            assertEquals(11_950, lane.planChunk(LaneClass.SMALL, 1_000_000))
            // a small request that is not a chunk (the poll, the failure) says nothing about the rate
            lane.call(LaneClass.SMALL, 10, req, label = "poll2")
            assertEquals(11_950, lane.planChunk(LaneClass.SMALL, 1_000_000))
            lane.call(LaneClass.INTERACTIVE, 10, req, ByteArray(6_000), 6_000)
            assertEquals("an interactive request is not a chunk", 11_950, lane.planChunk(LaneClass.SMALL, 1_000_000))
        } finally { lane.close() }
    }

    @Test fun aSlowMapLinkNeverGivesAChunkBelow4096BytesSoEveryChunkIsASampleOfTheMeter() {
        val now = object { @Volatile var ns = 0L }
        val link = QuietLink().apply { clock = { now.ns += it }; serviceNs = 4_000_000_000L }
        val lane = BleLane(link, { now.ns }, false)
        try {
            val req = JSONObject().put("method", "POST").put("path", "/x")
            lane.call(LaneClass.MAP, 30, req, ByteArray(4_096), 4_096, "chunk") // 1 KB/s
            assertEquals(4_096, lane.planChunk(LaneClass.MAP, 1_000_000))
            repeat(5) { lane.call(LaneClass.MAP, 30, req, ByteArray(4_096), 4_096, "chunk"); assertTrue(lane.planChunk(LaneClass.MAP, 1_000_000) >= 4_096) }
            assertEquals("the last chunk of a job is the rest, however small", 700, lane.planChunk(LaneClass.MAP, 700))
        } finally { lane.close() }
    }

    @Test fun aMapChunkAsksForTheHighPriorityLikeARouteChunkAndTheLaneGivesItBackWhenIdle() {
        val now = object { @Volatile var ns = 0L }
        val link = QuietLink()
        val lane = BleLane(link, { now.ns }, false)
        try {
            val req = JSONObject().put("method", "POST").put("path", "/x")
            lane.call(LaneClass.SMALL, 10, req); lane.call(LaneClass.INTERACTIVE, 10, req)
            assertTrue("a single exchange never asks for the high priority", link.priorities.isEmpty())
            lane.call(LaneClass.MAP, 30, req, ByteArray(4_096), 4_096, "chunk")
            assertEquals(listOf(true), link.priorities.toList())
            lane.call(LaneClass.MAP, 30, req, ByteArray(4_096), 4_096, "chunk")
            assertEquals("asked once while it is on", listOf(true), link.priorities.toList())
            now.ns += 7_000_000_000L; lane.checkPriority()
            assertEquals("kept for eight seconds without a request", listOf(true), link.priorities.toList())
            now.ns += 2_000_000_000L; lane.checkPriority()
            assertEquals("given back after eight seconds of idle", listOf(true, false), link.priorities.toList())
        } finally { lane.close() }
    }

    @Test fun aMapChunkThatStallsPastItsDeadlineClosesTheLinkAndFailsEverythingQueuedAndNothingIsReplayed() {
        val now = object { @Volatile var ns = 0L }
        val release = CountDownLatch(1)
        val executed = CopyOnWriteArrayList<String>()
        val link = object : LaneLink {
            @Volatile var closed = false
            override fun exchange(meta: JSONObject, body: ByteArray?, deadlineSeconds: Long): JSONObject { executed += meta.optString("name"); release.await(10, TimeUnit.SECONDS); throw java.io.IOException("stalled") }
            override fun control(action: String) = JSONObject().put("status", 200)
            override fun setHighPriority(on: Boolean) = Unit
            override fun close() { closed = true }
        }
        val lane = BleLane(link, { now.ns }, false)
        val chunk = lane.submit(LaneClass.MAP, 30, JSONObject().put("method", "POST").put("path", "/x").put("name", "m1"), ByteArray(4_096), 4_096, "chunk")
        while (executed.isEmpty()) Thread.sleep(1)
        val waiting = lane.submit(LaneClass.MAP, 30, JSONObject().put("method", "POST").put("path", "/x").put("name", "m2"), ByteArray(4_096), 4_096, "chunk")
        val small = lane.submit(LaneClass.SMALL, 10, JSONObject().put("method", "GET").put("path", "/y").put("name", "s1"))
        now.ns += 36_000_000_000L // 30 s deadline plus the 5 s grace
        lane.checkDeadline()
        release.countDown()
        for (ticket in listOf(chunk, waiting, small)) assertTrue(runCatching { ticket.await() }.isFailure)
        assertFalse(lane.isOpen)
        assertEquals("nothing was replayed", listOf("m1"), executed.toList())
        assertTrue(link.closed)
    }
}
