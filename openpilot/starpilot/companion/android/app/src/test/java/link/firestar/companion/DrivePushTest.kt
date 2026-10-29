package link.firestar.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * The drive status on the Bluetooth push path (contract version 4; `DrivePush.kt`, `DriveContract.kt`, `VehiclePush.kt`): the frames of the kind `drive` open or are refused, a drive frame is
 * forwarded to `POST /v1/drive` and nowhere else with its own forwarder and its own counters, the freshness rule is the position's, the announcement and the health flag, and a seeded hostile
 * fuzz that must never forward what the allow-list refuses. The position path is held unchanged by `VehiclePushTest`, which is not touched.
 */
class DrivePushTest {
    private val key = TestVectors.KEY
    private val session = "00112233445566778899aabbccddeeff"
    private val positionToken = "P".repeat(48)
    private val driveToken = "D".repeat(48)
    private val position = "{\"v\":1,\"seq\":1234,\"fix\":true,\"lat\":35.681201,\"lon\":139.767102,\"bearing\":250.5,\"speed\":13.89,\"age_ms\":120,\"src\":\"gnss\"}"
    private fun drive(seq: Int = 7, age: Int = 12) =
        "{\"v\":1,\"seq\":$seq,\"age_ms\":$age,\"st\":\"en\",\"rdy\":true,\"lat\":true,\"lon\":false,\"al\":1,\"ak\":\"lane\",\"a1\":\"Steer Left to Start Lane Change\",\"a2\":\"Once Safe\",\"lc\":\"pre\",\"ld\":\"l\",\"lp\":\"man\",\"crv\":true,\"stp\":true}"
    private val minimal = "{\"v\":1,\"seq\":3,\"age_ms\":40}"

    // ---- helpers that make frames and packets the way comma's bridge does ----------------------------------------------------------------

    private fun seal(frameId: Int, counter: Long, kind: String, ts: Long, reportText: String?, auth: String?, session: String = this.session): ByteArray {
        val text = "{\"t\":\"$kind\",\"c\":$counter,\"s\":\"$session\",\"ts\":$ts" + (reportText?.let { ",\"b\":$it,\"a\":\"$auth\"" } ?: "") + "}"
        return BleWire.seal(text.toByteArray(), key, BleWire.PUSH_DIRECTION, ByteArray(12).also { Random(frameId.toLong()).nextBytes(it) })
    }

    private fun sealText(text: String): ByteArray = BleWire.seal(text.toByteArray(Charsets.UTF_8), key, BleWire.PUSH_DIRECTION)

    private fun packets(frame: ByteArray, frameId: Int, att: Int = 244): List<ByteArray> {
        val step = att - PushFrames.HEADER
        val parts = (frame.indices step step).map { frame.copyOfRange(it, minOf(it + step, frame.size)) }
        return parts.mapIndexed { index, part -> ByteBuffer.allocate(PushFrames.HEADER + part.size).put(PushFrames.PACKET_TYPE.toByte()).putInt(frameId).put(index.toByte()).put(parts.size.toByte()).put(part).array() }
    }

    /** The plaintext of a frame with the fields as given (a null field is left out), for the frames that are wrong on purpose. */
    private fun plain(t: String = "drive", c: String = "5", s: String = session, ts: String = "1", b: String? = drive(), a: String? = driveToken, extra: String = ""): String =
        "{\"t\":\"$t\",\"c\":$c,\"s\":\"$s\",\"ts\":$ts" + (b?.let { ",\"b\":$it" } ?: "") + (a?.let { ",\"a\":\"$it\"" } ?: "") + extra + "}"

    private class FakeLink(val key: ByteArray, val session: String, var nav: Boolean = false, var drive: Boolean = true) : PushLink {
        override val carriesNav get() = nav
        override val carriesDrive get() = drive
        override val pushKey get() = key
        override val pushSession get() = session
        override fun subscribe() {}
        override fun pushEnable(): Int = 200
    }

    private class Rig(val key: ByteArray, val session: String, drive: Boolean = true) {
        var now = 1_000_000L
        val posts = CopyOnWriteArrayList<Pair<String, String>>()       // the position path: body, token
        val drivePosts = CopyOnWriteArrayList<Pair<String, String>>()  // the drive path: body, token
        val warnings = CopyOnWriteArrayList<String>()
        val wakes = java.util.concurrent.atomic.AtomicInteger()
        var postStatus = 204; var drivePostStatus = 204
        var postFailure: Exception? = null; var drivePostFailure: Exception? = null
        var postGate: CountDownLatch? = null; var drivePostGate: CountDownLatch? = null
        val postEntered = CountDownLatch(1); val drivePostEntered = CountDownLatch(1)
        val queued = ArrayDeque<Runnable>(); val queuedDrive = ArrayDeque<Runnable>()
        val link = FakeLink(key, session, drive = drive)
        val push = VehiclePush(
            link,
            post = { body, token -> postEntered.countDown(); postGate?.await(10, TimeUnit.SECONDS); postFailure?.let { throw it }; posts += String(body) to token; postStatus },
            clock = { now },
            worker = Executor { queued.addLast(it) },
            onWake = { wakes.incrementAndGet() },
            warn = { warnings += it },
            postDrive = { body, token -> drivePostEntered.countDown(); drivePostGate?.await(10, TimeUnit.SECONDS); drivePostFailure?.let { throw it }; drivePosts += String(body) to token; drivePostStatus },
            driveWorker = Executor { queuedDrive.addLast(it) },
        )
        fun runWorker() { while (queued.isNotEmpty()) queued.removeFirst().run() }
        fun runDriveWorker() { while (queuedDrive.isNotEmpty()) queuedDrive.removeFirst().run() }
        fun runBoth() { runWorker(); runDriveWorker() }
        fun positionCounters() = listOf(push.framesOpened, push.wakes, push.forwarded, push.refusedByX10, push.postFailed, push.droppedLate, push.droppedBad, push.droppedStale)
        fun driveCounters() = listOf(push.driveFramesOpened, push.driveForwarded, push.driveRefusedByX10, push.drivePostFailed, push.driveDroppedLate, push.driveDroppedBad, push.driveDroppedStale)
    }

    private fun rig(drive: Boolean = true) = Rig(key, session, drive)

    private fun Rig.deliver(frame: ByteArray, frameId: Int, arrive: Long = now) {
        now = arrive
        for (p in packets(frame, frameId)) push.onPacket(p)
    }

    /** The calibration frame that follows `push-enable`: comma's clock 0, the Companion's 40 ms later. */
    private fun Rig.calibrate() = deliver(seal(1, 1, "wake", 0, null, null), 1, 40)

    private fun Rig.driveFrame(counter: Long, ts: Long, text: String = drive(), delay: Long = 0, token: String = driveToken) = deliver(seal(counter.toInt(), counter, "drive", ts, text, token), counter.toInt(), ts + 40 + delay)
    private fun Rig.positionFrame(counter: Long, ts: Long, text: String = position, delay: Long = 0, token: String = positionToken) = deliver(seal(counter.toInt(), counter, "vehicle", ts, text, token), counter.toInt(), ts + 40 + delay)

    private fun seqOf(body: String) = Regex("\"seq\":(\\d+)").find(body)!!.groupValues[1].toInt()

    // ---- frames: open ------------------------------------------------------------------------------------------------------------------

