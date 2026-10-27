package link.firestar.companion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class LinkMiscTest {
    // ---- what the bridge announces ----------------------------------------------------------------------------------------------------

    private fun features(text: String) = BridgeFeatures.parse(text.toByteArray())

    @Test fun aBridgeThatAnnouncesItsFlags() {
        val f = features("""{"protocol":2,"transport":"bluetooth","poll2":true,"results2":true,"push":true,"galaxy":"secretgood"}""")
        assertEquals(2, f.protocol); assertTrue(f.jobsV2); assertTrue(f.push); assertTrue(f.xz)
    }

    @Test fun theBridgeThatKeepsProtocolOneForIosAnnouncesTheSidecarProtocolNextToTheFlags() {
        // what the bridge of job A answers: `protocol` stays 1 because the iOS Galaxy app refuses any other value
        val f = features("""{"protocol":1,"transport":"bluetooth","readStream":true,"notificationStream":true,"galaxy":"secretgood","sidecarProtocol":2,"poll2":true,"results2":true,"push":true}""")
        assertEquals(2, f.protocol); assertTrue(f.jobsV2); assertTrue(f.push)
    }

    @Test fun theFlagsDecideNeverTheProtocolField() {
        assertTrue("flags with protocol 1 and no level", features("""{"protocol":1,"poll2":true,"results2":true,"push":true}""").jobsV2)
        assertTrue("flags and no protocol field at all", features("""{"poll2":true,"results2":true}""").jobsV2)
        assertFalse("a level without the flags", features("""{"protocol":1,"sidecarProtocol":2}""").jobsV2)
        assertFalse("protocol 2 without the flags", features("""{"protocol":2,"transport":"bluetooth"}""").jobsV2)
        assertFalse("a level that is not a number does not matter, the flags are not booleans", features("""{"protocol":1,"sidecarProtocol":"2","poll2":"true","results2":1}""").jobsV2)
    }

    @Test fun theOldBridgeAndAnythingUnreadableKeepTheLegacyPath() {
        val old = features("""{"protocol":1,"transport":"bluetooth","readStream":true,"notificationStream":true,"galaxy":"secretgood"}""")
        assertEquals(1, old.protocol); assertFalse(old.jobsV2); assertFalse(old.push)
        for (text in listOf("", "{}", "[]", "not json", "{\"poll2\":true,\"results2\":true}x", """{"poll2":true,"poll2":true,"results2":true} {}""")) {
            val f = features(text)
            assertFalse(text, f.jobsV2); assertFalse(text, f.push)
        }
    }

    @Test fun everyFlagIsNeededAndMustBeTheBooleanTrue() {
        assertFalse(features("""{"protocol":2,"poll2":true}""").jobsV2)
        assertFalse(features("""{"protocol":2,"results2":true}""").jobsV2)
        assertFalse(features("""{"protocol":2,"poll2":"true","results2":1}""").jobsV2)
        assertFalse(features("""{"poll2":true,"results2":true,"push":1}""").push)
        assertFalse("xz can be switched off by the bridge", features("""{"poll2":true,"results2":true,"xz":false}""").xz)
        assertFalse(BridgeFeatures.LEGACY.jobsV2)
    }

    // ---- reconnect cadence -----------------------------------------------------------------------------------------------------------

    @Test fun retriesAfterOneTwoFourEightAndThenEveryTenSeconds() {
        val backoff = ReconnectBackoff()
        assertTrue("the first attempt is at once", backoff.ready(0))
        val waits = ArrayList<Long>()
        var now = 0L
        repeat(8) {
            backoff.attempt(now); backoff.failed()
            var waited = 0L
            while (!backoff.ready(now + waited)) waited += 100
            waits += waited
            now += waited
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L, 10_000L, 10_000L), waits)
        backoff.attempt(now); backoff.succeeded()
        assertTrue("after a success the next drop starts again at once", backoff.ready(now))
        backoff.attempt(now); backoff.failed()
        assertFalse(backoff.ready(now + 999)); assertTrue(backoff.ready(now + 1_000))
    }

    // ---- the route worker hands a v2 bridge to the job worker and leaves an old bridge alone --------------------------------------------

    private class LegacyLink : RouteLink {
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun request(value: JSONObject, deadlineSeconds: Long): JSONObject { calls += value.getString("path"); return JSONObject().put("status", 204) }
        override fun routePriority(high: Boolean) = Unit
    }

    @Test fun aV2BridgeIsPolledWithPoll2AndTheLegacyPollIsNotUsed() {
        val comma = FakeComma(); val job = comma.submit(Shape.STATUS, "/status")
        val lane = BleLane(CommaLink(comma), System::nanoTime, false)
        val legacy = LegacyLink()
        val fetcher = LocalFetcher { _, _ -> LocalAnswer(200, "{\"service\":\"openpilot-sidecar\"}".toByteArray()) }
        val worker = SidecarRouteWorker({ legacy }, false, { lane }, { false }, fetcher)
        worker.warning = {}
        try {
            worker.tick()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!job.finished && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue("the status job was answered through poll2", job.finished)
            assertEquals(emptyList<String>(), legacy.calls.toList())
            assertTrue(comma.rejected.isEmpty())
        } finally { worker.close(); lane.close() }
    }

    @Test fun anOldBridgeKeepsTheLegacyPollAndChunkCalls() {
        val legacy = LegacyLink()
        val worker = SidecarRouteWorker({ legacy }, false, { null }, { false })
        worker.warning = {}
        worker.tick()
        assertEquals(listOf("/_bridge/sidecar/poll"), legacy.calls.toList())
        worker.close()
    }

    @Test fun wakeRunsATickOnTheWorkerThread() {
        val legacy = LegacyLink()
        val worker = SidecarRouteWorker({ legacy }, false, { null }, { false })
        worker.warning = {}
        worker.wake()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (legacy.calls.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(listOf("/_bridge/sidecar/poll"), legacy.calls.toList())
        worker.close()
    }
}
