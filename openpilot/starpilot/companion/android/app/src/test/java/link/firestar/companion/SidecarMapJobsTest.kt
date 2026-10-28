package link.firestar.companion

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater

/**
 * The job worker (SidecarJobs.kt) with the five map kinds of contract version 3 (docs/dev/bluetooth-map-contract.md 2.3, 2.5, 7.2 step 2): each kind fetched from the X10's own route API and
 * carried as it came (`identity`), with the status the X10 answered (200, 400, 401, 404, 422, 429, 503) and never fetched again for a 4xx; the limits of every kind; the chunks of the class
 * `map` and the planner of the class; the deadlines of a map job; one job of a kind at a time; a phone that did not announce `maps` and a bridge that did not say it; the bounded fetch against
 * hostile answers (a huge one, a slow one, a cut one, a redirect, an encoded one, no answer); every bundle of the vectors through the Companion's real path.
 */
class SidecarMapJobsTest {
    private val token = "TokenForTests"
    private val session = "123e4567-e89b-42d3-a456-426614174000"
    private val targets = linkedMapOf(
        Shape.ROADTILES to "/sidecar/v1/roadtiles?z=14&t=14551.6425,14551.6426&access_token=$token",
        Shape.STILE to "/styles/v1/mapbox/light-v11/tiles/512/14/14551/6425?access_token=$token",
        Shape.GEOCODE to "/search/geocode/v6/forward?q=%E6%B8%8B%E8%B0%B7&access_token=$token&limit=5",
        Shape.SUGGEST to "/search/searchbox/v1/suggest?q=Tokyo&access_token=$token&session_token=$session",
        Shape.RETRIEVE to "/search/searchbox/v1/retrieve/abc123?access_token=$token&session_token=$session",
    )
    private val lanes = ArrayList<BleLane>()
    private val warnings = CopyOnWriteArrayList<String>()
    private val pools = ArrayList<java.util.concurrent.ExecutorService>()

    @After fun cleanUp() { lanes.forEach { it.close() }; pools.forEach { it.shutdownNow() } }

    private fun lane(link: LaneLink, clock: () -> Long = System::nanoTime) = BleLane(link, clock, false).also { lanes += it }

    private class FakeFetcher(val answers: MutableMap<String, () -> LocalAnswer> = HashMap()) : LocalFetcher {
        val fetched = CopyOnWriteArrayList<String>()
        override fun fetch(shape: Shape, target: String): LocalAnswer {
            fetched += target
            return (answers[target] ?: answers[shape.wire] ?: throw AssertionError("fetch of an unexpected target"))()
        }
    }

    private fun jobs(lane: () -> BleLane?, fetcher: LocalFetcher, maps: Boolean = true, clock: () -> Long = System::nanoTime, workers: Executor = Executor { it.run() }) =
        SidecarJobs(lane, fetcher, { false }, clock, { warnings += it }, workers, null, { maps })

    private fun comma(maps: Boolean = true) = FakeComma().also { it.announced = false to maps }

    private fun bytes(n: Int, seed: Long = 1): ByteArray = ByteArray(n).also { Random(seed).nextBytes(it) }
    private fun limitOf(kind: Shape) = JobContract.mapLimit(kind, 200)

    // ---- the five kinds, carried as the X10 served them -----------------------------------------------------------------------------

    @Test fun everyMapKindIsFetchedAndCarriedByteForByteAsIdentityInItsLaneClass() {
        for ((kind, target) in targets) {
            val comma = comma(); val job = comma.submit(kind, target)
            val link = CommaLink(comma); val lane = lane(link)
            val events = CopyOnWriteArrayList<LaneEvent>().also { lane.observer = { e -> it += e } }
            val body = bytes(if (kind == Shape.STILE) 150_000 else if (kind == Shape.ROADTILES) 60_000 else 9_000, kind.ordinal.toLong())
            val fetcher = FakeFetcher(mutableMapOf(target to { LocalAnswer(200, body) }))
            jobs({ lane }, fetcher).pollOnce(lane)
            assertEquals(kind.wire, listOf(target), fetcher.fetched.toList())
            assertTrue("${kind.wire}: ${comma.rejected}", comma.rejected.isEmpty())
            assertEquals(kind.wire, 200, job.result!!.first)
            assertEquals(kind.wire, "identity", job.encoding)
            assertArrayEquals("${kind.wire}: the answer as the X10 served it", body, job.result!!.second)
            val chunkClasses = events.filter { it.label == "chunk" }.map { it.cls }.toSet()
            assertEquals("${kind.wire}: the lane class of its chunks", setOf(kind.jobClass), chunkClasses)
            assertTrue("${kind.wire}: the poll is a small request", events.any { it.label == "poll2" && it.cls == LaneClass.SMALL })
        }
    }

    @Test fun onlyTheChunksOfAPlannedTransferFeedAPlannerAndEachFeedsItsOwn() {
        val statusLane: BleLane
        run {
            val comma = comma(); val job = comma.submit(Shape.STATUS, "/status")
            val clock = java.util.concurrent.atomic.AtomicLong()
            statusLane = lane(TimedLink(comma, 10_000.0, clock), clock::get)
            jobs({ statusLane }, FakeFetcher(mutableMapOf("/status" to { LocalAnswer(200, bytes(3_000)) })), clock = clock::get).pollOnce(statusLane)
            assertNotNull(job.result)
        }
        assertNull("a status answer is one chunk of the poll's class and says nothing about the rate of a search answer", statusLane.searchPlanner.rate)
        assertNull(statusLane.planner.rate); assertNull(statusLane.mapPlanner.rate)
        for ((kind, planner) in listOf(Shape.GEOCODE to { l: BleLane -> l.searchPlanner }, Shape.ROADTILES to { l: BleLane -> l.mapPlanner })) {
            val comma = comma(); val job = comma.submit(kind, targets.getValue(kind))
            val clock = java.util.concurrent.atomic.AtomicLong()
            val lane = lane(TimedLink(comma, 10_000.0, clock), clock::get)
            jobs({ lane }, FakeFetcher(mutableMapOf(targets.getValue(kind) to { LocalAnswer(200, bytes(20_000)) })), clock = clock::get).pollOnce(lane)
            assertNotNull(job.result)
            assertNotNull("${kind.wire}: its own planner measured a rate", planner(lane).rate)
            assertNull("${kind.wire}: the route's planner did not", lane.planner.rate)
        }
    }