    @Test fun aDriveFrameOpensWhenTheBridgeAnnouncedIt() {
        val frame = seal(1, 5, "drive", 123456, drive(), driveToken)
        val message = PushFrames.open(frame, key, session, 4, driveAllowed = true)
        assertEquals("drive", message.kind); assertEquals(5L, message.counter); assertEquals(123456L, message.tsMs)
        assertEquals(drive(), String(message.body!!)); assertEquals(driveToken, message.auth)
        val mine = PushFrames.open(seal(2, 6, "drive", 1, minimal, driveToken), key, session, 5, navAllowed = true, driveAllowed = true)
        assertEquals(minimal, String(mine.body!!))
    }

    @Test fun theDriveObjectIsWrittenAsCompactAsciiWhateverTheSenderSpacing() {
        val spaced = "{ \"v\" : 1 , \"seq\" : 3 ,\n \"age_ms\" : 40,\t\"st\":\"en\" }"
        val message = PushFrames.open(seal(1, 5, "drive", 1, spaced, driveToken), key, session, 0, driveAllowed = true)
        assertEquals("{\"v\":1,\"seq\":3,\"age_ms\":40,\"st\":\"en\"}", String(message.body!!))
        // a text that was written with an escape for a character that needs none is written plain; one that needs an escape does not pass at all
        val escaped = "{\"v\":1,\"seq\":3,\"age_ms\":40,\"al\":1,\"a1\":\"a\\u0042\\/c\"}"
        assertEquals("{\"v\":1,\"seq\":3,\"age_ms\":40,\"al\":1,\"a1\":\"aB/c\"}", String(PushFrames.open(seal(2, 6, "drive", 1, escaped, driveToken), key, session, 0, driveAllowed = true).body!!))
    }

    @Test fun aDriveFrameFromABridgeThatDidNotAnnounceItIsRefusedAndCounted() {
        val frame = seal(1, 5, "drive", 1, drive(), driveToken)
        for (error in listOf(assertThrows(PushError::class.java) { PushFrames.open(frame, key, session, 0) }, assertThrows(PushError::class.java) { PushFrames.open(frame, key, session, 0, navAllowed = true, driveAllowed = false) }))
            assertEquals("drive", error.kind)
        val r = rig(drive = false)
        r.calibrate()
        r.driveFrame(2, 1000)
        r.runBoth()
        assertTrue(r.drivePosts.isEmpty()); assertTrue(r.queuedDrive.isEmpty())
        assertEquals(1, r.push.driveDroppedBad); assertEquals(0, r.push.driveFramesOpened)
        assertEquals("the position's own counters did not move", listOf(1, 1, 0, 0, 0, 0, 0, 0), r.positionCounters())
        r.positionFrame(3, 2000); r.runBoth()
        assertEquals("a position still passes", 1, r.posts.size)
    }

    @Test fun aLinkThatNeverSaysCarriesDriveIsOneThatDidNotAnnounceIt() {
        val old = object : PushLink {
            override val pushKey get() = key
            override val pushSession get() = session
            override fun subscribe() {}
            override fun pushEnable() = 200
        }
        assertFalse(old.carriesDrive); assertFalse(old.carriesNav)
        val posts = CopyOnWriteArrayList<String>()
        val queued = ArrayDeque<Runnable>()
        val push = VehiclePush(old, post = { _, _ -> 204 }, clock = { 41L }, worker = Executor { queued.addLast(it) }, warn = {}, postDrive = { body, _ -> posts += String(body); 204 }, driveWorker = Executor { queued.addLast(it) })
        for (p in packets(seal(1, 1, "drive", 1, drive(), driveToken), 1)) push.onPacket(p)
        while (queued.isNotEmpty()) queued.removeFirst().run()
        assertTrue(posts.isEmpty()); assertEquals(1, push.driveDroppedBad)
    }

    // ---- frames: every refusal --------------------------------------------------------------------------------------------------------

    @Test fun everyRefusalOfADriveFrame() {
        val longToken = "A".repeat(129)
        val refused = linkedMapOf(
            "an extra field" to plain(extra = ",\"x\":1"),
            "an extra field that looks like a command" to plain(extra = ",\"cmd\":\"engage\""),
            "another session" to plain(s = "ff".repeat(16)),
            "the session as a number" to plain(s = "7"),
            "a counter as a string" to plain(c = "\"5\""), "a counter as a float" to plain(c = "5.0"), "a counter as true" to plain(c = "true"), "a counter of 0" to plain(c = "0"),
            "a negative timestamp" to plain(ts = "-1"), "a timestamp as a string" to plain(ts = "\"1\""), "a timestamp as a float" to plain(ts = "1.5"),
            "a token that is too short" to plain(a = "short"), "a token with a space" to plain(a = "bad token with space"), "a token that is not ASCII" to plain(a = "é".repeat(12)),
            "a token of 129 characters" to plain(a = longToken), "no token" to plain(a = null),
            "a token that is a number" to plain(a = null, extra = ",\"a\":12345678901234"), "a token that is null" to plain(a = null, extra = ",\"a\":null"),
            "no object" to plain(b = null), "an object that is null" to plain(b = "null"), "an array" to plain(b = "[1]"), "a string" to plain(b = "\"x\""), "an empty object" to plain(b = "{}"),
            "an unknown key" to plain(b = "{\"v\":1,\"seq\":1,\"age_ms\":1,\"zz\":1}"),
            "the nav key of the position inside a drive object" to plain(b = "{\"v\":1,\"seq\":1,\"age_ms\":1,\"nav\":{\"v\":1,\"age_ms\":1,\"sig\":\"left\"}}"),
            "a position report" to plain(b = position),
            "a version that is 2" to plain(b = "{\"v\":2,\"seq\":1,\"age_ms\":1}"), "no seq" to plain(b = "{\"v\":1,\"age_ms\":1}"), "no age" to plain(b = "{\"v\":1,\"seq\":1}"),
            "an age over the bound" to plain(b = "{\"v\":1,\"seq\":1,\"age_ms\":60001}"), "an age as a float" to plain(b = "{\"v\":1,\"seq\":1,\"age_ms\":1.5}"), "a seq over the bound" to plain(b = "{\"v\":1,\"seq\":2147483648,\"age_ms\":1}"),
            "a repeated key" to plain(b = "{\"v\":1,\"seq\":1,\"seq\":2,\"age_ms\":1}"), "a null value" to plain(b = "{\"v\":1,\"seq\":1,\"age_ms\":1,\"st\":null}"),
            "an unknown word" to plain(b = "{\"v\":1,\"seq\":1,\"age_ms\":1,\"st\":\"engaged\"}"), "an alert level of 4" to plain(b = "{\"v\":1,\"seq\":1,\"age_ms\":1,\"al\":4}"),
            "a nested value" to plain(b = "{\"v\":1,\"seq\":1,\"age_ms\":1,\"st\":{\"x\":1}}"), "an array value" to plain(b = "{\"v\":1,\"seq\":1,\"age_ms\":1,\"st\":[\"en\"]}"),
            "crv false" to plain(b = "{\"v\":1,\"seq\":1,\"age_ms\":1,\"crv\":false}"), "ld without lc" to plain(b = "{\"v\":1,\"seq\":1,\"age_ms\":1,\"ld\":\"l\"}"),
            "lp without lc pre" to plain(b = "{\"v\":1,\"seq\":1,\"age_ms\":1,\"lc\":\"go\",\"lp\":\"man\"}"), "ov without ovr" to plain(b = "{\"v\":1,\"seq\":1,\"age_ms\":1,\"st\":\"en\",\"ov\":\"gas\"}"),
            "a text without an alert" to plain(b = "{\"v\":1,\"seq\":1,\"age_ms\":1,\"a1\":\"hi\"}"), "an ak with al 0" to plain(b = "{\"v\":1,\"seq\":1,\"age_ms\":1,\"al\":0,\"ak\":\"take\"}"),
            "a text with an angle bracket" to plain(b = "{\"v\":1,\"seq\":1,\"age_ms\":1,\"al\":1,\"a1\":\"<b>\"}"), "a text of 41 characters" to plain(b = "{\"v\":1,\"seq\":1,\"age_ms\":1,\"al\":1,\"a1\":\"${"x".repeat(41)}\"}"),
            "a text with an escaped newline" to plain(b = "{\"v\":1,\"seq\":1,\"age_ms\":1,\"al\":1,\"a1\":\"a\\nb\"}"), "a text with an escaped quote" to plain(b = "{\"v\":1,\"seq\":1,\"age_ms\":1,\"al\":1,\"a2\":\"a\\\"b\"}"),
            "a text with a letter outside ASCII" to plain(b = "{\"v\":1,\"seq\":1,\"age_ms\":1,\"al\":1,\"a1\":\"café\"}"), "an empty text" to plain(b = "{\"v\":1,\"seq\":1,\"age_ms\":1,\"al\":1,\"a1\":\"\"}"),
            "a number that is not finite" to plain(b = "{\"v\":1,\"seq\":1,\"age_ms\":1e999}"),
        )
        for ((name, text) in refused) {
            val error = assertThrows("$name was accepted", PushError::class.java) { PushFrames.open(sealText(text), key, session, 0, navAllowed = true, driveAllowed = true) }
            assertEquals("$name: counted as a drive frame", "drive", error.kind)
        }
        val replay = sealText(plain())
        assertEquals(5L, PushFrames.open(replay, key, session, 4, driveAllowed = true).counter)
        for (last in listOf(5L, 6L, 1000L)) assertEquals("the counter did not rise ($last)", "drive", assertThrows(PushError::class.java) { PushFrames.open(replay, key, session, last, driveAllowed = true) }.kind)
    }

