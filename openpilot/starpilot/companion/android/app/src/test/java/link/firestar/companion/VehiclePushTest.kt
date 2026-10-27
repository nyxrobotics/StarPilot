package link.firestar.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** The push receiver (VehiclePush.kt): the frames and packets of the reference, the freshness rule with injected delays, one POST in flight, nothing forwarded that is not fresh. */
class VehiclePushTest {
    private val key = TestVectors.KEY
    private val session = "00112233445566778899aabbccddeeff"
    private val token = "A".repeat(48)
    private val report = "{\"v\":1,\"seq\":1234,\"fix\":true,\"lat\":35.681201,\"lon\":139.767102,\"bearing\":250.5,\"speed\":13.89,\"age_ms\":120,\"src\":\"gnss\"}"

    // ---- frames, as the reference makes them ----------------------------------------------------------------------------------------------

    @Test fun framesOfTheReferenceAndOfTheProductionFramingOpen() {
        val vectors = TestVectors.file("push-frames.json")
        val vectorKey = TestVectors.hex(vectors["key_hex"] as String)
        val vectorSession = vectors["session"] as String
        var accepted = 0
        for (case in vectors["cases"] as List<*>) {
            case as StrictJson.Obj
            val frame = TestVectors.hex(case["frame_hex"] as String)
            if (case["expect"] == "refuse") {
                assertThrows("${case["name"]} was accepted", PushError::class.java) { PushFrames.open(frame, vectorKey, vectorSession, 0) }
                continue
            }
            accepted++
            val message = PushFrames.open(frame, vectorKey, vectorSession, 0)
            assertEquals(case["name"].toString(), case["kind"], message.kind)
            assertEquals(TestVectors.int(case["counter"]).toLong(), message.counter)
            assertEquals((case["ts"] as StrictJson.Num).text.toLong(), message.tsMs)
            if (message.kind == "vehicle") {
                assertEquals(case["name"].toString(), case["body"], String(message.body!!))
                assertEquals(case["auth"], message.auth)
            } else { assertNull(message.body); assertNull(message.auth) }
            // the notification packets the reference cut it into reassemble to the same frame
            val assembler = PushFrames.Assembler()
            var whole: ByteArray? = null
            for (packet in case["packets_hex"] as List<*>) whole = assembler.add(TestVectors.hex(packet as String)) ?: whole
            assertArrayEquals(frame, whole)
        }
        assertEquals(6, accepted)
    }

    @Test fun theAgeRuleAgreesWithTheReference() {
        for (case in TestVectors.file("push-frames.json")["ages"] as List<*>) {
            case as StrictJson.Obj
            val out = FreshnessFilter.withAge((case["body"] as String).toByteArray(), (case["add"] as StrictJson.Num).text.toLong())
            assertEquals(case["expect"], String(out))
        }
    }

    @Test fun aFrameSealedHereIsByteForByteTheFrameOfTheReference() {
        val vector = TestVectors.cases("push-frames.json").first { it["name"] == "vehicle-codec0" }
        val mine = seal(1, 5, "vehicle", 123456, report, token, nonce = TestVectors.nonce(1))
        assertEquals("the plaintext is the one comma's reference seals", vector["frame_hex"], TestVectors.hex(mine))
        assertEquals(5L, PushFrames.open(mine, key, session, 0).counter)
    }

    // ---- helpers that make frames and packets the way comma's bridge does -------------------------------------------------------------------

    private fun seal(frameId: Int, counter: Long, kind: String, ts: Long, reportText: String?, auth: String?, session: String = this.session, nonce: ByteArray = ByteArray(12).also { Random(frameId.toLong()).nextBytes(it) }): ByteArray {
        val text = "{\"t\":\"$kind\",\"c\":$counter,\"s\":\"$session\",\"ts\":$ts" + (reportText?.let { ",\"b\":$it,\"a\":\"$auth\"" } ?: "") + "}"
        return BleWire.seal(text.toByteArray(), key, BleWire.PUSH_DIRECTION, nonce)
    }