    @Test fun theTilesAreMapChunksAndTheSearchesAreSmallChunksAndTheRouteStaysBulk() {
        assertEquals(LaneClass.MAP, Shape.ROADTILES.jobClass); assertEquals(LaneClass.MAP, Shape.STILE.jobClass)
        for (kind in listOf(Shape.GEOCODE, Shape.SUGGEST, Shape.RETRIEVE, Shape.STATUS, Shape.HEALTH)) assertEquals(kind.wire, LaneClass.SMALL, kind.jobClass)
        assertEquals(LaneClass.BULK, Shape.ROUTE.jobClass)
    }

    @Test fun theStatusOfTheX10CrossesForEveryKindAndA4xxIsNeverFetchedAgain() {
        for ((kind, target) in targets) for (status in listOf(200, 400, 401, 404, 422, 429, 503)) {
            val comma = comma(); val job = comma.submit(kind, target)
            val lane = lane(CommaLink(comma))
            val body = if (status == 200) bytes(500, status.toLong()) else "{\"message\":\"x$status\"}".toByteArray()
            val fetcher = FakeFetcher(mutableMapOf(target to { LocalAnswer(status, body) }))
            val worker = jobs({ lane }, fetcher)
            worker.pollOnce(lane)
            assertEquals("${kind.wire} $status", status, job.result?.first)
            assertArrayEquals("${kind.wire} $status", body, job.result!!.second)
            assertEquals("${kind.wire} $status: identity", "identity", job.encoding)
            worker.pollOnce(lane); worker.pollOnce(lane)
            assertEquals("${kind.wire} $status: one fetch, the answer is the answer (no retry)", 1, fetcher.fetched.size)
            assertEquals(0, worker.jobsFailed)
        }
    }

    @Test fun aStatusTheMapRoutesNeverAnswerFailsTheJobAtOnceAndNothingIsSent() {
        for ((kind, target) in targets) for (status in listOf(204, 201, 202, 301, 302, 304, 403, 405, 500, 502, 504)) {
            val comma = comma(); val job = comma.submit(kind, target)
            val lane = lane(CommaLink(comma))
            val fetcher = FakeFetcher(mutableMapOf(target to { LocalAnswer(status, if (status == 204) ByteArray(0) else "{}".toByteArray()) }))
            val worker = jobs({ lane }, fetcher)
            worker.pollOnce(lane)
            assertNull("${kind.wire} $status is not carried", job.result)
            assertTrue("${kind.wire} $status: the job is failed, not left to time out", job.failed)
            assertTrue("${kind.wire} $status: no chunk was sent", job.chunks.isEmpty())
            assertEquals(1, fetcher.fetched.size)
        }
    }

    @Test fun anEmptyOkAnswerFailsTheJob() {
        for ((kind, target) in targets) {
            val comma = comma(); val job = comma.submit(kind, target)
            val lane = lane(CommaLink(comma))
            jobs({ lane }, FakeFetcher(mutableMapOf(target to { LocalAnswer(200, ByteArray(0)) }))).pollOnce(lane)
            assertTrue(kind.wire, job.failed); assertNull(kind.wire, job.result)
        }
    }

    // ---- the limits --------------------------------------------------------------------------------------------------------------------

    @Test fun everyKindCarriesItsLimitExactlyAndRefusesOneByteMore() {
        assertEquals(524_288, limitOf(Shape.ROADTILES)); assertEquals(2 * 1024 * 1024, limitOf(Shape.STILE))
        for (kind in listOf(Shape.GEOCODE, Shape.SUGGEST, Shape.RETRIEVE)) assertEquals(64 * 1024, limitOf(kind))
        for (kind in targets.keys) assertEquals("an error body is 4 KiB", 4096, JobContract.mapLimit(kind, 503))
        for ((kind, target) in targets) for ((status, size, ok) in listOf(Triple(200, limitOf(kind), true), Triple(200, limitOf(kind) + 1, false), Triple(404, 4096, true), Triple(404, 4097, false), Triple(503, 4097, false))) {
            val comma = comma(); val job = comma.submit(kind, target)
            val lane = lane(CommaLink(comma))
            val body = ByteArray(size) { (it * 31).toByte() }
            jobs({ lane }, FakeFetcher(mutableMapOf(target to { LocalAnswer(status, body) }))).pollOnce(lane)
            assertEquals("${kind.wire} $status $size bytes", ok, job.result != null)
            assertEquals("${kind.wire} $status $size bytes: refused means failed", !ok, job.failed)
            if (ok) assertArrayEquals(body, job.result!!.second)
        }
    }

    // ---- a Companion that did not announce maps, and a bridge that did not say it ---------------------------------------------------------

    @Test fun aPhoneThatDidNotAnnounceMapsNeverFetchesAMapJobAndFailsItAtOnce() {
        // a bridge that lists a map job to such a phone is wrong; the phone judges as a peer of version 2 and fails the job, it fetches nothing from the X10
        for ((kind, target) in targets) {
            val comma = FakeComma().also { it.announced = false to true } // the fake lists the job (it believes the phone announced); the phone itself did not
            val job = comma.submit(kind, target)
            val lane = lane(CommaLink(comma))
            val fetcher = FakeFetcher()
            val worker = jobs({ lane }, fetcher, maps = false)
            worker.pollOnce(lane)
            assertTrue("${kind.wire} fetched nothing", fetcher.fetched.isEmpty())
            assertTrue("${kind.wire} failed", job.failed); assertEquals(1, worker.jobsRefused)
            assertTrue(warnings.toString(), warnings.last().startsWith("Job refused (reason=not-allowed, kind=${kind.wire}"))
        }
    }

    @Test fun theBridgeShowsAMapJobOnlyToAPhoneThatAnnouncedItAndTheOldKindsDoNotNeedIt() {
        val comma = comma(maps = false)
        val route = "/directions/v5/mapbox/driving-traffic/139.7671,35.6812;139.7016,35.6580?access_token=$token"
        val tile = comma.submit(Shape.ROADTILES, targets.getValue(Shape.ROADTILES)); val status = comma.submit(Shape.STATUS, "/status"); val r = comma.submit(Shape.ROUTE, route)
        val lane = lane(CommaLink(comma))
        val fetcher = FakeFetcher(mutableMapOf("/status" to { LocalAnswer(200, "{}".toByteArray()) }, route to { LocalAnswer(200, bytes(20_000)) }))
        jobs({ lane }, fetcher, maps = false).pollOnce(lane)
        assertNotNull(status.result); assertNotNull(r.result)
        assertFalse(tile.finished); assertFalse(tile.failed)
        assertEquals("the map job was never listed to this phone", listOf("/status", route).sorted(), fetcher.fetched.sorted())
    }