    @Test fun aFrameThatDoesNotOpenIsNoDriveFrameAndIsCountedAsTheOldKindOfBad() {
        val frame = seal(1, 5, "drive", 1, drive(), driveToken)
        assertNull(assertThrows(PushError::class.java) { PushFrames.open(frame, ByteArray(32) { (it + 1).toByte() }, session, 0, driveAllowed = true) }.kind)
        val asRequest = BleWire.seal(plain().toByteArray(), key, "request")
        assertNull(assertThrows(PushError::class.java) { PushFrames.open(asRequest, key, session, 0, driveAllowed = true) }.kind)
        assertNull(assertThrows(PushError::class.java) { PushFrames.open(frame + byteArrayOf(0), key, session, 0, driveAllowed = true) }.kind)
        assertNull(assertThrows(PushError::class.java) { PushFrames.open(BleWire.sealRaw(plain(b = null, a = null).toByteArray(), byteArrayOf(1), key, BleWire.PUSH_DIRECTION), key, session, 0, driveAllowed = true) }.kind)
        for (kind in listOf("Drive", "DRIVE", "drive ", "drives", "driver", "status", "command", "", "vehicle2")) assertNull(kind, assertThrows(PushError::class.java) { PushFrames.open(sealText(plain(t = kind)), key, session, 0, driveAllowed = true) }.kind)
        val r = rig()
        r.calibrate()
        r.deliver(frame.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }, 2, 1040)
        assertEquals(1, r.push.droppedBad); assertEquals(0, r.push.driveDroppedBad)
    }

    @Test fun aVehicleFrameWithADriveOnlyKeyIsRefused() {
        val items = listOf("\"st\":\"en\"", "\"rdy\":true", "\"ov\":\"gas\"", "\"al\":1", "\"ak\":\"lane\"", "\"a1\":\"x\"", "\"a2\":\"x\"", "\"lc\":\"pre\"", "\"ld\":\"l\"", "\"lp\":\"man\"", "\"crv\":true", "\"stp\":true")
        for (item in items) for (flags in listOf(false to false, true to true)) {
            val text = plain(t = "vehicle", b = "{\"v\":1,\"seq\":1,\"age_ms\":5,$item}", a = positionToken)
            val error = assertThrows("$item was accepted as a vehicle report", PushError::class.java) { PushFrames.open(sealText(text), key, session, 0, navAllowed = flags.first, driveAllowed = flags.second) }
            assertNull("a vehicle frame is no drive frame", error.kind)
        }
        // and a drive frame carrying the position's keys
        for (item in listOf("\"fix\":true", "\"lat\":35.68", "\"lon\":139.7", "\"speed\":1.5", "\"src\":\"gnss\"", "\"bearing\":10.5", "\"acc\":3")) {
            val error = assertThrows("$item was accepted in a drive object", PushError::class.java) { PushFrames.open(sealText(plain(b = "{\"v\":1,\"seq\":1,\"age_ms\":5,$item}")), key, session, 0, driveAllowed = true) }
            assertEquals("drive", error.kind)
        }
        assertEquals("vehicle", PushFrames.open(sealText(plain(t = "vehicle", b = position, a = positionToken)), key, session, 0, driveAllowed = true).kind)
    }

    @Test fun aWakeFrameAndAVehicleFrameAreAsTheyWereWhetherOrNotDriveIsAnnounced() {
        for (announced in listOf(true, false)) {
            val wake = PushFrames.open(seal(1, 5, "wake", 1, null, null), key, session, 0, driveAllowed = announced)
            assertEquals("wake", wake.kind); assertNull(wake.body)
            val vehicle = PushFrames.open(seal(2, 6, "vehicle", 1, position, positionToken), key, session, 0, driveAllowed = announced)
            assertEquals("vehicle", vehicle.kind); assertEquals(position, String(vehicle.body!!))
            assertThrows(PushError::class.java) { PushFrames.open(sealText(plain(t = "wake", b = drive())), key, session, 0, driveAllowed = announced) }
        }
    }

    // ---- the forward: path, token, age ---------------------------------------------------------------------------------------------------

    @Test fun aFreshDriveFrameIsPostedToTheDrivePathWithItsOwnTokenAndOnlyTheAgeRaised() {
        val r = rig()
        r.calibrate()
        r.driveFrame(2, 1000, delay = 30)
        r.runBoth()
        assertEquals(1, r.drivePosts.size); assertTrue(r.posts.isEmpty())
        assertEquals(driveToken, r.drivePosts[0].second)
        assertEquals(drive(age = 42), r.drivePosts[0].first)
        assertEquals(1, r.push.driveForwarded); assertEquals(1, r.push.driveFramesOpened)
        assertEquals("nothing of the position path moved but the calibration wake", listOf(1, 1, 0, 0, 0, 0, 0, 0), r.positionCounters())
        val verdict = Allowlist.check("POST", "/v1/drive", r.drivePosts[0].first.toByteArray())
        assertTrue(verdict.ok); assertEquals(Shape.DRIVE, verdict.shape)
    }

    @Test fun aPositionAndADriveFrameGoEachToItsOwnPostWithItsOwnToken() {
        val r = rig()
        r.calibrate()
        for (k in 1..6) {
            r.positionFrame(2L * k, 2000L * k, position.replace("\"seq\":1234", "\"seq\":$k"), token = positionToken + "p$k")
            r.driveFrame(2L * k + 1, 2000L * k + 500, drive(seq = k), token = driveToken + "d$k")
            r.runBoth()
        }
        assertEquals(6, r.posts.size); assertEquals(6, r.drivePosts.size)
        for ((i, post) in r.posts.withIndex()) {
            assertEquals(positionToken + "p${i + 1}", post.second)
            assertTrue(Allowlist.check("POST", "/v1/vehicle", post.first.toByteArray()).ok); assertFalse("a position is no drive body", Allowlist.check("POST", "/v1/drive", post.first.toByteArray()).ok)
        }
        for ((i, post) in r.drivePosts.withIndex()) {
            assertEquals(driveToken + "d${i + 1}", post.second); assertEquals(i + 1, seqOf(post.first))
            assertTrue(Allowlist.check("POST", "/v1/drive", post.first.toByteArray()).ok); assertFalse("a drive body is no position", Allowlist.check("POST", "/v1/vehicle", post.first.toByteArray()).ok)
        }
        assertEquals(6, r.push.forwarded); assertEquals(6, r.push.driveForwarded)
    }

    @Test fun everyAllowedBodyOfTheVectorsPassesTheFramePathAndEveryRefusedOneDoesNot() {
        val file = TestVectors.file("drive-v4.json")
        @Suppress("UNCHECKED_CAST") val cases = (file["cases"] as List<StrictJson.Obj>).filter { it["bytes"] != true }
        val r = rig()
        r.calibrate()
        var counter = 2L
        var forwarded = 0
        for (case in cases) {
            val text = case["body"] as String
            val before = r.drivePosts.size
            val frame = try { seal(counter.toInt(), counter, "drive", counter * 1000, text, driveToken) } catch (_: IllegalArgumentException) { continue }
            r.deliver(frame, counter.toInt(), counter * 1000 + 40)
            r.runBoth()
            counter++
            val went = r.drivePosts.size > before
            // the object of a frame is written again without its spacing, and that is what is judged: a valid object padded to 513 bytes is the same object as an unpadded one
            val padded = case["name"] == "513 bytes"
            if (padded) { assertTrue(went); assertEquals("{\"v\":1,\"seq\":7,\"age_ms\":12}", r.drivePosts.last().first); continue }
            if (case["allow"] == true) {
                assertTrue("${case["name"]} was refused on the way", went)
                forwarded++
                val body = r.drivePosts.last().first.toByteArray()
                assertTrue(Allowlist.check("POST", "/v1/drive", body).ok)
                assertEquals("${case["name"]}: only the spacing may differ", (case["body"] as String).replace(Regex("\\s+"), ""), String(body).replace(Regex("\\s+"), ""))
            } else assertFalse("${case["name"]} was forwarded though the allow-list refuses it", went)
        }
        assertEquals(cases.count { it["allow"] == true }, forwarded)
        assertTrue(r.posts.isEmpty())
    }

    @Test fun everyCharacterOfAnAlertTextIsForwardedAsGiven() {
        val r = rig()
        r.calibrate()
        val texts = listOf("Az09 .,:;!?'%/()+-", "TAKE CONTROL IMMEDIATELY", "x".repeat(40), "Steer Left to Start Lane Change Now")
        for ((i, text) in texts.withIndex()) {
            r.driveFrame(2L + i, 1000L * (i + 1), "{\"v\":1,\"seq\":$i,\"age_ms\":9,\"al\":3,\"ak\":\"take\",\"a1\":\"$text\"}")
            r.runBoth()
            assertEquals("{\"v\":1,\"seq\":$i,\"age_ms\":9,\"al\":3,\"ak\":\"take\",\"a1\":\"$text\"}", r.drivePosts.last().first)
        }
    }

    @Test fun theAgeOfADriveFrameSaturatesAt60000OnItsWay() {
        val r = rig()
        r.calibrate()
        r.driveFrame(2, 1000, drive(age = 59_990), delay = 30); r.runBoth()
        assertEquals(drive(age = 60_000), r.drivePosts[0].first)
        r.driveFrame(3, 2000, drive(seq = 9, age = 60_000)); r.runBoth()
        assertEquals(drive(seq = 9, age = 60_000), r.drivePosts.last().first)
        r.driveFrame(4, 3000, drive(seq = 10, age = 59_999), delay = 1); r.runBoth()
        assertEquals(drive(seq = 10, age = 60_000), r.drivePosts.last().first)
        r.driveFrame(5, 4000, drive(seq = 11, age = 59_000), delay = 999); r.runBoth()
        assertEquals("59000 + 999 stays below the bound", drive(seq = 11, age = 59_999), r.drivePosts.last().first)
    }

    // ---- the freshness rule is the position's ---------------------------------------------------------------------------------------------

    @Test fun aDriveFrameThatWaitedOverASecondIsDroppedAndOneThatWaitedASecondIsNot() {
        for ((late, dwell, forwarded) in listOf(Triple(0L, 1000L, true), Triple(0L, 1001L, false), Triple(1000L, 0L, true), Triple(1001L, 0L, false), Triple(600L, 400L, true), Triple(600L, 401L, false), Triple(0L, 0L, true), Triple(999L, 1L, true))) {
            val r = rig()
            r.calibrate()
            r.driveFrame(2, 1000, delay = late)
            r.now += dwell
            r.runBoth()
            assertEquals("late $late dwell $dwell", forwarded, r.drivePosts.size == 1)
            assertEquals(if (forwarded) 1 else 0, r.push.driveForwarded); assertEquals(if (forwarded) 0 else 1, r.push.driveDroppedLate)
            if (forwarded) assertEquals("the age grows by late + dwell", drive(age = 12 + (late + dwell).toInt()), r.drivePosts[0].first)
            assertEquals("the position's counters did not move", listOf(1, 1, 0, 0, 0, 0, 0, 0), r.positionCounters())
        }
    }

    @Test fun aDriveFrameFeedsTheSameLateWindowAsThePosition() {
        // no wake: the first sample of the window is a drive frame, and the position that comes late is judged against it
        val r = rig()
        r.driveFrame(1, 1000); r.runBoth()
        r.positionFrame(2, 2000, delay = 1500); r.runBoth()
        assertEquals(1, r.push.droppedLate); assertEquals(0, r.push.driveDroppedLate); assertTrue(r.posts.isEmpty())
        // and the other way round
        val q = rig()
        q.positionFrame(1, 1000); q.runBoth()
        q.driveFrame(2, 2000, delay = 1500); q.runBoth()
        assertEquals(1, q.push.driveDroppedLate); assertEquals(0, q.push.droppedLate); assertTrue(q.drivePosts.isEmpty())
        q.driveFrame(3, 3000, delay = 0); q.runBoth()
        assertEquals("a frame on time goes again", 1, q.drivePosts.size)
    }

    @Test fun aDriveFrameThatIsLateDoesNotMakeThePositionLate() {
        val r = rig()
        r.calibrate()
        r.driveFrame(2, 1000, delay = 5000); r.runBoth()
        r.positionFrame(3, 2000); r.runBoth()
        assertEquals(1, r.posts.size); assertEquals(position, r.posts[0].first)
        assertEquals(1, r.push.driveDroppedLate)
    }

    // ---- one POST in flight per kind, the newest first --------------------------------------------------------------------------------------

    @Test fun aSlowDrivePostDoesNotHoldAPositionPost() {
        val r = rig()
        r.calibrate()
        r.drivePostGate = CountDownLatch(1)
        r.driveFrame(2, 1000, drive(seq = 1))
        val running = r.queuedDrive.removeFirst()
        val thread = Thread { running.run() }
        thread.start()
        assertTrue("the drive POST is in flight", r.drivePostEntered.await(10, TimeUnit.SECONDS))
        r.positionFrame(3, 2000, position.replace("\"seq\":1234", "\"seq\":1")); r.runWorker()
        assertEquals("the position went at once", 1, r.posts.size); assertTrue(r.drivePosts.isEmpty())
        r.driveFrame(4, 2500, drive(seq = 2)); r.driveFrame(5, 2600, drive(seq = 3))
        assertTrue("a drive frame that arrives meanwhile waits for the one in flight", r.queuedDrive.isEmpty())
        r.positionFrame(6, 3000, position.replace("\"seq\":1234", "\"seq\":2")); r.runWorker()
        assertEquals("a second position goes too", 2, r.posts.size)
        r.drivePostGate!!.countDown(); thread.join(10_000)
        assertEquals("the first and the newest only", listOf(1, 3), r.drivePosts.map { seqOf(it.first) })
        assertEquals(1, r.push.driveDroppedStale); assertEquals("the position forwarder is not the drive's", 0, r.push.droppedStale)
        assertEquals(2, r.push.driveForwarded); assertEquals(2, r.push.forwarded)
    }

    @Test fun aSlowPositionPostDoesNotHoldADrivePost() {
        val r = rig()
        r.calibrate()
        r.postGate = CountDownLatch(1)
        r.positionFrame(2, 1000)
        val running = r.queued.removeFirst()
        val thread = Thread { running.run() }
        thread.start()
        assertTrue("the position POST is in flight", r.postEntered.await(10, TimeUnit.SECONDS))
        r.driveFrame(3, 2000, drive(seq = 1)); r.runDriveWorker()
        r.driveFrame(4, 3000, drive(seq = 2)); r.runDriveWorker()
        assertEquals("both drive frames went", listOf(1, 2), r.drivePosts.map { seqOf(it.first) }); assertTrue(r.posts.isEmpty())
        r.positionFrame(5, 4000, position.replace("\"seq\":1234", "\"seq\":5")); r.positionFrame(6, 4100, position.replace("\"seq\":1234", "\"seq\":6"))
        r.postGate!!.countDown(); thread.join(10_000)
        assertEquals("the position forwarder keeps the newest only", listOf(1234, 6), r.posts.map { seqOf(it.first) })
        assertEquals(1, r.push.droppedStale); assertEquals(0, r.push.driveDroppedStale)
    }

    @Test fun aStallThatDeliversFiveDriveFramesAtOnceForwardsOnlyTheNewest() {
        val r = rig()
        r.calibrate()
        for (k in 2..31) { r.driveFrame(k.toLong(), k * 1000L, drive(seq = k)); r.runBoth() }
        r.drivePosts.clear()
        val burstAt = 36_000L + 40
        for (k in 32..36) r.deliver(seal(k, k.toLong(), "drive", k * 1000L, drive(seq = k), driveToken), k, burstAt)
        r.runBoth()
        assertEquals(listOf(36), r.drivePosts.map { seqOf(it.first) })
        assertEquals(3, r.push.driveDroppedStale); assertEquals(1, r.push.driveDroppedLate); assertEquals(0, r.push.droppedStale)
    }

    @Test fun anInterleavedStreamOfBothKindsKeepsEachKindInOrderAndNeverMixesThem() {
        val r = rig()
        r.calibrate()
        val rng = Random(5)
        var counter = 2L
        var p = 0; var d = 0
        repeat(300) {
            val ts = counter * 100
            if (rng.nextBoolean()) { p++; r.positionFrame(counter, ts, position.replace("\"seq\":1234", "\"seq\":$p")) } else { d++; r.driveFrame(counter, ts, drive(seq = d)) }
            counter++
            if (rng.nextInt(3) == 0) { if (rng.nextBoolean()) r.runWorker() else r.runDriveWorker() }
        }
        r.runBoth()
        assertTrue(r.posts.map { seqOf(it.first) }.zipWithNext().all { (a, b) -> a < b }); assertTrue(r.drivePosts.map { seqOf(it.first) }.zipWithNext().all { (a, b) -> a < b })
        assertTrue(r.posts.all { Allowlist.check("POST", "/v1/vehicle", it.first.toByteArray()).shape == Shape.VEHICLE && !Allowlist.check("POST", "/v1/drive", it.first.toByteArray()).ok })
        assertTrue(r.drivePosts.all { Allowlist.check("POST", "/v1/drive", it.first.toByteArray()).shape == Shape.DRIVE && !Allowlist.check("POST", "/v1/vehicle", it.first.toByteArray()).ok })
        assertTrue(r.posts.all { it.second == positionToken } && r.drivePosts.all { it.second == driveToken })
        assertEquals(p, r.posts.size + r.push.droppedStale + r.push.droppedLate); assertEquals(d, r.drivePosts.size + r.push.driveDroppedStale + r.push.driveDroppedLate)
    }

    // ---- counters -----------------------------------------------------------------------------------------------------------------------

    @Test fun aDroppedOrRefusedDriveFrameNeverTouchesThePositionCountersAndTheReverse() {
        val r = rig()
        r.calibrate()
        val positionBefore = r.positionCounters()
        // drive: forwarded, refused by the X10, failed, late (dwell), late (delay), bad (an object that is not allowed), bad (a token that is not usable)
        r.driveFrame(2, 1000); r.runBoth()
        r.drivePostStatus = 401; r.driveFrame(3, 2000); r.runBoth()
        r.drivePostStatus = 204; r.drivePostFailure = IOException("connection refused"); r.driveFrame(4, 3000); r.runBoth(); r.drivePostFailure = null
        r.driveFrame(5, 4000); r.now += 1500; r.runBoth()
        r.driveFrame(6, 5000, delay = 2500); r.runBoth()
        r.driveFrame(7, 6000, "{\"v\":1,\"seq\":1,\"age_ms\":1,\"cmd\":1}"); r.runBoth()
        r.driveFrame(8, 7000, token = "short"); r.runBoth()
        assertEquals(1, r.push.driveForwarded); assertEquals(1, r.push.driveRefusedByX10); assertEquals(1, r.push.drivePostFailed); assertEquals(2, r.push.driveDroppedLate); assertEquals(2, r.push.driveDroppedBad)
        assertEquals(positionBefore, r.positionCounters())
        val driveBefore = r.driveCounters()
        // position: forwarded, refused, failed, late, bad
        r.positionFrame(9, 8000); r.runBoth()
        r.postStatus = 401; r.positionFrame(10, 9000); r.runBoth()
        r.postStatus = 204; r.postFailure = IOException("connection refused"); r.positionFrame(11, 10_000); r.runBoth(); r.postFailure = null
        r.positionFrame(12, 11_000, delay = 2500); r.runBoth()
        r.positionFrame(13, 12_000, "{\"v\":1,\"cmd\":1}"); r.runBoth()
        assertEquals(listOf(1, 1, 1, 1, 1, 1), listOf(r.push.forwarded, r.push.refusedByX10, r.push.postFailed, r.push.droppedLate, r.push.droppedBad, r.push.wakes))
        assertEquals(driveBefore, r.driveCounters())
    }

    @Test fun nothingOfADriveFrameIsLogged() {
        val r = rig()
        r.link.drive = false
        r.calibrate()
        val secret = "{\"v\":1,\"seq\":1,\"age_ms\":1,\"al\":1,\"a1\":\"Steer Left to Start Lane Change\"}"
        r.driveFrame(2, 1000, secret, token = driveToken)
        r.link.drive = true
        r.driveFrame(3, 2000, "{\"v\":1,\"seq\":1,\"age_ms\":1,\"al\":1,\"a1\":\"Steer Left <b>\"}")
        r.driveFrame(4, 3000, secret, token = driveToken + " bad")
        r.drivePostFailure = IOException("Steer Left to Start Lane Change $driveToken"); r.driveFrame(5, 4000, secret); r.runBoth()
        r.deliver(ByteArray(64) { 3 }, 6, 5000)
        assertTrue(r.warnings.isNotEmpty())
        for (line in r.warnings) for (leaked in listOf(driveToken, "Steer", "Lane Change", "<b>", "\"al\"", "age_ms", "connection")) assertFalse("a warning holds '$leaked': $line", line.contains(leaked))
    }

    // ---- the announcement and the health flag -------------------------------------------------------------------------------------------

    private fun features(vararg members: String) = BridgeFeatures.parse(("{\"protocol\":1,\"poll2\":true,\"results2\":true,\"push\":true" + members.joinToString("") { ",$it" } + "}").toByteArray())
    private fun announcement(features: BridgeFeatures): String? = PushEnable.request(features).let { if (it.has("body")) String(java.util.Base64.getDecoder().decode(it.getString("body")), Charsets.US_ASCII) else null }

    @Test fun theHealthFlagPushDriveIsReadAndOnlyTheLiteralTrueCounts() {
        assertTrue(features("\"pushDrive\":true").pushDrive); assertTrue(features("\"pushDrive\":true").announcesDrive)
        assertFalse(features().pushDrive)
        for (bad in listOf("1", "\"true\"", "null", "false", "[true]", "{}", "0", "\"pushDrive\"")) assertFalse(bad, features("\"pushDrive\":$bad").pushDrive)
        for (other in listOf("\"drive\":true", "\"pushdrive\":true", "\"PushDrive\":true", "\"push_drive\":true", "\"pushDrive \":true", "\"pushNav\":true", "\"maps\":true")) assertFalse(other, features(other).pushDrive)
        assertFalse(BridgeFeatures.parse("{\"protocol\":2,\"pushDrive\":true".toByteArray()).pushDrive) // not JSON: the old bridge's behaviour
        assertFalse(BridgeFeatures.LEGACY.pushDrive); assertFalse(BridgeFeatures.LEGACY.announcesDrive)
    }

    @Test fun theAnnouncementOfAllEightCombinationsOfTheHealthFlags() {
        val expected = mapOf(
            Triple(false, false, false) to null, Triple(true, false, false) to "{\"nav\":true}", Triple(false, true, false) to "{\"maps\":true}", Triple(true, true, false) to "{\"nav\":true,\"maps\":true}",
            Triple(false, false, true) to "{\"drive\":true}", Triple(true, false, true) to "{\"nav\":true,\"drive\":true}", Triple(false, true, true) to "{\"maps\":true,\"drive\":true}", Triple(true, true, true) to "{\"nav\":true,\"maps\":true,\"drive\":true}",
        )
        for ((flags, body) in expected) {
            val members = listOfNotNull("\"pushNav\":true".takeIf { flags.first }, "\"maps\":true".takeIf { flags.second }, "\"pushDrive\":true".takeIf { flags.third })
            assertEquals("health $members", body, announcement(features(*members.toTypedArray())))
        }
        assertEquals(8, expected.size)
    }

    @Test fun driveIsAnnouncedOnlyToABridgeWhoseHealthSaidPushDrive() {
        for (nav in listOf(false, true)) for (maps in listOf(false, true)) for (junk in listOf("", "\"drive\":true", "\"pushDrive\":1", "\"pushDrive\":\"true\"", "\"pushDrive\":null", "\"pushDrive\":false", "\"pushDrive\":[true]", "\"pushdrive\":true")) {
            val members = listOfNotNull("\"pushNav\":true".takeIf { nav }, "\"maps\":true".takeIf { maps }, junk.takeIf { it.isNotEmpty() })
            val body = announcement(features(*members.toTypedArray()))
            assertFalse("health $members: $body", body?.contains("drive") == true)
            assertEquals("the announcement an older bridge always got", when { nav && maps -> "{\"nav\":true,\"maps\":true}"; maps -> "{\"maps\":true}"; nav -> "{\"nav\":true}"; else -> null }, body)
        }
        // a legacy answer and an answer that is not JSON announce nothing at all
        assertNull(announcement(BridgeFeatures.LEGACY)); assertNull(announcement(BridgeFeatures.parse("not json".toByteArray())))
        // maps without the jobs of version 2 is not announced (as before), drive is
        val noJobs = BridgeFeatures.parse("{\"protocol\":1,\"push\":true,\"pushNav\":true,\"maps\":true,\"pushDrive\":true}".toByteArray())
        assertEquals("{\"nav\":true,\"drive\":true}", announcement(noJobs))
        assertEquals("the old overloads: nav and maps alone", "{\"nav\":true}", String(java.util.Base64.getDecoder().decode(PushEnable.request(true).getString("body"))))
        assertFalse(PushEnable.request(false).has("body")); assertFalse(PushEnable.request(false, false, false).has("body"))
    }

    @Test fun theLinkOfTheTransportReadsItsFlagsFromTheHealthAnswerEveryTime() {
        var health = BridgeFeatures.LEGACY
        val link = object : BridgePushLink({ health }) {
            override val pushKey get() = key
            override val pushSession get() = session
            override fun subscribe() {}
            override fun pushEnable() = 200
        }
        assertFalse(link.carriesDrive); assertFalse(link.carriesNav); assertNull(link.enableRequest().optString("body", "").takeIf { it.isNotEmpty() })
        health = features("\"pushDrive\":true")
        assertTrue(link.carriesDrive); assertFalse(link.carriesNav)
        assertEquals("{\"drive\":true}", String(java.util.Base64.getDecoder().decode(link.enableRequest().getString("body"))))
        health = features("\"pushNav\":true")
        assertFalse(link.carriesDrive); assertTrue(link.carriesNav)
        assertEquals("{\"nav\":true}", String(java.util.Base64.getDecoder().decode(link.enableRequest().getString("body"))))
        health = features("\"pushNav\":true", "\"maps\":true", "\"pushDrive\":true")
        assertTrue(link.carriesDrive); assertTrue(link.carriesNav)
        assertEquals("{\"nav\":true,\"maps\":true,\"drive\":true}", String(java.util.Base64.getDecoder().decode(link.enableRequest().getString("body"))))
        assertEquals("POST", link.enableRequest().getString("method")); assertEquals("/_bridge/push/enable", link.enableRequest().getString("path"))
    }

    @Test fun aReceiverOnALinkThatCarriesDriveAcceptsDriveAndOneThatDoesNotRefusesIt() {
        for (carries in listOf(true, false)) {
            val r = rig(drive = carries)
            r.calibrate(); r.driveFrame(2, 1000); r.runBoth()
            assertEquals(carries, r.drivePosts.size == 1)
        }
    }

    // ---- the old behaviour, unchanged -----------------------------------------------------------------------------------------------------

    @Test fun thePositionPathIsTheSameWithOrWithoutDriveAnnounced() {
        val outcomes = ArrayList<Pair<List<String>, List<Int>>>()
        for (carries in listOf(true, false)) {
            val r = rig(drive = carries)
            r.calibrate()
            r.positionFrame(2, 1000, delay = 30); r.runBoth()
            r.positionFrame(3, 2000, delay = 2000); r.runBoth()
            r.positionFrame(4, 3000, "{\"v\":1,\"cmd\":1}"); r.runBoth()
            r.postStatus = 401; r.positionFrame(5, 4000); r.runBoth()
            outcomes += r.posts.map { it.first + "|" + it.second } to r.positionCounters()
        }
        assertEquals(outcomes[0], outcomes[1])
        assertEquals(listOf(position.replace("\"age_ms\":120", "\"age_ms\":150") + "|" + positionToken, position + "|" + positionToken), outcomes[0].first)
        assertEquals("the wake, a position, a late one, a bad one, a refused one", listOf(4, 1, 1, 1, 0, 1, 1, 0), outcomes[0].second)
    }

    // ---- the poster: the real HTTP request ----------------------------------------------------------------------------------------------

    private class Request(val line: String, val headers: Map<String, String>, val body: ByteArray)

    /** A server of one loopback port that answers [status] (with [location] when it redirects) and keeps what it was asked. */
    private class Peer(private val status: Int = 204, private val location: String? = null) : Closeable {
        private val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val requests = CopyOnWriteArrayList<Request>()
        private val thread = Thread {
            try {
                while (true) server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream()
                    val line = readLine(input)
                    val headers = LinkedHashMap<String, String>()
                    while (true) { val h = readLine(input); if (h.isEmpty()) break; headers[h.substringBefore(':').lowercase()] = h.substringAfter(':').trim() }
                    val body = ByteArray(headers["content-length"]?.toInt() ?: 0).also { var n = 0; while (n < it.size) { val k = input.read(it, n, it.size - n); if (k < 0) break; n += k } }
                    requests += Request(line, headers, body)
                    socket.getOutputStream().write(("HTTP/1.1 $status X\r\nContent-Length: 0\r\nConnection: close\r\n" + (location?.let { "Location: $it\r\n" } ?: "") + "\r\n").toByteArray())
                }
            } catch (_: IOException) { /* closed */ }
        }.apply { isDaemon = true; start() }
        private fun readLine(input: InputStream): String { val out = StringBuilder(); while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) out.append(c.toChar()) }; return out.toString() }
        override fun close() { server.close(); thread.join(2000) }
    }

    @Test fun thePosterOfTheDriveStatusPostsToTheDrivePathAndThePositionPosterToTheVehiclePath() {
        Peer().use { peer ->
            val body = drive().toByteArray()
            assertEquals(204, LoopbackVehiclePoster(peer.port, "/v1/drive").post(body, driveToken))
            assertEquals(204, LoopbackVehiclePoster(peer.port).post(position.toByteArray(), positionToken))
            assertEquals(204, LoopbackVehiclePoster(peer.port, "/v1/vehicle").post(position.toByteArray(), positionToken))
            assertEquals(3, peer.requests.size)
            val (first, second, third) = peer.requests
            assertEquals("POST /v1/drive HTTP/1.1", first.line); assertEquals("Bearer $driveToken", first.headers["authorization"]); assertArrayEquals(body, first.body)
            assertEquals("POST /v1/vehicle HTTP/1.1", second.line); assertEquals("Bearer $positionToken", second.headers["authorization"]); assertArrayEquals(position.toByteArray(), second.body)
            assertEquals("POST /v1/vehicle HTTP/1.1", third.line)
            for (request in peer.requests) {
                assertEquals("application/json", request.headers["content-type"]); assertEquals(request.body.size.toString(), request.headers["content-length"])
                assertEquals("127.0.0.1:${peer.port}", request.headers["host"])
            }
        }
    }

    @Test fun thePosterIsHeldToItsTwoPathsAndFollowsNoRedirect() {
        for (path in listOf("/v1/vehicles", "/v1/Drive", "/", "", "/v1/drive?x=1", "/v1/drive/", "/status", "//v1/drive", "v1/drive", "/v1/drive#"))
            assertThrows(path, IllegalArgumentException::class.java) { LoopbackVehiclePoster(8765, path) }
        Peer(status = 302, location = "/v1/vehicle").use { peer ->
            assertEquals("a redirect is an answer, never followed", 302, LoopbackVehiclePoster(peer.port, "/v1/drive").post(drive().toByteArray(), driveToken))
            assertEquals(1, peer.requests.size)
        }
        Peer(status = 401).use { peer -> assertEquals(401, LoopbackVehiclePoster(peer.port, "/v1/drive").post(drive().toByteArray(), driveToken)) }
    }

    @Test fun theDefaultPostersOfAReceiverAreTheTwoPathsOfTheRouteApi() {
        // the receiver's defaults: position to the vehicle path, drive to the drive path (the constants the real posters are built from)
        assertEquals("/v1/vehicle", LoopbackVehiclePoster.VEHICLE_PATH); assertEquals("/v1/drive", DriveFrames.PATH); assertEquals("/v1/drive", DriveContract.PATH)
        assertEquals("drive", DriveFrames.KIND)
    }

    // ---- a hostile fuzz of drive frames ------------------------------------------------------------------------------------------------------

    /** The verdict of the contract on a Kotlin map, written from the table of the spec and nothing else (not from the Companion's code). */
    private fun oracle(o: Map<String, Any?>): Boolean {
        val words = mapOf("st" to listOf("dis", "pre", "en", "soft", "ovr"), "ov" to listOf("steer", "gas"), "ak" to listOf("take", "drv", "fcw", "lane", "other"), "lc" to listOf("off", "pre", "go", "fin"), "ld" to listOf("l", "r"), "lp" to listOf("man", "auto", "na", "blk"))
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 .,:;!?'%/()+-"
        fun int(x: Any?, lo: Long, hi: Long) = (x is Int || x is Long) && (x as Number).toLong() in lo..hi
        if (!(o["v"] is Int && o["v"] == 1) || !int(o["seq"], 0, 2147483647L) || !int(o["age_ms"], 0, 60000)) return false
        for ((k, x) in o) {
            val ok = when (k) {
                "v", "seq", "age_ms" -> true
                "st", "ov", "ak", "lc", "ld", "lp" -> x is String && x in words.getValue(k)
                "rdy", "lat", "lon" -> x is Boolean
                "al" -> int(x, 0, 3)
                "a1", "a2" -> x is String && x.length in 1..40 && x.all { it in chars }
                "crv", "stp" -> x == true
                else -> false
            }
            if (!ok) return false
        }
        if ("ld" in o && (o["lc"] ?: "off") == "off") return false
        if ("lp" in o && o["lc"] != "pre") return false
        if ("ov" in o && o["st"] != "ovr") return false
        if (("ak" in o || "a1" in o || "a2" in o) && !int(o["al"], 1, 3)) return false
        return true
    }

    private fun json(x: Any?): String = when (x) {
        null -> "null"
        is Boolean, is Int, is Long -> x.toString()
        is Double -> x.toString()
        is String -> "\"" + x.map { c -> when { c == '"' -> "\\\""; c == '\\' -> "\\\\"; c.code < 0x20 -> "\\u%04x".format(c.code); else -> c.toString() } }.joinToString("") + "\""
        is List<*> -> x.joinToString(",", "[", "]") { json(it) }
        is Map<*, *> -> x.entries.joinToString(",", "{", "}") { json(it.key as String) + ":" + json(it.value) }
        else -> error("type")
    }

    private fun randomObject(rng: Random): LinkedHashMap<String, Any?> {
        fun <T> pick(vararg items: T): T = items[rng.nextInt(items.size)]
        val o = LinkedHashMap<String, Any?>()
        o["v"] = 1; o["seq"] = pick(0, 1, 7, 2147483647, rng.nextInt(1 shl 30)); o["age_ms"] = pick(0, 12, 999, 59999, 60000, rng.nextInt(60001))
        if (rng.nextInt(3) > 0) o["st"] = pick("dis", "pre", "en", "soft", "ovr")
        for (k in listOf("rdy", "lat", "lon")) if (rng.nextInt(3) == 0) o[k] = rng.nextBoolean()
        if (o["st"] == "ovr" && rng.nextBoolean()) o["ov"] = pick("steer", "gas")
        val al = rng.nextInt(5) - 1
        if (al >= 0) o["al"] = al
        if (al >= 1) { if (rng.nextBoolean()) o["ak"] = pick("take", "drv", "fcw", "lane", "other"); if (rng.nextBoolean()) o["a1"] = "Steer Left ${rng.nextInt(100)}"; if (rng.nextInt(3) == 0) o["a2"] = "Az09 .,:;!?'%/()+-" }
        if (rng.nextInt(3) > 0) o["lc"] = pick("off", "pre", "go", "fin")
        if (o["lc"] != null && o["lc"] != "off" && rng.nextBoolean()) o["ld"] = pick("l", "r")
        if (o["lc"] == "pre" && rng.nextBoolean()) o["lp"] = pick("man", "auto", "na", "blk")
        if (rng.nextInt(4) == 0) o["crv"] = true
        if (rng.nextInt(4) == 0) o["stp"] = true
        return o
    }

    private fun hostileObject(rng: Random): LinkedHashMap<String, Any?> {
        val o = randomObject(rng)
        val junk = listOf<Any?>(null, true, false, 0, 1, 2, 3, 4, -1, 60001, 2147483648L, 1.5, 1.0, "", "x", "en", "EN", "on", "A".repeat(41), "a b", "a\"b", "a\\b", "a\nb", "café", "<b>", "a&b", "a@b", "a#b", "a|b", listOf(1), listOf("en"), mapOf("a" to 1), mapOf("v" to 1))
        val keys = listOf("v", "seq", "age_ms", "st", "rdy", "lat", "lon", "ov", "al", "ak", "a1", "a2", "lc", "ld", "lp", "crv", "stp", "zz", "nav", "fix", "speed", "cmd", "ST", "")
        repeat(rng.nextInt(3)) {
            when (rng.nextInt(5)) {
                0 -> o[keys[rng.nextInt(keys.size)]] = junk[rng.nextInt(junk.size)]
                1 -> if (o.isNotEmpty()) o.remove(o.keys.elementAt(rng.nextInt(o.size)))
                2 -> o[keys[17 + rng.nextInt(keys.size - 17)]] = junk[rng.nextInt(junk.size)]
                3 -> { val pair = listOf("ld" to "l", "lp" to "man", "ov" to "gas", "ak" to "lane", "a1" to "hi")[rng.nextInt(5)]; o[pair.first] = pair.second }
                else -> o["al"] = rng.nextInt(7) - 2
            }
        }
        return o
    }

    @Test fun hostileDriveFramesNeverForwardWhatTheAllowListRefuses() {
        val rng = Random(20261029)
        val r = rig()
        r.calibrate()
        var forwardedCount = 0; var refusedCount = 0
        for (i in 0 until 3000) {
            val o = hostileObject(rng)
            val counter = i + 2L
            val ts = counter * 1000
            val text = json(o)
            val frame = try { seal(counter.toInt(), counter, "drive", ts, text, driveToken) } catch (_: IllegalArgumentException) { continue }
            val before = r.drivePosts.size
            r.deliver(frame, counter.toInt(), ts + 40)
            r.runBoth()
            val went = r.drivePosts.size > before
            val allowed = oracle(o)
            assertEquals("object $text: the oracle says $allowed", allowed, went)
            assertEquals("the allow-list agrees with the oracle on $text", allowed, Allowlist.driveBodyProblem(text.toByteArray(Charsets.UTF_8)).isEmpty())
            if (went) {
                forwardedCount++
                assertEquals("a valid object goes as the sender wrote it", text, r.drivePosts.last().first)
                assertEquals(Shape.DRIVE, Allowlist.check("POST", "/v1/drive", r.drivePosts.last().first.toByteArray()).shape)
            } else refusedCount++
        }
        assertTrue("both outcomes happen ($forwardedCount forwarded, $refusedCount refused)", forwardedCount > 500 && refusedCount > 500)
        assertTrue("nothing went to the position path", r.posts.isEmpty())
        assertEquals(listOf(1, 1, 0, 0, 0, 0, 0, 0), r.positionCounters())
        assertEquals(forwardedCount, r.push.driveForwarded)
    }

    @Test fun hostileDriveFramesFromABridgeThatDidNotAnnounceDriveNeverForwardAnything() {
        val rng = Random(77)
        val r = rig(drive = false)
        r.calibrate()
        var sent = 0
        for (i in 0 until 800) {
            val o = if (i % 2 == 0) randomObject(rng) else hostileObject(rng)
            val counter = i + 2L
            val frame = try { seal(counter.toInt(), counter, "drive", counter * 1000, json(o), driveToken) } catch (_: IllegalArgumentException) { continue }
            r.deliver(frame, counter.toInt(), counter * 1000 + 40); sent++
            r.runBoth()
        }
        assertTrue(r.drivePosts.isEmpty()); assertTrue(r.posts.isEmpty()); assertTrue(r.queuedDrive.isEmpty())
        assertEquals(sent, r.push.driveDroppedBad); assertEquals(0, r.push.driveFramesOpened)
    }

    @Test fun fuzzTheSealedPlaintextOfADriveFrameNothingButAPushErrorEscapesAndWhatOpensIsAllowed() {
        val rng = Random(31)
        val base = plain()
        val alphabet = "{}[]\",:\\ 0123456789abcdeflnrstuvx-.é\u0000\n"
        var opened = 0
        repeat(4000) { n ->
            val text = StringBuilder(base)
            repeat(1 + rng.nextInt(3)) {
                when (rng.nextInt(4)) {
                    0 -> if (text.isNotEmpty()) text.setCharAt(rng.nextInt(text.length), alphabet[rng.nextInt(alphabet.length)])
                    1 -> text.insert(rng.nextInt(text.length + 1), alphabet[rng.nextInt(alphabet.length)])
                    2 -> if (text.isNotEmpty()) text.deleteCharAt(rng.nextInt(text.length))
                    else -> if (text.isNotEmpty()) text.setLength(rng.nextInt(text.length))
                }
            }
            if (text.isEmpty()) return@repeat
            val frame = sealText(text.toString())
            try {
                val message = PushFrames.open(frame, key, session, 0, navAllowed = true, driveAllowed = true)
                opened++
                when (message.kind) {
                    "drive" -> assertTrue("frame $n: $text", Allowlist.driveBodyProblem(message.body!!).isEmpty())
                    "vehicle" -> assertTrue("frame $n: $text", Allowlist.vehicleBodyProblem(message.body!!).isEmpty())
                    else -> assertNull(message.body)
                }
            } catch (_: PushError) { /* refused */ }
        }
        assertTrue("some mutated frames still open ($opened)", opened > 0)
    }

    @Test fun fuzzFlippedAndCutDriveFramesNeverOpen() {
        val rng = Random(9)
        val frame = seal(1, 5, "drive", 1, drive(), driveToken)
        repeat(3000) {
            val data = frame.copyOf()
            repeat(1 + rng.nextInt(3)) { data[4 + rng.nextInt(data.size - 4)] = (data[4 + rng.nextInt(data.size - 4)].toInt() xor (1 shl rng.nextInt(8))).toByte() }
            val mutated = if (rng.nextDouble() < 0.3) data.copyOf(rng.nextInt(data.size)) else data
            try { PushFrames.open(mutated, key, session, 0, driveAllowed = true); if (!mutated.contentEquals(frame)) throw AssertionError("a damaged frame opened") } catch (_: PushError) { /* refused */ }
        }
    }
}
