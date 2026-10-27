package link.firestar.companion

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The job worker (SidecarJobs.kt) against comma's broker as the contract describes it: the three kinds, raw chunks, the result with its status, resume, stale and hostile jobs. */
class SidecarJobsTest {
    private val route = "/directions/v5/mapbox/driving-traffic/139.7671,35.6812;139.7016,35.6580?access_token=SECRETTOKEN123&steps=true"
    private val lanes = ArrayList<BleLane>()
    private val warnings = CopyOnWriteArrayList<String>()

    @After fun closeLanes() { lanes.forEach { it.close() } }

    private fun lane(link: LaneLink) = BleLane(link, System::nanoTime, false).also { lanes += it }

    private class FakeFetcher(val answers: MutableMap<String, () -> LocalAnswer> = HashMap()) : LocalFetcher {
        val fetched = CopyOnWriteArrayList<String>()
        override fun fetch(shape: Shape, target: String): LocalAnswer {
            fetched += target
            return (answers[target] ?: answers[shape.wire] ?: throw AssertionError("fetch of an unexpected target"))()
        }
    }

    private fun jobs(lane: () -> BleLane?, fetcher: LocalFetcher, xz: Boolean = false, workers: Executor = Executor { it.run() }) =
        SidecarJobs(lane, fetcher, { xz }, System::nanoTime, { warnings += it }, workers, null)

    /** A route answer: JSON text with random digits, about [size] bytes, so that gzip does not make it small. */
    private fun routeBody(size: Int, seed: Long = 1): ByteArray {
        val rng = Random(seed)
        val text = StringBuilder("{\"routes\":[{\"geometry\":{\"coordinates\":[")
        while (text.length < size) text.append('[').append(139 + rng.nextDouble()).append(',').append(35 + rng.nextDouble()).append("],")
        return text.append("[0,0]]}}],\"code\":\"Ok\"}").toString().toByteArray()
    }

    private fun json(text: String) = text.toByteArray()

    // ---- the three kinds, status and encoding ---------------------------------------------------------------------------------------

    @Test fun aRouteJobArrivesCompleteVerifiedAndAsRawChunksSizedByThePlanner() {
        val body = routeBody(400_000)
        val comma = FakeComma(); val job = comma.submit(Shape.ROUTE, route)
        val link = CommaLink(comma); val lane = lane(link)
        val fetcher = FakeFetcher(mutableMapOf(route to { LocalAnswer(200, body) }))
        jobs({ lane }, fetcher).pollOnce(lane)
        assertEquals(listOf(route), fetcher.fetched.toList())
        assertTrue(comma.rejected.toString(), comma.rejected.isEmpty())
        val (status, decoded) = job.result!!
        assertEquals(200, status)
        assertEquals("gzip", job.encoding)
        assertArrayEquals("the route as the X10 served it, byte for byte", body, decoded)
        assertTrue("several chunks", job.chunks.size >= 3)
        assertEquals("the first chunk is the planner's start size in whole packets", 5975, job.chunks[0].second)
        assertTrue(job.chunks.all { it.second <= JobContract.MAX_CHUNK_BYTES })
        assertEquals((0 until job.chunks.size).toList(), job.chunks.map { it.first })
        assertTrue("the poll asked for the high priority", link.priorities.contains(true))
    }

    @Test fun theFallbackSendsXzWhichCommaDecodesToTheSameBytes() {
        val body = routeBody(120_000, 2)
        val comma = FakeComma(); val job = comma.submit(Shape.ROUTE, route)
        val lane = lane(CommaLink(comma))
        jobs({ lane }, FakeFetcher(mutableMapOf(route to { LocalAnswer(200, body) })), xz = true).pollOnce(lane)
        assertEquals("xz", job.encoding)
        assertArrayEquals(body, job.result!!.second)
        assertTrue(comma.rejected.isEmpty())
        val gzipSize = JobContract.encode("route", 200, body, false).payload.size
        assertTrue("xz is smaller than gzip for this route", job.data.size() < gzipSize)
    }