    private fun packets(frame: ByteArray, frameId: Int, att: Int = 244): List<ByteArray> {
        val step = att - PushFrames.HEADER
        val parts = (frame.indices step step).map { frame.copyOfRange(it, minOf(it + step, frame.size)) }
        return parts.mapIndexed { index, part -> ByteBuffer.allocate(PushFrames.HEADER + part.size).put(PushFrames.PACKET_TYPE.toByte()).putInt(frameId).put(index.toByte()).put(parts.size.toByte()).put(part).array() }
    }

    // ---- frame rules ------------------------------------------------------------------------------------------------------------------

    @Test fun theCounterMustRiseStrictly() {
        val frame = seal(1, 5, "wake", 1, null, null)
        assertEquals(5L, PushFrames.open(frame, key, session, 4).counter)
        for (last in listOf(5L, 6L, 100L)) assertThrows(PushError::class.java) { PushFrames.open(frame, key, session, last) }
    }

    @Test fun framesDoNotOpenUnderAnotherKeySessionOrDirection() {
        val frame = seal(1, 5, "vehicle", 1, report, token)
        assertThrows(PushError::class.java) { PushFrames.open(frame, ByteArray(32) { (it + 1).toByte() }, session, 0) }
        assertThrows(PushError::class.java) { PushFrames.open(frame, key, "ff".repeat(16), 0) }
        val asRequest = BleWire.seal("{\"t\":\"wake\",\"c\":9,\"s\":\"$session\",\"ts\":1}".toByteArray(), key, "request")
        assertThrows(PushError::class.java) { PushFrames.open(asRequest, key, session, 0) }
        for (direction in listOf("request", "response")) assertThrows(Exception::class.java) { BleWire.open(frame.copyOfRange(4, frame.size), key, direction) }
    }

    @Test fun aLengthPrefixThatDoesNotMatchIsRefused() {
        val frame = seal(1, 5, "wake", 1, null, null)
        val longer = frame + byteArrayOf(0)
        assertThrows(PushError::class.java) { PushFrames.open(longer, key, session, 0) }
        assertThrows(PushError::class.java) { PushFrames.open(frame.copyOfRange(0, frame.size - 1), key, session, 0) }
        assertThrows(PushError::class.java) { PushFrames.open(ByteArray(3), key, session, 0) }
    }

    @Test fun aLengthPrefixThatLiesIsRefusedEvenWhenTheCiphertextIsIntact() {
        val frame = seal(1, 5, "wake", 1, null, null)
        for (claimed in listOf(frame.size - 5, frame.size - 3, frame.size, 0, -1, Int.MAX_VALUE)) {
            val lying = frame.copyOf().also { java.nio.ByteBuffer.wrap(it).putInt(0, claimed) }
            assertThrows("a prefix of $claimed", PushError::class.java) { PushFrames.open(lying, key, session, 0) }
        }
        assertEquals(5L, PushFrames.open(frame, key, session, 0).counter)
    }

    @Test fun aReportWithoutAFixKeepsItsAgeEvenWhenItCarriesOne() {
        val body = "{\"v\":1,\"seq\":1,\"fix\":false,\"age_ms\":5}"
        assertEquals(body, String(FreshnessFilter.withAge(body.toByteArray(), 250)))
    }

    @Test fun aReportThatGrowsPast512BytesWithItsRaisedAgeIsNotPosted() {
        val head = "{\"v\":1,\"seq\":1,\"fix\":true,\"lat\":35.11111111,\"lon\":139.11111111,\"age_ms\":99,\"snap_m\":"
        val body = head + "1".repeat(512 - head.length - 1) + "}"
        assertEquals(512, body.length)
        assertTrue(Allowlist.check("POST", "/v1/vehicle", body.toByteArray()).ok)
        val r = rig()
        r.deliver(seal(1, 1, "wake", 0, null, null), 1, 40)
        r.deliver(seal(2, 2, "vehicle", 1000, body, token), 2, 1040 + 20) // 20 ms late: age_ms 99 becomes 119 (more digits would need 100 -> 1000+)
        r.runWorker()
        assertEquals("119 is as long as 99 plus one digit: 513 bytes", 0, r.posts.size)
        assertEquals(1, r.push.droppedBad)
    }