    @Test fun theAnnouncementFollowsTheHealthAnswerOfTheBridge() {
        fun body(health: String): String? = PushEnable.request(BridgeFeatures.parse(health.toByteArray())).let { request ->
            if (request.has("body")) String(java.util.Base64.getDecoder().decode(request.getString("body"))) else null
        }
        val base = "\"protocol\":1,\"sidecarProtocol\":2,\"poll2\":true,\"results2\":true,\"push\":true"
        assertEquals("an old bridge with nav: the body of version 2, byte for byte", "{\"nav\":true}", body("{$base,\"pushNav\":true}"))
        assertEquals("an older bridge: no body at all", null, body("{$base}"))
        assertEquals("the legacy answer", null, body("{\"protocol\":1}"))
        assertEquals("maps and nav", "{\"nav\":true,\"maps\":true}", body("{$base,\"pushNav\":true,\"maps\":true,\"wwr\":true,\"up_kb_s\":41.3}"))
        assertEquals("maps without nav", "{\"maps\":true}", body("{$base,\"maps\":true,\"wwr\":true}"))
        assertEquals("maps said without wwr is still maps: the comma's client decides about the link", "{\"maps\":true}", body("{$base,\"maps\":true}"))
        assertEquals("a bridge that says maps but does not speak the jobs of version 2: nav only", "{\"nav\":true}", body("{\"protocol\":1,\"push\":true,\"pushNav\":true,\"maps\":true}"))
        assertEquals("nothing but the literal true counts", "{\"nav\":true}", body("{$base,\"pushNav\":true,\"maps\":1}"))
        assertEquals("{\"nav\":true}", body("{$base,\"pushNav\":true,\"maps\":\"true\"}"))
        assertEquals("not JSON: the legacy answer", null, body("{$base,\"maps\":true"))
        // what the bridge reads from each body is what the Companion means
        assertEquals(true to true, Announcements.parse("{\"nav\":true,\"maps\":true}".toByteArray()))
        assertEquals("the old bridge's reading of the combined body is no announcement at all, which is why it is sent only to a bridge that said maps", false to false, Announcements.parse("{\"nav\":true,\"maps\":false}".toByteArray()))
    }

    @Test fun aCompanionAndABridgeOfVersion3CarryAMapJobEndToEndAndAnOldBridgeNeverGetsOne() {
        // new bridge, new Companion: health says maps, the push-enable announces it, the job is listed, fetched and carried
        val comma = FakeComma()
        val health = BridgeFeatures.parse("{\"protocol\":1,\"poll2\":true,\"results2\":true,\"push\":true,\"pushNav\":true,\"maps\":true,\"wwr\":true,\"up_kb_s\":41.3}".toByteArray())
        val lane = lane(CommaLink(comma))
        assertEquals(200, lane.call(LaneClass.SMALL, 10, PushEnable.request(health), label = "push-enable").optInt("status"))
        assertEquals(true to true, comma.announced)
        val job = comma.submit(Shape.ROADTILES, targets.getValue(Shape.ROADTILES))
        val body = bytes(30_000)
        val worker = jobs({ lane }, FakeFetcher(mutableMapOf(targets.getValue(Shape.ROADTILES) to { LocalAnswer(200, body) })), maps = health.announcesMaps)
        worker.pollOnce(lane)
        assertArrayEquals(body, job.result!!.second)
        // old bridge (no maps in health), new Companion: the announcement is the old one, no map job is accepted whatever the bridge lists
        val old = BridgeFeatures.parse("{\"protocol\":1,\"poll2\":true,\"results2\":true,\"push\":true,\"pushNav\":true}".toByteArray())
        assertFalse(old.announcesMaps)
        val oldComma = FakeComma(); val oldLane = lane(CommaLink(oldComma))
        oldLane.call(LaneClass.SMALL, 10, PushEnable.request(old), label = "push-enable")
        assertEquals(true to false, oldComma.announced)
        oldComma.announced = true to true // a broken bridge that lists the job anyway
        val refused = oldComma.submit(Shape.STILE, targets.getValue(Shape.STILE))
        val fetcher = FakeFetcher()
        jobs({ oldLane }, fetcher, maps = old.announcesMaps).pollOnce(oldLane)
        assertTrue(fetcher.fetched.isEmpty()); assertTrue(refused.failed)
        // the legacy route path of an old bridge (no poll2) refuses a map target as it refused every other target
        assertFalse(SidecarRouteWorker.validTarget(targets.getValue(Shape.ROADTILES)))
        assertFalse(SidecarRouteWorker.validTarget(targets.getValue(Shape.STILE)))
    }

    // ---- the chunks of the class map ----------------------------------------------------------------------------------------------

    /** A link to the fake comma on a virtual clock: a chunk takes `body / rate + 0.1 s`. */
    private class TimedLink(val comma: FakeComma, val rate: Double, val clockNs: java.util.concurrent.atomic.AtomicLong) : LaneLink {
        val inner = CommaLink(comma)
        override fun exchange(meta: org.json.JSONObject, body: ByteArray?, deadlineSeconds: Long): org.json.JSONObject {
            val answer = inner.exchange(meta, body, deadlineSeconds)
            clockNs.addAndGet((((body?.size ?: 0) / rate + 0.1) * 1e9).toLong())
            return answer
        }
        override fun control(action: String) = inner.control(action)
        override fun setHighPriority(on: Boolean) = inner.setHighPriority(on)
        override fun close() = inner.close()
    }

    @Test fun aMapJobStartsAt4096BytesThenFollowsTheMeasuredRateTimesOneSecondAndNeverGoesBelow4096UntilTheLast() {
        for (rate in listOf(20_000.0, 1_500.0, 60_000.0)) {
            val clock = java.util.concurrent.atomic.AtomicLong()
            val comma = comma(); val target = targets.getValue(Shape.ROADTILES); val job = comma.submit(Shape.ROADTILES, target)
            val lane = lane(TimedLink(comma, rate, clock), clock::get)
            val body = bytes(100_000, 7)
            jobs({ lane }, FakeFetcher(mutableMapOf(target to { LocalAnswer(200, body) })), clock = clock::get).pollOnce(lane)
            assertArrayEquals(body, job.result!!.second)
            // an independent model of the planner: EWMA 0.5 of body over exchange time, times 1 s, whole packets of 239, between 4,096 and 65,536
            var ewma: Double? = null
            var offset = 0
            val sizes = job.chunks.map { it.second }
            for ((i, size) in sizes.withIndex()) {
                val remaining = body.size - offset
                var expected = ewma?.let { (it * 1.0).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt() } ?: 4096
                expected = maxOf(4096, minOf(65536, expected)); expected -= expected % 239; expected = minOf(maxOf(expected, 4096), remaining)
                assertEquals("rate $rate, chunk $i", expected, size)
                if (i < sizes.size - 1) assertTrue("rate $rate: a chunk below 4,096 bytes is not a sample of the bridge's meter (chunk $i is $size)", size >= 4096)
                val sample = size / (size / rate + 0.1)
                ewma = ewma?.let { 0.5 * it + 0.5 * sample } ?: sample
                offset += size
            }
            assertEquals("the first chunk of a map job", 4096, sizes.first())
        }
    }