    @Test fun aStatusAndAHealthJobGoAsIdentityInOneSmallChunk() {
        val comma = FakeComma()
        val status = comma.submit(Shape.STATUS, "/status"); val health = comma.submit(Shape.HEALTH, "/health")
        val lane = lane(CommaLink(comma))
        val fetcher = FakeFetcher(mutableMapOf("/status" to { LocalAnswer(200, json("{\"service\":\"openpilot-sidecar\"}")) }, "/health" to { LocalAnswer(200, json("{}")) }))
        jobs({ lane }, fetcher).pollOnce(lane)
        assertEquals("identity", status.encoding); assertEquals("identity", health.encoding)
        assertEquals("{\"service\":\"openpilot-sidecar\"}", String(status.result!!.second))
        assertEquals("{}", String(health.result!!.second))
        assertEquals(1, status.chunks.size)
        assertTrue(comma.rejected.isEmpty())
    }

    @Test fun theStatusOfTheX10AnswerCrossesWithItsBody() {
        val comma = FakeComma(); val job = comma.submit(Shape.ROUTE, route)
        val lane = lane(CommaLink(comma))
        val answer = json("{\"error\":\"RouteAwaitingApproval\"}")
        jobs({ lane }, FakeFetcher(mutableMapOf(route to { LocalAnswer(503, answer) }))).pollOnce(lane)
        assertEquals(503, job.result!!.first)
        assertEquals("identity", job.encoding)
        assertArrayEquals(answer, job.result!!.second)
    }

    @Test fun everyListedStatusCrossesAndOtherCodesFailTheJob() {
        for (code in JobContract.ALLOWED_STATUS) {
            val comma = FakeComma(); val job = comma.submit(Shape.STATUS, "/status")
            val lane = lane(CommaLink(comma))
            jobs({ lane }, FakeFetcher(mutableMapOf("/status" to { LocalAnswer(code, if (code == 204) ByteArray(0) else json("{\"c\":$code}")) }))).pollOnce(lane)
            assertEquals("status $code", code, job.result?.first)
        }
        for (code in listOf(201, 301, 302, 304, 405, 500, 502)) {
            val comma = FakeComma(); val job = comma.submit(Shape.STATUS, "/status")
            val lane = lane(CommaLink(comma))
            jobs({ lane }, FakeFetcher(mutableMapOf("/status" to { LocalAnswer(code, json("{}")) }))).pollOnce(lane)
            assertNull("status $code is not carried", job.result)
            assertTrue("the job is failed, not left to time out", job.failed)
        }
    }

    @Test fun emptyOversizeAndUnfetchableAnswersFailTheJobAndNothingPartialIsSent() {
        val cases = mapOf<String, () -> LocalAnswer>(
            "empty 200" to { LocalAnswer(200, ByteArray(0)) },
            "status body over 4 KiB" to { LocalAnswer(200, ByteArray(4097) { 65 }) },
            "rejected by the fetcher" to { throw RouteFetchRejected("redirect", 302) },
        )
        for ((name, answer) in cases) {
            val comma = FakeComma(); val job = comma.submit(Shape.STATUS, "/status")
            val lane = lane(CommaLink(comma))
            jobs({ lane }, FakeFetcher(mutableMapOf("/status" to answer))).pollOnce(lane)
            assertTrue(name, job.failed)
            assertNull(name, job.result)
            assertEquals(name, 0, job.chunks.size)
        }
    }

    @Test fun aFetchThatGotNoAnswerKeepsTheJobOpenAndTheNextPollFetchesAgain() {
        val comma = FakeComma(); val job = comma.submit(Shape.ROUTE, route)
        val lane = lane(CommaLink(comma))
        var attempts = 0
        val fetcher = FakeFetcher(mutableMapOf(route to { if (attempts++ == 0) throw RouteFetchIo("response", "SocketException", java.net.SocketException("reset")) else LocalAnswer(200, routeBody(3000)) }))
        val worker = jobs({ lane }, fetcher)
        worker.pollOnce(lane)
        assertFalse(job.failed); assertNull(job.result); assertEquals(0, job.chunks.size)
        worker.pollOnce(lane)
        assertNotNull(job.result)
        assertEquals(2, fetcher.fetched.size)
    }