    @Test fun hostileFramesThatAreSealedCorrectlyAreStillRefused() {
        val ok = report
        val hostile = listOf(
            "{\"t\":\"vehicle\",\"c\":5,\"s\":\"$session\",\"ts\":1,\"b\":$ok,\"a\":\"$token\",\"x\":1}",
            "{\"t\":\"vehicle\",\"c\":5,\"s\":\"$session\",\"ts\":1,\"b\":$ok,\"a\":\"short\"}",
            "{\"t\":\"vehicle\",\"c\":5,\"s\":\"$session\",\"ts\":1,\"b\":$ok,\"a\":\"bad token with space\"}",
            "{\"t\":\"vehicle\",\"c\":5,\"s\":\"$session\",\"ts\":1,\"b\":$ok,\"a\":\"${"é".repeat(12)}\"}",
            "{\"t\":\"vehicle\",\"c\":5,\"s\":\"$session\",\"ts\":1,\"b\":$ok,\"a\":\"${"A".repeat(129)}\"}",
            "{\"t\":\"vehicle\",\"c\":5,\"s\":\"$session\",\"ts\":1,\"b\":{\"v\":1,\"seq\":1,\"cmd\":1},\"a\":\"$token\"}",
            "{\"t\":\"vehicle\",\"c\":5,\"s\":\"$session\",\"ts\":1,\"b\":{\"v\":2,\"seq\":1},\"a\":\"$token\"}",
            "{\"t\":\"vehicle\",\"c\":5,\"s\":\"$session\",\"ts\":1,\"b\":{\"v\":1,\"v\":1},\"a\":\"$token\"}",
            "{\"t\":\"vehicle\",\"c\":5,\"s\":\"$session\",\"ts\":1,\"b\":{\"v\":1,\"lat\":null},\"a\":\"$token\"}",
            "{\"t\":\"vehicle\",\"c\":5,\"s\":\"$session\",\"ts\":1,\"b\":{\"v\":1,\"lat\":1e999},\"a\":\"$token\"}",
            "{\"t\":\"vehicle\",\"c\":5,\"s\":\"$session\",\"ts\":1,\"b\":[1],\"a\":\"$token\"}",
            "{\"t\":\"vehicle\",\"c\":5,\"s\":\"$session\",\"ts\":1,\"a\":\"$token\"}",
            "{\"t\":\"vehicle\",\"c\":5,\"s\":\"$session\",\"ts\":1,\"b\":$ok}",
            "{\"t\":\"wake\",\"c\":5,\"s\":\"$session\",\"ts\":1,\"b\":$ok}",
            "{\"t\":\"wake\",\"c\":5,\"s\":\"$session\",\"ts\":1,\"a\":\"$token\"}",
            "{\"t\":\"command\",\"c\":5,\"s\":\"$session\",\"ts\":1}",
            "{\"t\":\"wake\",\"c\":\"5\",\"s\":\"$session\",\"ts\":1}", "{\"t\":\"wake\",\"c\":5.0,\"s\":\"$session\",\"ts\":1}", "{\"t\":\"wake\",\"c\":true,\"s\":\"$session\",\"ts\":1}",
            "{\"t\":\"wake\",\"c\":0,\"s\":\"$session\",\"ts\":1}", "{\"t\":\"wake\",\"c\":5,\"s\":\"$session\",\"ts\":-1}", "{\"t\":\"wake\",\"c\":5,\"s\":\"$session\",\"ts\":\"1\"}",
            "{\"t\":\"wake\",\"c\":5,\"ts\":1}", "{\"t\":\"wake\",\"c\":5,\"s\":7,\"ts\":1}", "[]", "\"wake\"", "{\"t\":\"wake\",\"c\":5,\"s\":\"$session\",\"ts\":1,", "",
        )
        for ((i, text) in hostile.withIndex()) {
            val frame = try { BleWire.seal(text.toByteArray(), key, BleWire.PUSH_DIRECTION) } catch (_: IllegalArgumentException) { continue }
            assertThrows("hostile frame $i was accepted: $text", PushError::class.java) { PushFrames.open(frame, key, session, 0) }
        }
    }