    @Test fun aRouteStillStartsAt5975AndTheSearchAnswerUsesItsOwnPlannerOfOneSecond() {
        val comma = comma(); val job = comma.submit(Shape.SUGGEST, targets.getValue(Shape.SUGGEST))
        val clock = java.util.concurrent.atomic.AtomicLong()
        val lane = lane(TimedLink(comma, 10_000.0, clock), clock::get)
        val body = ByteArray(60_000) { (it % 97 + 32).toByte() }
        jobs({ lane }, FakeFetcher(mutableMapOf(targets.getValue(Shape.SUGGEST) to { LocalAnswer(200, body) })), clock = clock::get).pollOnce(lane)
        assertEquals("the first chunk of a search answer: the route's start", 5975, job.chunks.first().second)
        assertTrue("a 60 KB answer is several chunks, none above one second of the measured rate (10 KB/s: 9,000 to 10,000 bytes)", job.chunks.size > 3 && job.chunks.drop(1).all { it.second <= 10_000 })
        assertArrayEquals(body, job.result!!.second)
    }

    // ---- resume and the deadlines of a map job ---------------------------------------------------------------------------------------

    @Test fun aLinkLossDuringAMapUploadResumesAtTheCommasCursorWithoutAFreshFetch() {
        val comma = comma(); val target = targets.getValue(Shape.ROADTILES); val job = comma.submit(Shape.ROADTILES, target)
        val body = bytes(200_000, 3)
        val fetcher = FakeFetcher(mutableMapOf(target to { LocalAnswer(200, body) }))
        val worker = jobs({ null }, fetcher)
        val link1 = CommaLink(comma).apply { failAfterChunk = 1 }; val lane1 = lane(link1)
        worker.pollOnce(lane1)
        assertFalse(job.finished); assertEquals(2, job.next)
        val lane2 = lane(CommaLink(comma))
        worker.pollOnce(lane2)
        assertArrayEquals(body, job.result!!.second)
        assertEquals("no second fetch: the bytes were held", 1, fetcher.fetched.size)
        assertTrue(comma.rejected.isEmpty())
    }

    @Test fun aStyleTileIsLostAtItsOwn30SecondsAndATileBatchLivesTo120() {
        for ((kind, lives) in listOf(Shape.STILE to false, Shape.ROADTILES to true)) {
            val clock = java.util.concurrent.atomic.AtomicLong()
            val comma = comma(); val target = targets.getValue(kind); val job = comma.submit(kind, target)
            val body = bytes(200_000, 5)
            val fetcher = FakeFetcher(mutableMapOf(target to { LocalAnswer(200, body) }))
            val worker = jobs({ null }, fetcher, clock = clock::get)
            val lane1 = lane(CommaLink(comma).apply { failAfterChunk = 0 }, clock::get)
            worker.pollOnce(lane1) // the first poll lists the job: the deadline counts from here; one chunk is committed, then the link is lost
            assertEquals(1, job.next)
            clock.addAndGet(TimeUnit.SECONDS.toNanos(31))
            val lane2 = lane(CommaLink(comma), clock::get)
            worker.pollOnce(lane2)
            assertEquals("${kind.wire} after 31 s", lives, job.finished)
            assertEquals("${kind.wire}: lost jobs", if (lives) 0 else 1, worker.jobsLost)
            assertEquals("${kind.wire}: never fetched again", 1, fetcher.fetched.size)
            if (!lives) {
                assertEquals("nothing more was sent for the lost job", 1, job.next)
                assertFalse("it is the comma's deadline that ends it, the Companion does not call fail", job.failed)
                assertTrue(warnings.last().startsWith("Job past its deadline (kind=${kind.wire}"))
                val polls = worker.pollRequests.get()
                worker.pollOnce(lane(CommaLink(comma), clock::get))
                assertEquals("a job the comma still lists is counted once and never fetched or sent again", 1, worker.jobsLost)
                assertEquals("and it does not ask for an immediate poll (the comma has not dropped it yet)", polls, worker.pollRequests.get())
            } else assertArrayEquals(body, job.result!!.second)
        }
    }

    @Test fun aStyleTileThatPassesItsDeadlineInTheMiddleOfTheUploadSendsNothingMore() {
        val clock = java.util.concurrent.atomic.AtomicLong()
        val comma = comma(); val target = targets.getValue(Shape.STILE); val job = comma.submit(Shape.STILE, target)
        val body = bytes(200_000, 11)
        val fetcher = FakeFetcher(mutableMapOf(target to { LocalAnswer(200, body) }))
        val lane = lane(TimedLink(comma, 5_000.0, clock), clock::get) // 5 KB/s: the whole tile would take 40 s, its deadline is 30
        val worker = jobs({ lane }, fetcher, clock = clock::get)
        worker.pollOnce(lane)
        val sent = job.chunks.sumOf { it.second }
        assertNull(job.result); assertFalse(job.finished); assertFalse("the comma ends it, the Companion does not call fail", job.failed)
        assertEquals(1, worker.jobsLost)
        assertTrue("part of the tile went, not all of it ($sent bytes)", sent in 1 until body.size)
        val seconds = clock.get() / 1e9
        assertTrue("the last chunk started before the deadline and nothing went after it ($seconds s)", seconds in 30.0..32.0)
        assertEquals(1, fetcher.fetched.size)
        assertEquals("the bytes of the lost job are gone", 0, worker.heldTransfers)
    }

    @Test fun theBytesOfATileBatchAreHeldForItsDeadlineAndTenSecondsMoreSoAResumeAfter110SecondsContinues() {
        val clock = java.util.concurrent.atomic.AtomicLong()
        val comma = comma(); val target = targets.getValue(Shape.ROADTILES); val job = comma.submit(Shape.ROADTILES, target)
        val body = bytes(200_000, 13)
        val fetcher = FakeFetcher(mutableMapOf(target to { LocalAnswer(200, body) }))
        val worker = jobs({ null }, fetcher, clock = clock::get)
        worker.pollOnce(lane(CommaLink(comma).apply { failAfterChunk = 0 }, clock::get))
        assertEquals(1, job.next)
        clock.addAndGet(TimeUnit.SECONDS.toNanos(110)) // past the 100 s a route's bytes are held, inside the 120 s of a tile batch
        worker.pollOnce(lane(CommaLink(comma), clock::get))
        assertTrue("the upload continued from the comma's cursor", job.finished); assertFalse(job.failed)
        assertArrayEquals(body, job.result!!.second)
        assertEquals("no second fetch", 1, fetcher.fetched.size)
    }