    @Test fun aJobThatStaysOpenDoesNotMakeATightPollingLoop() {
        val comma = FakeComma(); comma.submit(Shape.ROUTE, route)
        val lane = lane(CommaLink(comma))
        val fetcher = FakeFetcher(mutableMapOf(route to { throw RouteFetchIo("response", "ConnectException", java.net.ConnectException("refused")) }))
        val worker = jobs({ lane }, fetcher)
        repeat(3) { worker.pollOnce(lane) }
        assertEquals("one fetch per poll, at the comma's cadence", 3, fetcher.fetched.size)
        assertEquals("a job that stays open never asks for an immediate poll", 0, worker.pollRequests.get())
        val done = FakeComma(); done.submit(Shape.STATUS, "/status")
        val laneB = lane(CommaLink(done))
        val fine = jobs({ laneB }, FakeFetcher(mutableMapOf("/status" to { LocalAnswer(200, json("{}")) })))
        fine.pollOnce(laneB)
        assertEquals("a job that reached its end asks for the next poll at once", 1, fine.pollRequests.get())
    }

    @Test fun aLinkLossDuringAnUploadDoesNotAskForAnImmediatePoll() {
        val comma = FakeComma(); comma.submit(Shape.ROUTE, route)
        val lane = lane(CommaLink(comma).apply { failAfterChunk = 0 })
        val worker = jobs({ lane }, FakeFetcher(mutableMapOf(route to { LocalAnswer(200, routeBody(300_000, 11)) })))
        worker.pollOnce(lane)
        assertEquals(0, worker.pollRequests.get())
    }

    // ---- a small job while a route is mid-upload ---------------------------------------------------------------------------------------

    @Test fun aStatusJobCompletesWhileARouteIsMidUpload() {
        val comma = FakeComma(); val link = CommaLink(comma).apply { chunkDelayMs = 40 }
        val lane = lane(link)
        val routeJob = comma.submit(Shape.ROUTE, route)
        val fetcher = FakeFetcher(mutableMapOf(route to { LocalAnswer(200, routeBody(600_000, 3)) }, "/status" to { LocalAnswer(200, json("{\"ok\":true}")) }))
        val pool = Executors.newCachedThreadPool()
        try {
            val worker = jobs({ lane }, fetcher, workers = pool)
            worker.pollOnce(lane)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (routeJob.next < 2 && System.nanoTime() < deadline) Thread.sleep(2)
            assertTrue("the route is mid-upload", routeJob.next in 2..10 && !routeJob.finished)
            val statusJob = comma.submit(Shape.STATUS, "/status")
            worker.pollOnce(lane) // the next poll lists the status job while the route job is claimed by this phone and running
            while (!statusJob.finished && System.nanoTime() < deadline) Thread.sleep(2)
            assertTrue("the status job is done", statusJob.finished)
            assertFalse("while the route is still being uploaded", routeJob.finished)
            while (!routeJob.finished && System.nanoTime() < deadline) Thread.sleep(2)
            assertTrue(routeJob.finished)
            assertEquals("the route job was not started twice", 1, fetcher.fetched.count { it == route })
            val order = comma.calls.filter { it.startsWith("chunk:") }
            assertTrue("the small chunk sits between route chunks", order.indexOf("chunk:status:0:last") in 1 until order.size - 1)
        } finally { pool.shutdownNow() }
    }

    // ---- resume at the comma's cursor, stale and hostile jobs -----------------------------------------------------------------------------

    private fun assembleCheck(comma: FakeComma, job: FakeComma.Job, body: ByteArray) {
        assertTrue(comma.rejected.toString(), comma.rejected.isEmpty())
        assertArrayEquals("complete and verified", body, job.result!!.second)
        assertEquals("the chunk numbers are contiguous", (0 until job.chunks.size).toList(), job.chunks.map { it.first })
    }