    @Test fun aCodecTwoFrameWithABodyIsNotAPush() {
        val frame = BleWire.sealRaw("{\"t\":\"wake\",\"c\":5,\"s\":\"$session\",\"ts\":1}".toByteArray(), byteArrayOf(1), key, BleWire.PUSH_DIRECTION)
        assertThrows(PushError::class.java) { PushFrames.open(frame, key, session, 0) }
    }

    @Test fun fuzzNothingButAPushErrorEscapesAFlippedOrCutFrame() {
        val rng = Random(3)
        val frame = seal(1, 5, "vehicle", 1, report, token)
        repeat(3000) {
            val data = frame.copyOf()
            repeat(1 + rng.nextInt(3)) { data[4 + rng.nextInt(data.size - 4)] = (data[4 + rng.nextInt(data.size - 4)].toInt() xor (1 shl rng.nextInt(8))).toByte() }
            val cut = if (rng.nextDouble() < 0.3) data.copyOf(rng.nextInt(data.size)) else data
            val mutated = if (cut === data) data else cut
            try { PushFrames.open(mutated, key, session, 0); if (!mutated.contentEquals(frame)) throw AssertionError("a damaged frame opened") } catch (_: PushError) { /* refused */ }
        }
    }

    @Test fun fuzzTheAssemblerWithRandomPacketsOnlyRaisesPushError() {
        val rng = Random(4)
        val assembler = PushFrames.Assembler()
        repeat(5000) {
            val packet = ByteArray(rng.nextInt(40)).also { rng.nextBytes(it) }
            if (it % 3 == 0 && packet.size > 8) { packet[0] = PushFrames.PACKET_TYPE.toByte(); packet[5] = rng.nextInt(10).toByte(); packet[6] = (1 + rng.nextInt(9)).toByte() }
            try { assembler.add(packet) } catch (_: PushError) { /* refused, the assembler reset */ }
        }
    }

    // ---- packets ----------------------------------------------------------------------------------------------------------------------

    @Test fun packetsRoundTripAtAnyPacketSize() {
        val frame = seal(1, 5, "vehicle", 1, report, "C".repeat(128))
        for (att in listOf(40, 120, 244)) {
            val parts = packets(frame, 77, att)
            if (parts.size > PushFrames.MAX_PACKETS) continue
            val assembler = PushFrames.Assembler()
            var out: ByteArray? = null
            for (p in parts) out = assembler.add(p) ?: out
            assertArrayEquals(frame, out)
        }
    }

    @Test fun assemblerRules() {
        val three = packets(ByteArray(700) { 1 }, 1)
        assertEquals(3, three.size)
        PushFrames.Assembler().apply { add(three[0]); assertThrows(PushError::class.java) { add(three[2]) } }
        val a = PushFrames.Assembler()
        a.add(three[0])
        val other = packets(ByteArray(700) { 2 }, 2)
        a.add(other[0]) // a newer frame replaces the unfinished one
        assertNull(a.add(other[1]))
        assertArrayEquals(ByteArray(700) { 2 }, a.add(other[2]))
        assertThrows("the same finished frame again", PushError::class.java) { a.add(other[0]) }
        assertThrows("a frame that starts in the middle", PushError::class.java) { PushFrames.Assembler().add(three[1]) }
        val bad = listOf(byteArrayOf(), byteArrayOf(0x10, 0, 0, 0, 0, 0, 0), byteArrayOf(0x11, 0, 0, 0, 0, 0, 0, 0, 0), byteArrayOf(0x10, 0, 0, 0, 1, 5, 2, 100), byteArrayOf(0x10, 0, 0, 0, 1, 0, 0, 100), byteArrayOf(0x10, 0, 0, 0, 1, 0, 9, 100))
        for (packet in bad) assertThrows(PushError::class.java) { PushFrames.Assembler().add(packet) }
        assertThrows("more than eight packets", PushError::class.java) {
            val parts = packets(ByteArray(PushFrames.MAX_PACKETS * 237 + 1), 3)
            PushFrames.Assembler().apply { parts.forEach { add(it) } }
        }
    }

    // ---- the freshness rule, with injected delays -----------------------------------------------------------------------------------------