    @Test fun aSearchIsLostAfter15SecondsAndTheBytesOfALostJobAreForgotten() {
        val clock = java.util.concurrent.atomic.AtomicLong()
        val comma = comma(); val target = targets.getValue(Shape.GEOCODE); val job = comma.submit(Shape.GEOCODE, target)
        val fetcher = FakeFetcher(mutableMapOf(target to { throw RouteFetchIo("response", "SocketTimeoutException", java.net.SocketTimeoutException("slow")) }))
        val worker = jobs({ null }, fetcher, clock = clock::get)
        val lane1 = lane(CommaLink(comma), clock::get)
        worker.pollOnce(lane1) // the X10 did not answer: the job stays open and the next poll fetches again
        assertEquals(1, fetcher.fetched.size); assertFalse(job.failed)
        clock.addAndGet(TimeUnit.SECONDS.toNanos(14)); worker.pollOnce(lane1)
        assertEquals("inside 15 s the next poll fetches again", 2, fetcher.fetched.size)
        clock.addAndGet(TimeUnit.SECONDS.toNanos(2)); worker.pollOnce(lane1)
        assertEquals("past 15 s nothing is fetched for it", 2, fetcher.fetched.size)
        assertEquals(1, worker.jobsLost)
    }

    // ---- one job of a kind at a time, the kinds side by side ----------------------------------------------------------------------------

    @Test fun twoJobsOfOneKindRunOneAfterTheOtherAndTheOtherKindsRunAtTheSameTime() {
        val comma = comma()
        val t1 = "/styles/v1/mapbox/light-v11/tiles/512/14/14551/6425?access_token=$token"
        val t2 = "/styles/v1/mapbox/dark-v11/tiles/512/14/14551/6425?access_token=$token"
        val s1 = comma.submit(Shape.STILE, t1); val s2 = comma.submit(Shape.STILE, t2); val tiles = comma.submit(Shape.ROADTILES, targets.getValue(Shape.ROADTILES))
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val fetcher = FakeFetcher(mutableMapOf(
            t1 to { entered.countDown(); release.await(10, TimeUnit.SECONDS); LocalAnswer(200, bytes(8_000, 1)) },
            t2 to { LocalAnswer(200, bytes(8_000, 2)) },
            targets.getValue(Shape.ROADTILES) to { LocalAnswer(200, bytes(8_000, 3)) },
        ))
        val pool = Executors.newCachedThreadPool().also { pools += it }
        val lane = lane(CommaLink(comma))
        val worker = jobs({ lane }, fetcher, workers = pool)
        worker.pollOnce(lane)
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val begun = System.nanoTime()
        while (!tiles.finished && System.nanoTime() - begun < 10_000_000_000L) Thread.sleep(5)
        assertTrue("another kind goes on while the first style tile is being fetched", tiles.finished)
        assertFalse("the second style tile waits for the first", s2.finished); assertFalse(t2 in fetcher.fetched)
        release.countDown()
        val waited = System.nanoTime()
        while (!s1.finished && System.nanoTime() - waited < 10_000_000_000L) Thread.sleep(5)
        assertTrue(s1.finished)
        Thread.sleep(50) // the worker of the first job has ended; the next poll starts the second
        worker.pollOnce(lane)
        val again = System.nanoTime()
        while (!s2.finished && System.nanoTime() - again < 10_000_000_000L) Thread.sleep(5)
        assertTrue(s2.finished)
    }

    /**
     * Found by the twin of the real pair (rows N-two-style-tiles-in-a-row, open item B1-4): the second job of a kind waits for the first, and its deadline was counted from the moment its own
     * work began, not from the poll that listed it. The comma counts from the queueing, so on a slow link the Companion would start (fetch, upload) a job that the comma had dropped, and lose the
     * lane time of its chunks. The deadline of a listed map job counts from the first poll that listed it, whether it started then or later.
     */
    @Test fun aMapJobThatWaitsBehindAnotherOfItsKindCountsItsDeadlineFromThePollThatListedIt() {
        val clock = java.util.concurrent.atomic.AtomicLong()
        val comma = comma()
        val t1 = "/styles/v1/mapbox/light-v11/tiles/512/14/14551/6425?access_token=$token"
        val t2 = "/styles/v1/mapbox/dark-v11/tiles/512/14/14551/6425?access_token=$token"
        val s1 = comma.submit(Shape.STILE, t1); val s2 = comma.submit(Shape.STILE, t2)
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val fetcher = FakeFetcher(mutableMapOf(
            t1 to { entered.countDown(); release.await(10, TimeUnit.SECONDS); LocalAnswer(200, bytes(8_000, 1)) },
            t2 to { LocalAnswer(200, bytes(8_000, 2)) },
        ))
        val pool = Executors.newCachedThreadPool().also { pools += it }
        val lane = lane(CommaLink(comma), clock::get)
        val worker = jobs({ lane }, fetcher, clock = clock::get, workers = pool)
        worker.pollOnce(lane) // lists both: the first starts, the second waits (one job of a kind at a time)
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        release.countDown()
        val begun = System.nanoTime()
        while (!s1.finished && System.nanoTime() - begun < 10_000_000_000L) Thread.sleep(5)
        assertTrue(s1.finished); assertFalse(s2.finished)
        Thread.sleep(50) // the worker of the first job has ended
        clock.addAndGet(TimeUnit.SECONDS.toNanos(31)) // the first took long: the 30 s of a style tile, counted from the comma's queueing, are over
        worker.pollOnce(lane)
        Thread.sleep(100)
        assertEquals("the second tile is lost by its own deadline", 1, worker.jobsLost)
        assertFalse("nothing is fetched for a job whose time is over", t2 in fetcher.fetched)
        assertEquals("nothing is sent for it", 0, s2.next)
        assertFalse("the comma's deadline ends it, the Companion does not call fail", s2.failed)
        assertTrue(warnings.any { it.startsWith("Job past its deadline (kind=stile") })
    }

