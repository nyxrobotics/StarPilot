package link.firestar.companion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * Runs the job worker through scenarios and, when BTLINK_INTEROP_DIR is set, writes what reached comma (every chunk and failure notice, with the answer the Kotlin fake
 * gave) so that `tools/btcompanion/bridge_replay.py` can feed the same requests to the REAL `JobBroker` and `GalaxyProxy` of the bridge and compare every answer and the
 * finished result. A normal run only checks that each scenario ends as intended.
 */
class JobsInteropTest {
    private val route = "/directions/v5/mapbox/driving-traffic/139.7671,35.6812;139.7016,35.6580?access_token=TOKEN&steps=true"
    private val lanes = ArrayList<BleLane>()
    private val warnings = CopyOnWriteArrayList<String>()
    private val dir = System.getenv("BTLINK_INTEROP_DIR")?.let { File(it).apply { mkdirs() } }
    private var written = 0

    @After fun close() { lanes.forEach { it.close() } }

    private fun lane(link: LaneLink) = BleLane(link, System::nanoTime, false).also { lanes += it }

    private fun routeBody(size: Int, seed: Long): ByteArray {
        val rng = Random(seed)
        val text = StringBuilder("{\"routes\":[{\"geometry\":{\"coordinates\":[")
        while (text.length < size) text.append('[').append(139 + rng.nextDouble()).append(',').append(35 + rng.nextDouble()).append("],")
        return text.append("[0,0]]}}],\"code\":\"Ok\"}").toString().toByteArray()
    }

    private fun fetcher(vararg answers: Pair<String, LocalAnswer>) = LocalFetcher { _, target -> answers.first { it.first == target }.second }

    private fun jobs(lane: () -> BleLane?, fetcher: LocalFetcher, xz: Boolean = false) = SidecarJobs(lane, fetcher, { xz }, System::nanoTime, { warnings += it }, Executor { it.run() }, null)

    private fun dump(name: String, kind: Shape, target: String, answer: LocalAnswer?, comma: FakeComma, expect: String) {
        assertTrue("$name: requests reached comma", comma.transcript.isNotEmpty())
        val dir = dir ?: return
        val rows = comma.transcript.joinToString(",") { r ->
            "{\"method\":\"${r.method}\",\"path\":\"${r.path}\",\"body_hex\":\"${TestVectors.hex(r.body)}\",\"status\":${r.status},\"next\":${r.next ?: "null"}}"
        }
        File(dir, "jobs-%02d-%s.json".format(written++, name)).writeText(
            "{\"name\":\"$name\",\"kind\":\"${kind.wire}\",\"target\":\"$target\",\"expect\":\"$expect\",\"answer_status\":${answer?.status ?: "null"},\"answer_hex\":\"${answer?.let { TestVectors.hex(it.body) } ?: ""}\",\"requests\":[$rows]}")
    }

    private fun run(name: String, kind: Shape, target: String, answer: LocalAnswer, xz: Boolean = false, expect: String = "done") {
        val comma = FakeComma(); val job = comma.submit(kind, target)
        val lane = lane(CommaLink(comma))
        jobs({ lane }, fetcher(target to answer), xz).pollOnce(lane)
        assertEquals(name, expect == "done", job.result != null)
        assertEquals(name, expect == "failed", job.failed)
        dump(name, kind, target, answer, comma, expect)
    }

    @Test fun routeAsGzipInSeveralChunks() = run("route-gzip", Shape.ROUTE, route, LocalAnswer(200, routeBody(450_000, 21)))
    @Test fun routeAsXzTheFallback() = run("route-xz", Shape.ROUTE, route, LocalAnswer(200, routeBody(150_000, 22)), xz = true)
    @Test fun routeAsIdentityWhenSmall() = run("route-identity", Shape.ROUTE, route, LocalAnswer(200, "{\"routes\":[]}".toByteArray()))
    @Test fun approvalAnswerWithItsStatus() = run("route-503", Shape.ROUTE, route, LocalAnswer(503, "{\"error\":\"RouteAwaitingApproval\"}".toByteArray()))
    @Test fun statusJob() = run("status-200", Shape.STATUS, "/status", LocalAnswer(200, "{\"service\":\"openpilot-sidecar\",\"api\":1,\"features\":[\"vehicle\"]}".toByteArray()))
    @Test fun healthJob() = run("health-200", Shape.HEALTH, "/health", LocalAnswer(200, "{}".toByteArray()))
    @Test fun statusJobWithA429() = run("status-429", Shape.STATUS, "/status", LocalAnswer(429, "{\"error\":\"TooManyRequests\"}".toByteArray()))
    @Test fun emptyAnswerOf204() = run("status-204", Shape.STATUS, "/status", LocalAnswer(204, ByteArray(0)))
    @Test fun anUnlistedStatusFailsTheJob() = run("status-500-failed", Shape.STATUS, "/status", LocalAnswer(500, "{}".toByteArray()), expect = "failed")

    @Test fun resumeAfterALostAcknowledgementAndAfterALostChunk() {
        for ((name, lostAck) in listOf("route-resume-lost-ack" to true, "route-resume-lost-chunk" to false)) {
            val body = routeBody(500_000, 23)
            val comma = FakeComma(); val job = comma.submit(Shape.ROUTE, route)
            val fetcher = fetcher(route to LocalAnswer(200, body))
            val first = CommaLink(comma).apply { if (lostAck) failAfterChunk = 2 else failBeforeChunk = 2 }
            val laneA = lane(first)
            val worker = jobs({ laneA }, fetcher)
            worker.pollOnce(laneA)
            val laneB = lane(CommaLink(comma))
            worker.pollOnce(laneB)
            assertTrue(name, job.result != null)
            dump(name, Shape.ROUTE, route, LocalAnswer(200, body), comma, "done")
        }
    }
}