    /** Frames sent at 1 Hz (comma clock `ts`), each delayed by delays[i] on the way, the Companion's clock [offset] ahead: (ts, rx, delay). */
    private fun stream(delays: List<Long>, offset: Long = 10_000_000, first: Long = 0) = delays.mapIndexed { i, d -> Triple(first + 1000 * i, first + 1000 * i + 40 + d + offset, d) }

    private fun run(frames: List<Triple<Long, Long, Long>>, dwell: Long = 0): List<Triple<Long, Long, FreshnessFilter.Decision>> {
        val filter = FreshnessFilter()
        return frames.map { (ts, rx, delay) -> val late = filter.observe(rx, ts); Triple(delay, late, filter.decide(late, rx, rx + dwell)) }
    }

    @Test fun normalJitterIsForwardedAndTheAgeGrowsByWhatWasWaited() {
        for ((delay, late, decision) in run(stream(listOf<Long>(0, 30, 10, 0, 80, 20) + List(20) { 15L }))) {
            assertTrue(decision.forward); assertTrue(Math.abs(late - delay) <= 80); assertTrue(decision.addMs <= 100)
        }
    }

    @Test fun aTwoSecondAndAFiveSecondDelayAreDroppedAndAShortOneIsForwardedWithItsDelay() {
        val delays = List(10) { 0L } + 800L + List(5) { 0L } + 2000L + List(5) { 0L } + 5000L + List(5) { 0L }
        val results = run(stream(delays))
        val by = results.filter { it.first > 0 }.associateBy { it.first }
        assertTrue(by.getValue(800).third.forward && by.getValue(800).third.addMs in 750..850)
        assertFalse(by.getValue(2000).third.forward); assertEquals("late", by.getValue(2000).third.reason)
        assertFalse(by.getValue(5000).third.forward)
        assertTrue(results.filter { it.first == 0L }.all { it.third.forward })
    }

    @Test fun anyClockOffsetGivesTheSameDecisions() {
        val delays = List(8) { 0L } + 1500L + List(4) { 0L }
        assertEquals(run(stream(delays, 0)).map { it.third.forward }, run(stream(delays, -9_999_999_999)).map { it.third.forward })
    }

    @Test fun dwellInTheCompanionCountsAndCanCauseADrop() {
        val frames = stream(List(6) { 0L })
        assertTrue(run(frames, 1200).none { it.third.forward })
        assertTrue(run(frames, 300).all { it.third.forward })
        assertTrue("exactly 1000 ms is still fresh", run(frames, 1000).all { it.third.forward })
        assertTrue("1001 ms is not", run(frames, 1001).none { it.third.forward })
    }

    @Test fun aResetForgetsTheMinimumAfterAReconnect() {
        val filter = FreshnessFilter()
        for (i in 0 until 10) filter.observe(1000L * i + 500, 1000L * i)
        filter.reset()
        assertEquals(0L, filter.observe(50_000, 0))
    }

    @Test fun theWakeFrameAfterPushEnableSeedsAnUnloadedMinimum() {
        val filter = FreshnessFilter()
        filter.observe(500_000_040, 500_000_000)
        val late = filter.observe(500_000_000 + 1000 + 40 + 1500, 500_000_000 + 1000)
        assertEquals(1500L, late)
        assertFalse(filter.decide(late, 0, 0).forward)
    }

    @Test fun aLongWindowRemembersAnUnloadedFrameFromMinutesAgo() {
        val filter = FreshnessFilter()
        filter.observe(40, 0)
        var late = 0L
        for (i in 1 until 300) late = filter.observe(1000L * i + 1240, 1000L * i) // five minutes of frames that are all 1.2 s late: a persistent queue
        assertEquals(1200L, late)
        assertFalse(filter.decide(late, 0, 0).forward)
        val short = FreshnessFilter(window = 60)
        short.observe(40, 0)
        for (i in 1 until 300) late = short.observe(1000L * i + 1240, 1000L * i)
        assertEquals("a window of 60 frames forgets the persistent delay", 0L, late)
    }