    @Test fun afterALinkLossTheUploadResumesAtTheCommasCursorWithoutAFreshFetch() {
        val body = routeBody(500_000, 4)
        for (lostAck in listOf(true, false)) {
            val comma = FakeComma(); val job = comma.submit(Shape.ROUTE, route)
            val first = CommaLink(comma).apply { if (lostAck) failAfterChunk = 2 else failBeforeChunk = 2 }
            val laneA = lane(first)
            val fetcher = FakeFetcher(mutableMapOf(route to { LocalAnswer(200, body) }))
            val worker = jobs({ laneA }, fetcher)
            worker.pollOnce(laneA)
            assertFalse("the lane closed with the link", laneA.isOpen)
            assertNull(job.result)
            // the cursor says what comma holds: chunk 2 was committed in one case and not in the other
            assertEquals(if (lostAck) 3 else 2, job.next)
            val laneB = lane(CommaLink(comma)) // the reconnect: a new link and a new lane, the same worker
            worker.pollOnce(laneB)
            assembleCheck(comma, job, body)
            assertEquals("the route was fetched once", 1, fetcher.fetched.size)
        }
    }

    @Test fun repeatedLinkLossesStillEndInOneCompleteRoute() {
        val body = routeBody(450_000, 6)
        val comma = FakeComma(); val job = comma.submit(Shape.ROUTE, route)
        val fetcher = FakeFetcher(mutableMapOf(route to { LocalAnswer(200, body) }))
        var current = lane(CommaLink(comma).apply { failAfterChunk = 0 })
        val worker = jobs({ current }, fetcher)
        var rounds = 0
        while (!job.finished && rounds++ < 10) {
            worker.pollOnce(current)
            current = lane(CommaLink(comma).apply { if (rounds % 2 == 1) failBeforeChunk = job.next + 1 else failAfterChunk = job.next + 1 })
        }
        assembleCheck(comma, job, body)
        assertEquals(1, fetcher.fetched.size)
    }

    @Test fun aJobThatLostItsBytesIsFailedNotFetchedAgainAndSplicedOn() {
        val comma = FakeComma(); val job = comma.submit(Shape.ROUTE, route)
        job.next = 3 // comma holds three chunks of a transfer that a Companion process started and lost with its memory
        val lane = lane(CommaLink(comma))
        val fetcher = FakeFetcher()
        jobs({ lane }, fetcher).pollOnce(lane)
        assertEquals(emptyList<String>(), fetcher.fetched.toList())
        assertTrue(job.failed)
        assertTrue(comma.calls.contains("fail:route"))
    }

    @Test fun aJobCommaHasEndedStopsTheUploadAndTheBytesAreForgotten() {
        val body = routeBody(500_000, 7)
        val comma = FakeComma(); val job = comma.submit(Shape.ROUTE, route)
        val link = CommaLink(comma)
        val lane = lane(link)
        val fetcher = FakeFetcher(mutableMapOf(route to { LocalAnswer(200, body) }))
        // the job's deadline passes (or its caller gives up) after the first chunk: comma answers 404 from then on
        val expiring = object : LaneLink by link {
            override fun exchange(meta: JSONObject, body: ByteArray?, deadlineSeconds: Long): JSONObject {
                if (comma.calls.contains("chunk:route:0")) comma.expired += job.id
                return link.exchange(meta, body, deadlineSeconds)
            }
        }
        val laneB = lane(expiring)
        jobs({ laneB }, fetcher).pollOnce(laneB)
        assertEquals("one chunk was accepted, the next got 404 and the Companion stopped", listOf("chunk:route:0"), comma.calls.filter { it.startsWith("chunk") })
        assertNull(job.result)
        assertTrue(lane.isOpen) // a 404 is an answer, not a link failure
        assertTrue(warnings.any { it.startsWith("Job gone at comma") })
    }

    @Test fun aTransferForAJobThatIsNoLongerListedIsDroppedAtTheNextPoll() {
        val body = routeBody(300_000, 8)
        val comma = FakeComma(); val job = comma.submit(Shape.ROUTE, route)
        val lane1 = lane(CommaLink(comma).apply { failAfterChunk = 0 })
        val fetcher = FakeFetcher(mutableMapOf(route to { LocalAnswer(200, body) }))
        val worker = jobs({ lane1 }, fetcher)
        worker.pollOnce(lane1)
        comma.expired += job.id // comma ended the job: the next list no longer has it
        val lane2 = lane(CommaLink(comma))
        worker.pollOnce(lane2)
        // a new job with the same target gets a fresh fetch: nothing of the old bytes is reused
        val again = comma.submit(Shape.ROUTE, route)
        worker.pollOnce(lane2)
        assertEquals(2, fetcher.fetched.size)
        assertArrayEquals(body, again.result!!.second)
    }