    /**
     * Found by the twin of the real pair (row F-style-tile-behind-a-long-route): the lane picks the next request the moment a chunk ends, and the route's own next chunk is queued a moment
     * later (it has to read the acknowledgement first), so a map chunk that was already waiting took the lane in that gap, again after every route chunk: a long route upload was slowed by
     * the whole transfer of a style tile. A map chunk is not submitted while a route transfer is being sent: the tiles wait for the route (and a tile that passes its deadline meanwhile is
     * lost whole, as the contract says).
     */
    @Test fun aMapChunkIsNotSentBetweenTheChunksOfARouteTransferThatIsRunning() {
        val comma = comma()
        val route = "/directions/v5/mapbox/driving-traffic/139.7671,35.6812;139.7016,35.6580?access_token=$token&steps=true"
        val routeJob = comma.submit(Shape.ROUTE, route)
        val styleTarget = targets.getValue(Shape.STILE)
        val fetcher = FakeFetcher(mutableMapOf(route to { LocalAnswer(200, bytes(1_200_000, 21)) }, styleTarget to { LocalAnswer(200, bytes(20_000, 22)) }))
        val pool = Executors.newCachedThreadPool().also { pools += it }
        val lane = lane(CommaLink(comma).apply { chunkDelayMs = 30 })
        val worker = jobs({ lane }, fetcher, workers = pool)
        fun until(seconds: Long, condition: () -> Boolean): Boolean {
            val begun = System.nanoTime()
            while (!condition() && System.nanoTime() - begun < seconds * 1_000_000_000L) Thread.sleep(5)
            return condition()
        }
        worker.pollOnce(lane) // the route starts
        assertTrue("the route transfer began", until(20) { comma.transcript.any { it.path.contains(routeJob.id) } })
        val styleJob = comma.submit(Shape.STILE, styleTarget)
        worker.pollOnce(lane) // lists the style tile while the route is being sent
        assertTrue("both jobs ended", until(60) { routeJob.finished && styleJob.finished })
        val order = comma.transcript.filter { it.path.startsWith("/_bridge/sidecar/chunk/") }.map { it.path.removePrefix("/_bridge/sidecar/chunk/").substringBefore('/') }
        val firstRoute = order.indexOf(routeJob.id); val lastRoute = order.lastIndexOf(routeJob.id)
        assertTrue("the route went in several chunks (${order.count { it == routeJob.id }})", order.count { it == routeJob.id } >= 5)
        assertEquals("no chunk of the style tile between the first and the last chunk of the route", 0, order.subList(firstRoute, lastRoute + 1).count { it == styleJob.id })
        assertTrue("the style tile followed the route and arrived whole", styleJob.result!!.second.size == 20_000)
        assertFalse(routeJob.failed); assertFalse(styleJob.failed)
    }

    /**
     * Found by the twin of the real pair (row L-mixed-balanced-wwr: a route asked for during a corridor came 2.6 s late, two map chunks): a map chunk that went while the route job was still
     * being fetched from the X10 was on the air when the route's first chunk was ready. The lane belongs to the route from the moment its job is worked on, not from its first chunk.
     */
    @Test fun aMapChunkWaitsForARouteJobThatIsStillBeingFetchedFromTheX10() {
        val comma = comma()
        val route = "/directions/v5/mapbox/driving-traffic/139.7671,35.6812;139.7016,35.6580?access_token=$token&steps=true"
        val routeJob = comma.submit(Shape.ROUTE, route)
        val styleTarget = targets.getValue(Shape.STILE)
        val styleJob = comma.submit(Shape.STILE, styleTarget)
        val fetcher = FakeFetcher(mutableMapOf(
            route to { Thread.sleep(600); LocalAnswer(200, bytes(40_000, 41)) }, // the X10 computes the route: seconds in production
            styleTarget to { LocalAnswer(200, bytes(20_000, 42)) },
        ))
        val pool = Executors.newCachedThreadPool().also { pools += it }
        val lane = lane(CommaLink(comma))
        val worker = jobs({ lane }, fetcher, workers = pool)
        worker.pollOnce(lane) // lists both: the tile is ready at once, the route 600 ms later
        val begun = System.nanoTime()
        while (!(routeJob.finished && styleJob.finished) && System.nanoTime() - begun < 30_000_000_000L) Thread.sleep(5)
        assertTrue(routeJob.finished && styleJob.finished)
        val order = comma.transcript.filter { it.path.startsWith("/_bridge/sidecar/chunk/") }.map { it.path.removePrefix("/_bridge/sidecar/chunk/").substringBefore('/') }
        assertEquals("the route's first chunk goes before any chunk of the tile", routeJob.id, order.first())
        assertEquals("the tile follows the route", styleJob.id, order.last())
    }

    @Test fun aMapJobThatWaitsForARouteTransferStillLosesItsDeadlineAndSendsNothing() {
        val clock = java.util.concurrent.atomic.AtomicLong()
        val comma = comma()
        val route = "/directions/v5/mapbox/driving-traffic/139.7671,35.6812;139.7016,35.6580?access_token=$token&steps=true"
        val routeJob = comma.submit(Shape.ROUTE, route)
        val styleTarget = targets.getValue(Shape.STILE)
        val styleJob = comma.submit(Shape.STILE, styleTarget)
        val gate = CountDownLatch(1)
        val routeChunkInFlight = CountDownLatch(1)
        val inner = CommaLink(comma)
        val link = object : LaneLink {
            override fun exchange(meta: org.json.JSONObject, body: ByteArray?, deadlineSeconds: Long): org.json.JSONObject {
                if (meta.getString("path").startsWith("/_bridge/sidecar/chunk/${routeJob.id}/0")) { routeChunkInFlight.countDown(); gate.await(20, TimeUnit.SECONDS) }
                return inner.exchange(meta, body, deadlineSeconds)
            }
            override fun control(action: String) = inner.control(action)
            override fun setHighPriority(on: Boolean) = inner.setHighPriority(on)
            override fun close() = inner.close()
        }
        val styleFetched = CountDownLatch(1)
        val fetcher = FakeFetcher(mutableMapOf(
            route to { LocalAnswer(200, bytes(300_000, 31)) },
            styleTarget to { Thread.sleep(300); styleFetched.countDown(); LocalAnswer(200, bytes(20_000, 32)) }, // slower than the route: the route is being sent when the tile is ready
        ))
        val pool = Executors.newCachedThreadPool().also { pools += it }
        val lane = lane(link, clock::get)
        val worker = jobs({ lane }, fetcher, clock = clock::get, workers = pool)
        worker.pollOnce(lane) // lists both: the route is sent at once, the tile is fetched meanwhile and then waits for the route
        assertTrue("the first chunk of the route is on the link", routeChunkInFlight.await(10, TimeUnit.SECONDS))
        assertTrue("the tile was fetched", styleFetched.await(10, TimeUnit.SECONDS))
        Thread.sleep(200) // the tile's worker is waiting for the route
        assertEquals("nothing of the tile was sent while the route is being sent", 0, styleJob.next)
        clock.addAndGet(TimeUnit.SECONDS.toNanos(31)) // the 30 s of a style tile are over
        val begun = System.nanoTime()
        while (worker.jobsLost == 0 && System.nanoTime() - begun < 10_000_000_000L) Thread.sleep(5)
        assertEquals("the tile is lost by its own deadline while it waits", 1, worker.jobsLost)
        assertEquals(0, styleJob.next); assertFalse(styleJob.failed)
        gate.countDown() // the route goes on
        val waited = System.nanoTime()
        while (!routeJob.finished && System.nanoTime() - waited < 20_000_000_000L) Thread.sleep(5)
        assertTrue(routeJob.finished)
        assertEquals("the route's transfer ended: the tiles may go again", 0, styleJob.next)
    }