    @Test fun propertyAForwardedPositionIsNeverShownYoungerThanItIs() {
        val rng = Random(11)
        var checked = 0
        repeat(200) {
            val delays = List(80) { listOf(0L, 0L, 0L, 20L, 60L, 400L, 900L, 1500L, 3000L, 7000L)[rng.nextInt(10)] }
            val filter = FreshnessFilter()
            var unloadedSeen = false
            for ((ts, rx, delay) in stream(delays)) {
                unloadedSeen = unloadedSeen || delay <= 60
                val late = filter.observe(rx, ts)
                val decision = filter.decide(late, rx, rx + listOf(0L, 0L, 50L, 200L)[rng.nextInt(4)])
                if (decision.forward && unloadedSeen) { checked++; assertTrue(decision.addMs >= delay - 60 - 100) }
            }
        }
        assertTrue(checked > 1000)
    }

    @Test fun withAgeAddsOnlyForAFixAndKeepsEverythingElseAsItWas() {
        val out = String(FreshnessFilter.withAge(report.toByteArray(), 250))
        assertEquals(report.replace("\"age_ms\":120", "\"age_ms\":370"), out)
        val noFix = "{\"v\":1,\"seq\":1,\"fix\":false}"
        assertEquals(noFix, String(FreshnessFilter.withAge(noFix.toByteArray(), 250)))
        val noAge = "{\"v\":1,\"seq\":1,\"fix\":true,\"lat\":1.5,\"lon\":2.5}"
        assertEquals(noAge, String(FreshnessFilter.withAge(noAge.toByteArray(), 250)))
        assertThrows(PushError::class.java) { FreshnessFilter.withAge("{\"v\":1,\"fix\":true,\"age_ms\":true}".toByteArray(), 1) }
        assertThrows(PushError::class.java) { FreshnessFilter.withAge("not json".toByteArray(), 1) }
    }

    @Test fun theForwarderKeepsOneInFlightAndTheNewestPending() {
        val forwarder = Forwarder<String>()
        assertEquals(1L to "a", forwarder.offer(1, "a"))
        assertNull(forwarder.offer(2, "b")); assertNull(forwarder.offer(3, "c"))
        assertEquals(1, forwarder.droppedStale)
        assertEquals(3L to "c", forwarder.done())
        assertNull(forwarder.done())
        assertEquals(4L to "d", forwarder.offer(4, "d"))
    }

    // ---- the receiver end to end, with a link, a clock and a POST that are injected -------------------------------------------------------

    private class FakeLink(val key: ByteArray, val session: String, var enableStatus: Int = 200) : PushLink {
        val steps = CopyOnWriteArrayList<String>()
        override val pushKey get() = key
        override val pushSession get() = session
        override fun subscribe() { steps += "subscribe" }
        override fun pushEnable(): Int { steps += "push-enable"; if (enableStatus < 0) throw IOException("link lost"); return enableStatus }
    }

    private class Rig(val key: ByteArray, val session: String) {
        var now = 1_000_000L
        val posts = CopyOnWriteArrayList<Pair<String, String>>() // body, token
        val warnings = CopyOnWriteArrayList<String>()
        val wakes = java.util.concurrent.atomic.AtomicInteger()
        var postStatus = 204
        var postFailure: Exception? = null
        var postGate: CountDownLatch? = null
        val queued = ArrayDeque<Runnable>()
        val link = FakeLink(key, session)
        /** The receiver's worker is run by the test, so that time can pass between a frame's arrival and its POST (a blocked thread). */
        val manual = Executor { queued.addLast(it) }
        val push = VehiclePush(link, { body, token ->
            postGate?.await(10, TimeUnit.SECONDS)
            postFailure?.let { throw it }
            posts += String(body) to token
            postStatus
        }, { now }, manual, { wakes.incrementAndGet() }, { warnings += it })
        fun runWorker() { while (queued.isNotEmpty()) queued.removeFirst().run() }
    }

    private fun rig() = Rig(key, session)

    private fun Rig.deliver(frame: ByteArray, frameId: Int, arrive: Long = now) {
        now = arrive
        for (p in packets(frame, frameId)) push.onPacket(p)
    }

