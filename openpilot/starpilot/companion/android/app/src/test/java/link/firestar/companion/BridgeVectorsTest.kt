package link.firestar.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the REAL bridge of job A produces (`bridge-vectors.json`, made by tools/btcompanion/gen_bridge_vectors.py from its own modules): the health answer, the `poll2` lists, the push frames. */
class BridgeVectorsTest {
    private val vectors = TestVectors.file("bridge-vectors.json")
    private val answers = vectors["answers_json"] as StrictJson.Obj

    @Test fun theRealHealthAnswerSelectsTheV2PathWithProtocolOne() {
        val features = BridgeFeatures.parse((answers["health"] as String).toByteArray())
        assertTrue("flags decide", features.jobsV2)
        assertTrue(features.push); assertTrue(features.xz)
        assertEquals("the larger of protocol 1 and sidecarProtocol 2", 2, features.protocol)
        // the health answer of the old bridge (production at the deployed line)
        val old = BridgeFeatures.parse("{\"protocol\":1,\"transport\":\"bluetooth\",\"readStream\":true,\"notificationStream\":true,\"galaxy\":\"secretgood\"}".toByteArray())
        assertFalse(old.jobsV2); assertFalse(old.push)
    }

    @Test fun theRealPollAnswersAreRead() {
        val empty = SidecarJobs.parseJobList((answers["poll2_empty"] as String).toByteArray())
        assertTrue(empty.jobs.isEmpty()); assertEquals(10_000L, empty.nextPollMs)
        val listed = SidecarJobs.parseJobList((answers["poll2_jobs"] as String).toByteArray())
        assertEquals(listOf(Shape.STATUS, Shape.HEALTH, Shape.ROUTE), listed.jobs.map { it.kind })
        assertTrue("the wake-up cadence while a job waits", listed.nextPollMs in 1_000L..10_000L)
        assertTrue(listed.jobs.none { it.claimed })
        for (job in listed.jobs) assertTrue("${job.kind} target passes the allow-list", Allowlist.check("GET", job.target).let { it.ok && it.shape == job.kind })
        val claimed = SidecarJobs.parseJobList((answers["poll2_claimed_by_another"] as String).toByteArray())
        assertEquals("only the job another phone started is claimed", listOf(false, false, true), claimed.jobs.map { it.claimed })
        assertTrue(claimed.jobs.last().nextSequence >= 1)
    }

    @Test fun framesSealedByTheRealBridgeOpenAndReassemble() {
        val key = TestVectors.hex(vectors["key_hex"] as String)
        val session = vectors["session"] as String
        val frames = vectors["frames"] as List<*>
        assertEquals(4, frames.size)
        for (item in frames) {
            item as StrictJson.Obj
            val frame = TestVectors.hex(item["frame_hex"] as String)
            val message = PushFrames.open(frame, key, session, 0)
            assertEquals(item["kind"], message.kind)
            assertEquals(TestVectors.int(item["counter"]).toLong(), message.counter)
            assertEquals((item["ts"] as StrictJson.Num).text.toLong(), message.tsMs)
            if (message.kind == "vehicle") {
                assertEquals(item["body"], String(message.body!!)); assertEquals(item["auth"], message.auth)
                assertTrue(Allowlist.check("POST", "/v1/vehicle", message.body!!).ok)
            } else { assertNull(message.body) }
            val assembler = PushFrames.Assembler()
            var whole: ByteArray? = null
            for (packet in item["packets_hex"] as List<*>) whole = assembler.add(TestVectors.hex(packet as String)) ?: whole
            assertArrayEquals(frame, whole)
        }
        assertTrue("the largest report with a 128-character token needs 2 or 3 packets", ((frames[1] as StrictJson.Obj)["packets_hex"] as List<*>).size in 2..3)
    }
}