    // ---- every bundle of the vectors through the Companion's path -------------------------------------------------------------------------

    private fun bundleBytes(hex: String?, zlibHex: String?): ByteArray {
        if (hex != null) return TestVectors.hex(hex)
        val inflater = Inflater()
        inflater.setInput(TestVectors.hex(zlibHex!!))
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(65536)
        while (!inflater.finished()) { val n = inflater.inflate(buffer); if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break; out.write(buffer, 0, n) }
        inflater.end()
        return out.toByteArray()
    }

    @Test fun everyBundleOfTheVectorsCrossesByteForByteWhenItIsWithinTheLimitAndNeverWhenItIsNot() {
        // The Companion carries an RTB1 bundle as opaque bytes (size, status, digest): the strict reader is the comma's (and the X10 writer's), the Companion does not interpret the structure.
        // So the 94 hand-written and the 1,500 generated bundles, the ones the reference reader accepts and the ones it refuses alike, arrive unchanged, and only an empty answer or a bundle over 524,288 bytes does not.
        val handWritten = (TestVectors.file("bundles.json")["decode_cases"] as List<*>).map { it as StrictJson.Obj }
        val generated = (TestVectors.file("bundles-differential.json")["cases"] as List<*>).map { it as StrictJson.Obj }
        assertEquals(94, handWritten.size); assertEquals(1500, generated.size)
        val comma = comma(); val target = targets.getValue(Shape.ROADTILES)
        var answer = ByteArray(0)
        val fetcher = FakeFetcher(mutableMapOf(target to { LocalAnswer(200, answer) }))
        val lane = lane(CommaLink(comma))
        val worker = jobs({ lane }, fetcher)
        var carried = 0; var refusedBySize = 0; var accepted = 0
        fun run(name: String, bytes: ByteArray, expectedSha: String?, readerAccepts: Boolean) {
            answer = bytes
            val job = comma.submit(Shape.ROADTILES, target)
            worker.pollOnce(lane)
            if (bytes.size in 1..524_288) {
                carried++; if (readerAccepts) accepted++
                assertNotNull("$name: carried", job.result)
                assertEquals("$name: the status of the X10", 200, job.result!!.first)
                assertArrayEquals("$name: the bytes the X10 served, unchanged", bytes, job.result!!.second)
                if (expectedSha != null) assertEquals("$name: the vector's own digest", expectedSha, JobContract.sha256Hex(job.result!!.second))
            } else {
                refusedBySize++
                assertNull("$name: empty or over the limit of a bundle", job.result); assertTrue("$name: the job is failed", job.failed); assertTrue("$name: nothing was uploaded", job.chunks.isEmpty())
            }
        }
        for (case in handWritten) {
            val bytes = bundleBytes(case["bundle_hex"] as? String, case["bundle_zlib_hex"] as? String)
            assertEquals("${case["name"]}: the vector's own length", TestVectors.int(case["bytes"]), bytes.size)
            run(case["name"] as String, bytes, case["sha256"] as String, case["expect"] == "accept")
        }
        for (case in generated) run("generated ${case["id"]}", bundleBytes(case["bundle_hex"] as String, null), null, case["expect"] == "accept")
        assertTrue("the vectors hold bundles of both kinds", accepted in 100..800 && carried > 1500 && refusedBySize in 1..10)
        assertEquals(1594, carried + refusedBySize)
        assertTrue(comma.rejected.toString(), comma.rejected.isEmpty())
    }

    // ---- the bounded fetch against hostile answers --------------------------------------------------------------------------------------