    @Test fun enableSubscribesFirstThenSendsPushEnableAndSaysWhetherCommaBound() {
        val r = rig()
        assertTrue(r.push.enable())
        assertEquals(listOf("subscribe", "push-enable"), r.link.steps.toList())
        assertTrue(r.push.bound)
        r.link.enableStatus = 503
        assertFalse(r.push.enable()); assertFalse(r.push.bound)
        r.link.enableStatus = -1
        assertFalse(r.push.enable())
        assertTrue(r.warnings.size == 2)
    }

    @Test fun aFreshVehicleFrameIsPostedWithItsTokenAndTheAgeRaised() {
        val r = rig()
        r.deliver(seal(1, 1, "wake", 500, null, null), 1, 540) // the calibration frame
        assertEquals(1, r.wakes.get())
        r.deliver(seal(2, 2, "vehicle", 1500, report, token), 2, 1540 + 30) // 30 ms of delay above the best case
        r.runWorker()
        assertEquals(1, r.posts.size)
        assertEquals(token, r.posts[0].second)
        assertEquals(report.replace("\"age_ms\":120", "\"age_ms\":150"), r.posts[0].first)
        assertEquals(1, r.push.forwarded)
        assertTrue(Allowlist.check("POST", "/v1/vehicle", r.posts[0].first.toByteArray()).ok)
    }

    @Test fun aFrameDelayedInTheRadioQueueIsForwardedWithItsDelayOrDroppedByHowLateItIs() {
        val r = rig()
        r.deliver(seal(1, 1, "wake", 0, null, null), 1, 40)
        var counter = 2L
        val forwardedAges = ArrayList<String>()
        for ((delay, shouldForward) in listOf(800L to true, 2000L to false, 5000L to false, 0L to true)) {
            val ts = counter * 1000
            val before = r.posts.size
            r.deliver(seal(counter.toInt(), counter, "vehicle", ts, report, token), counter.toInt(), ts + 40 + delay)
            r.runWorker()
            assertEquals("delay $delay ms", shouldForward, r.posts.size > before)
            if (shouldForward) forwardedAges += Regex("\"age_ms\":(\\d+)").find(r.posts.last().first)!!.groupValues[1]
            counter++
        }
        assertEquals("the 800 ms delay is added to the age of 120 ms", listOf("920", "120"), forwardedAges)
        assertEquals(2, r.push.droppedLate)
        assertEquals(2, r.push.forwarded)
    }

    @Test fun aCompanionThreadThatWasBlockedBeforeThePostDropsTheFrame() {
        val r = rig()
        r.deliver(seal(1, 1, "wake", 0, null, null), 1, 40)
        r.deliver(seal(2, 2, "vehicle", 1000, report, token), 2, 1040)
        r.now += 1200 // the worker thread did not run for 1.2 s
        r.runWorker()
        assertTrue(r.posts.isEmpty())
        assertEquals(1, r.push.droppedLate)
        r.deliver(seal(3, 3, "vehicle", 2000, report, token), 3, 2040)
        r.now += 300
        r.runWorker()
        assertEquals("a dwell of 300 ms is added to the age", report.replace("\"age_ms\":120", "\"age_ms\":420"), r.posts.single().first)
    }

    @Test fun aSlowLocalPostLeavesOnlyTheNewestFrameAfterIt() {
        val r = rig()
        r.deliver(seal(1, 1, "wake", 0, null, null), 1, 40)
        r.deliver(seal(2, 2, "vehicle", 1000, report, token), 2, 1040)
        // the first POST is in flight (taken from the queue but not finished): four more frames arrive meanwhile
        val running = r.queued.removeFirst()
        r.postGate = CountDownLatch(1)
        val thread = Thread { running.run() }
        thread.start()
        Thread.sleep(50)
        for (k in 3..6) r.deliver(seal(k, k.toLong(), "vehicle", k * 1000L, report.replace("\"seq\":1234", "\"seq\":$k"), token), k, k * 1000L + 40)
        r.postGate!!.countDown()
        thread.join(10_000)
        r.runWorker()
        assertEquals("the first and the newest only", 2, r.posts.size)
        assertTrue(r.posts[1].first.contains("\"seq\":6"))
        assertEquals(3, r.push.droppedStale)
    }