    @Test fun aJobHeldByAnotherPhoneIsNotTouched() {
        val comma = FakeComma().apply { claimedByAnotherPhone = true }
        val job = comma.submit(Shape.ROUTE, route)
        val lane = lane(CommaLink(comma))
        val fetcher = FakeFetcher()
        jobs({ lane }, fetcher).pollOnce(lane)
        assertTrue(fetcher.fetched.isEmpty())
        assertEquals(0, job.next)
        assertFalse(job.failed)
    }

    @Test fun onlyTheFourShapesAreFetchedWhateverTheCommaAsks() {
        val hostile = listOf(
            Shape.ROUTE to "http://evil.invalid/directions/v5/mapbox/driving-traffic/1,2;3,4?x=1",
            Shape.ROUTE to "/directions/v5/mapbox/driving/139.7,35.6;139.8,35.7?access_token=x",
            Shape.ROUTE to "/directions/v5/mapbox/driving-traffic/../../api/secret?x=1",
            Shape.ROUTE to "/directions/v5/mapbox/driving-traffic/abc?x=1",
            Shape.STATUS to "/status?x=1",
            Shape.STATUS to "/v1/vehicle",
            Shape.HEALTH to "/status", // the kind says health, the target says status
            Shape.ROUTE to "/status",
            Shape.STATUS to "/api/navigation",
        )
        for ((kind, target) in hostile) {
            val comma = FakeComma(); val job = comma.submit(kind, target)
            val lane = lane(CommaLink(comma))
            val fetcher = FakeFetcher()
            jobs({ lane }, fetcher).pollOnce(lane)
            assertTrue("$kind $target was fetched", fetcher.fetched.isEmpty())
            assertTrue("$kind $target is failed", job.failed)
        }
    }

    // ---- the poll answer is untrusted input -----------------------------------------------------------------------------------------------

    private fun list(vararg rows: String, extra: String = "", v: String = "2") = "{\"v\":$v,\"jobs\":[${rows.joinToString(",")}],\"next_poll_ms\":10000$extra}".toByteArray()
    private val id = "0123456789abcdef0123456789abcdef"
    private fun row(id: String = this.id, kind: String = "status", target: String = "/status", seq: String = "0", claimed: String = "false", lease: String = "15000") =
        "{\"id\":\"$id\",\"kind\":\"$kind\",\"target\":\"$target\",\"next_sequence\":$seq,\"claimed\":$claimed,\"lease_ms\":$lease}"

    @Test fun aWellFormedJobListIsRead() {
        val parsed = SidecarJobs.parseJobList(list(row(), row(id = "f".repeat(32), kind = "route", target = route, seq = "4", claimed = "true")))
        assertEquals(2, parsed.jobs.size)
        assertEquals(Shape.STATUS, parsed.jobs[0].kind)
        assertEquals(4, parsed.jobs[1].nextSequence); assertTrue(parsed.jobs[1].claimed)
        assertEquals(10_000L, parsed.nextPollMs)
    }

    @Test fun badJobListsAreRefusedWholeNotRepaired() {
        val bad = listOf(
            list(v = "1"), list(v = "\"2\""), list(row(id = "XYZ")), list(row(id = id.uppercase())), list(row(kind = "vehicle")), list(row(kind = "other")), list(row(seq = "-1")),
            list(row(seq = "1000000")), list(row(seq = "1.5")), list(row(seq = "\"0\"")), list(row(claimed = "\"false\"")), list(row(claimed = "0")), list(row(lease = "null")), list(row(), row()),
            "{\"v\":2,\"jobs\":{},\"next_poll_ms\":1}".toByteArray(), "{\"v\":2}".toByteArray(), "[]".toByteArray(), "".toByteArray(), "not json".toByteArray(),
            "{\"v\":2,\"jobs\":[1],\"next_poll_ms\":1}".toByteArray(), "{\"v\":2,\"jobs\":[],\"jobs\":[],\"next_poll_ms\":1} x".toByteArray(),
            list(*Array(9) { row(id = it.toString().repeat(32)) }), ByteArray(70_000) { 32 },
        )
        for ((i, body) in bad.withIndex()) assertTrue("list $i was accepted", runCatching { SidecarJobs.parseJobList(body) }.isFailure)
    }