    /** A one-shot local server: [handler] gets the socket and the request head; the fetcher under test connects to it as it would to the X10's route API. */
    private class Peer(val handler: (Socket, String) -> Unit) : AutoCloseable {
        val server = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        val heads = CopyOnWriteArrayList<String>()
        @Volatile private var open = true
        private val thread = Thread {
            while (open) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                try {
                    val input = socket.getInputStream()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) { val b = input.read(); if (b < 0) break; head.append(b.toChar()) }
                    heads += head.toString()
                    handler(socket, head.toString())
                } catch (_: IOException) { /* the client left */ } finally { try { socket.close() } catch (_: IOException) { } }
            }
        }.apply { isDaemon = true; start() }
        val port get() = server.localPort
        override fun close() { open = false; server.close() }
    }

    private fun reply(socket: Socket, status: String, headers: List<String>, body: ByteArray) {
        val out = socket.getOutputStream()
        out.write(("HTTP/1.1 $status\r\n" + headers.joinToString("") { "$it\r\n" } + "\r\n").toByteArray()); out.write(body); out.flush()
    }

    private fun fetch(peer: Peer, kind: Shape, running: () -> Boolean = { true }) = LoopbackFetcher(peer.port, running).fetch(kind, targets.getValue(kind))

    @Test fun theFetchAsksForExactlyTheTargetWithIdentityAndForwardsNothing() {
        Peer { socket, _ -> reply(socket, "200 OK", listOf("Content-Length: 5", "Content-Type: application/octet-stream"), "RTB1\u0001".toByteArray()) }.use { peer ->
            val answer = fetch(peer, Shape.ROADTILES)
            assertEquals(200, answer.status); assertEquals("RTB1\u0001", String(answer.body))
            val head = peer.heads.single()
            assertEquals("GET ${targets.getValue(Shape.ROADTILES)} HTTP/1.1", head.lines().first())
            val lower = head.lowercase()
            assertTrue(lower, "accept-encoding: identity" in lower); assertTrue(lower, "connection: close" in lower)
            for (header in listOf("authorization", "cookie", "x-", "proxy", "referer", "origin")) assertFalse("$header is not sent", head.lines().drop(1).any { it.lowercase().startsWith(header) })
        }
    }

    @Test fun aBodyOverTheLimitOfItsKindIsRefusedBeforeItIsReadAndWhileItIsRead() {
        for ((kind, limit) in listOf(Shape.ROADTILES to 524_288, Shape.GEOCODE to 65_536)) {
            // declared too long: refused at the headers
            Peer { socket, _ -> reply(socket, "200 OK", listOf("Content-Length: ${limit + 1}"), ByteArray(0)) }.use { peer ->
                val error = assertThrows(RouteFetchRejected::class.java) { fetch(peer, kind) }
                assertEquals("oversize", error.reason); assertEquals(200, error.status)
            }
            // exactly the limit: taken
            Peer { socket, _ -> reply(socket, "200 OK", listOf("Content-Length: $limit"), ByteArray(limit) { 7 }) }.use { peer -> assertEquals(limit, fetch(peer, kind).body.size) }
            // no length (the answer ends when the X10 closes): refused as soon as the bytes pass the limit
            Peer { socket, _ -> reply(socket, "200 OK", listOf("Connection: close"), ByteArray(limit + 1) { 7 }) }.use { peer ->
                assertEquals("oversize", assertThrows(RouteFetchRejected::class.java) { fetch(peer, kind) }.reason)
            }
        }
        Peer { socket, _ -> reply(socket, "200 OK", listOf("Connection: close"), ByteArray(2 * 1024 * 1024 + 1) { 7 }) }.use { peer ->
            assertEquals("oversize", assertThrows(RouteFetchRejected::class.java) { fetch(peer, Shape.STILE) }.reason)
        }
        Peer { socket, _ -> reply(socket, "200 OK", listOf("Connection: close"), ByteArray(2 * 1024 * 1024) { 7 }) }.use { peer -> assertEquals(2 * 1024 * 1024, fetch(peer, Shape.STILE).body.size) }
    }

    @Test fun anErrorBodyOver4KiBIsRefusedAndOneOf4KiBIsCarriedWithItsStatus() {
        for (status in listOf("400 Bad Request", "401 Unauthorized", "404 Not Found", "422 Unprocessable Entity", "429 Too Many Requests", "503 Service Unavailable")) {
            Peer { socket, _ -> reply(socket, status, listOf("Content-Length: 4096"), ByteArray(4096) { 65 }) }.use { peer ->
                val answer = fetch(peer, Shape.GEOCODE)
                assertEquals(status.substring(0, 3).toInt(), answer.status); assertEquals(4096, answer.body.size)
            }
            Peer { socket, _ -> reply(socket, status, listOf("Content-Length: 4097"), ByteArray(4097) { 65 }) }.use { peer ->
                assertEquals("oversize", assertThrows(RouteFetchRejected::class.java) { fetch(peer, Shape.GEOCODE) }.reason)
            }
        }
    }

    @Test fun aClientErrorIsOneFetchOnlyAndTheStatusIsPassedOnAsItCame() {
        for (status in listOf("400 Bad Request", "401 Unauthorized", "404 Not Found", "422 Unprocessable Entity", "429 Too Many Requests")) {
            Peer { socket, _ -> reply(socket, status, listOf("Content-Length: 2", "Retry-After: 30"), "{}".toByteArray()) }.use { peer ->
                val answer = fetch(peer, Shape.ROADTILES)
                assertEquals(status.substring(0, 3).toInt(), answer.status)
                assertEquals("one connection: a 4xx is an answer, the Companion never asks again", 1, peer.heads.size)
            }
        }
    }

    @Test fun aRedirectAndAnEncodedMapAnswerAreRefused() {
        Peer { socket, _ -> reply(socket, "302 Found", listOf("Location: http://127.0.0.1:1/elsewhere", "Content-Length: 0"), ByteArray(0)) }.use { peer ->
            val error = assertThrows(RouteFetchRejected::class.java) { fetch(peer, Shape.STILE) }
            assertEquals("redirect", error.reason); assertEquals(302, error.status); assertEquals("the redirect was not followed", 1, peer.heads.size)
        }
        for (encoding in listOf("gzip", "xz", "br", "deflate")) Peer { socket, _ -> reply(socket, "200 OK", listOf("Content-Encoding: $encoding", "Content-Length: 4"), byteArrayOf(1, 2, 3, 4)) }.use { peer ->
            assertEquals("content-encoding", assertThrows(RouteFetchRejected::class.java) { fetch(peer, Shape.ROADTILES) }.reason)
        }
        Peer { socket, _ -> reply(socket, "200 OK", listOf("Content-Encoding: identity", "Content-Length: 4"), byteArrayOf(1, 2, 3, 4)) }.use { peer -> assertEquals(4, fetch(peer, Shape.ROADTILES).body.size) }
    }

    @Test fun anAnswerThatIsCutShortIsNeverTakenAsAnAnswer() {
        Peer { socket, _ -> reply(socket, "200 OK", listOf("Content-Length: 1000"), ByteArray(100) { 1 }) }.use { peer ->
            val failure = runCatching { fetch(peer, Shape.ROADTILES) }.exceptionOrNull()
            assertTrue("a cut answer is an error ($failure)", failure is RouteFetchIo || failure is RouteFetchRejected)
        }
        // through the job worker the job stays open (a fresh GET is safe) and nothing is sent
        Peer { socket, _ -> reply(socket, "200 OK", listOf("Content-Length: 1000"), ByteArray(100) { 1 }) }.use { peer ->
            val comma = comma(); val job = comma.submit(Shape.ROADTILES, targets.getValue(Shape.ROADTILES))
            val lane = lane(CommaLink(comma))
            jobs({ lane }, LoopbackFetcher(peer.port)).pollOnce(lane)
            assertTrue(job.chunks.isEmpty()); assertNull(job.result)
        }
    }

    @Test fun aSlowX10IsGivenUpWhenTheWorkerStopsAndASilentOneAtTheReadTimeout() {
        val started = System.nanoTime()
        Peer { socket, _ ->
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n".toByteArray()); out.flush()
            repeat(100) { out.write(1); out.flush(); Thread.sleep(50) }
        }.use { peer ->
            val failure = runCatching { fetch(peer, Shape.STILE, running = { System.nanoTime() - started < 500_000_000L }) }.exceptionOrNull()
            assertTrue("a slow body ends in a typed error ($failure)", failure is RouteFetchIo)
            assertEquals("body", (failure as RouteFetchIo).stage)
            assertTrue("and does not outlast the worker", System.nanoTime() - started < 4_000_000_000L)
        }
        val silent = System.nanoTime()
        Peer { _, _ -> Thread.sleep(8_000) }.use { peer ->
            val failure = runCatching { fetch(peer, Shape.GEOCODE) }.exceptionOrNull()
            assertTrue("no answer at all is a typed error ($failure)", failure is RouteFetchIo)
            assertEquals("response", (failure as RouteFetchIo).stage)
            assertTrue("a search waits at most its read timeout of 5 s", System.nanoTime() - silent < 7_500_000_000L)
        }
    }
}