    @Test fun aStallThatDeliversFiveFramesAtOnceForwardsOnlyTheNewest() {
        val r = rig()
        r.deliver(seal(1, 1, "wake", 0, null, null), 1, 40)
        for (k in 2..31) { r.deliver(seal(k, k.toLong(), "vehicle", k * 1000L, report, token), k, k * 1000L + 40); r.runWorker() } // 30 normal frames
        r.posts.clear()
        val burstAt = 36_000L + 40 // all five arrive when the newest one was sent; the four before it were held up for 4, 3, 2 and 1 s
        for (k in 32..36) r.deliver(seal(k, k.toLong(), "vehicle", k * 1000L, report.replace("\"seq\":1234", "\"seq\":$k"), token), k, burstAt)
        r.runWorker()
        assertEquals(1, r.posts.size)
        assertTrue(r.posts.single().first.contains("\"seq\":36"))
    }

    @Test fun aRefusedPostAFailedPostAndAnOddStatusAreCountedAndTheNextFrameStillGoes() {
        val r = rig()
        r.deliver(seal(1, 1, "wake", 0, null, null), 1, 40)
        r.postStatus = 401
        r.deliver(seal(2, 2, "vehicle", 1000, report, token), 2, 1040); r.runWorker()
        r.postStatus = 429
        r.deliver(seal(3, 3, "vehicle", 2000, report, token), 3, 2040); r.runWorker()
        r.postFailure = IOException("connection refused")
        r.deliver(seal(4, 4, "vehicle", 3000, report, token), 4, 3040); r.runWorker()
        r.postFailure = null; r.postStatus = 204
        r.deliver(seal(5, 5, "vehicle", 4000, report, token), 5, 4040); r.runWorker()
        assertEquals(2, r.push.refusedByX10); assertEquals(1, r.push.postFailed); assertEquals(1, r.push.forwarded)
    }

    @Test fun framesThatDoNotPassAreDroppedAndCountedAndNeverPosted() {
        val r = rig()
        r.deliver(seal(1, 1, "wake", 0, null, null), 1, 40)
        val good = seal(2, 2, "vehicle", 1000, report, token)
        r.deliver(good, 2, 1040); r.runWorker()
        assertEquals(1, r.posts.size)
        r.deliver(good, 3, 1041); r.runWorker() // a replay: the counter did not rise
        r.deliver(seal(9, 9, "vehicle", 2000, report, token, session = "ff".repeat(16)), 9, 2040) // another session
        r.deliver(seal(10, 10, "vehicle", 3000, "{\"v\":1,\"cmd\":1}", token), 10, 3040)
        r.deliver(ByteArray(300) { 7 }, 11, 4040)
        r.runWorker()
        assertEquals(1, r.posts.size)
        assertTrue(r.push.droppedBad >= 4)
    }

    @Test fun aNewConnectionStartsWithAFreshCounterAndWindow() {
        val first = rig()
        first.deliver(seal(1, 50, "wake", 0, null, null), 1, 40)
        val second = rig() // the reconnect makes a new receiver
        second.deliver(seal(1, 1, "wake", 0, null, null), 1, 40)
        assertEquals(1, second.wakes.get())
        first.deliver(seal(2, 2, "vehicle", 1000, report, token), 2, 1040)
        assertEquals("the old receiver's counter is at 50", 1, first.push.droppedBad)
    }

    @Test fun noLogLineHoldsATokenACoordinateOrABody() {
        val r = rig()
        r.link.enableStatus = 503
        r.push.enable()
        r.deliver(seal(1, 1, "vehicle", 1, "{\"v\":1,\"lat\":35.681201,\"cmd\":1}", token), 1, 41)
        r.deliver(seal(2, 2, "vehicle", 1, report, token + " bad"), 2, 42)
        r.deliver(ByteArray(64) { 3 }, 3, 43)
        assertTrue(r.warnings.isNotEmpty())
        for (line in r.warnings) for (secret in listOf(token, "35.681201", "139.767102", "gnss", "cmd", "\"v\":1")) assertFalse("a warning holds '$secret': $line", line.contains(secret))
    }
}