    @Test fun theCommasPollHintIsBoundedAndTheDefaultIsTenSeconds() {
        fun hint(text: String) = SidecarJobs.parseJobList("{\"v\":2,\"jobs\":[],$text}".toByteArray()).nextPollMs
        assertEquals(10_000L, hint("\"x\":1"))
        assertEquals(1_000L, hint("\"next_poll_ms\":1"))
        assertEquals(1_000L, hint("\"next_poll_ms\":-5"))
        assertEquals(30_000L, hint("\"next_poll_ms\":86400000"))
        assertEquals(10_000L, hint("\"next_poll_ms\":\"fast\""))
        assertEquals(2_500L, hint("\"next_poll_ms\":2500"))
    }

    @Test fun anUnreadablePollAnswerIsCountedAndNothingHappens() {
        val link = object : LaneLink {
            override fun exchange(meta: JSONObject, body: ByteArray?, deadlineSeconds: Long) = JSONObject().put("status", 200).put("body", java.util.Base64.getEncoder().encodeToString("{\"v\":3}".toByteArray()))
            override fun control(action: String) = JSONObject().put("status", 200)
            override fun setHighPriority(on: Boolean) = Unit
            override fun close() = Unit
        }
        val lane = lane(link)
        val fetcher = FakeFetcher()
        val worker = jobs({ lane }, fetcher)
        assertEquals(SidecarJobs.IDLE_POLL_MS, worker.pollOnce(lane))
        assertEquals(1, worker.jobsRefused)
        assertTrue(fetcher.fetched.isEmpty())
    }

    @Test fun anIdlePollIs204OrAnEmptyList() {
        val comma = FakeComma()
        val lane = lane(CommaLink(comma))
        val worker = jobs({ lane }, FakeFetcher(mutableMapOf("/status" to { LocalAnswer(200, json("{}")) })))
        assertEquals(10_000L, worker.pollOnce(lane))
        comma.nextPollMs = 1_000
        comma.submit(Shape.STATUS, "/status")
        assertEquals("a job waits: the comma's short cadence", 1_000L, worker.pollOnce(lane))
        val gone = object : LaneLink {
            override fun exchange(meta: JSONObject, body: ByteArray?, deadlineSeconds: Long) = JSONObject().put("status", 204)
            override fun control(action: String) = JSONObject().put("status", 200)
            override fun setHighPriority(on: Boolean) = Unit
            override fun close() = Unit
        }
        val quiet = lane(gone)
        assertEquals(10_000L, worker.pollOnce(quiet))
    }

    // ---- logging --------------------------------------------------------------------------------------------------------------------

    @Test fun noLogLineHoldsATargetATokenACoordinateOrABody() {
        val comma = FakeComma()
        val a = comma.submit(Shape.ROUTE, route)
        val b = comma.submit(Shape.STATUS, "/status")
        val lane1 = lane(CommaLink(comma).apply { failAfterChunk = 0 })
        val fetcher = FakeFetcher(mutableMapOf(
            route to { LocalAnswer(200, routeBody(200_000, 9)) },
            "/status" to { throw RouteFetchIo("response", "SocketException", java.net.SocketException("connection reset by 139.7671 SECRETTOKEN123")) },
        ))
        val worker = jobs({ lane1 }, fetcher)
        worker.pollOnce(lane1) // the fetch of /status is deferred; the route upload is interrupted by the link loss
        val lane2 = lane(CommaLink(comma))
        comma.expired += a.id
        worker.pollOnce(lane2) // the route job is gone: a 404 or a dropped transfer
        b.hashCode()
        fetcher.answers["/status"] = { LocalAnswer(500, json("{\"token\":\"SECRETTOKEN123\",\"lat\":35.6812}")) }
        worker.pollOnce(lane2) // an unlisted status fails the job
        assertTrue("some warnings were written", warnings.size >= 3)
        for (line in warnings) for (secret in listOf("SECRETTOKEN123", "139.7671", "35.6812", "driving-traffic", "/status", "access_token", "steps=true", a.id, b.id))
            assertFalse("a warning holds '$secret': $line", line.contains(secret))
    }
}
