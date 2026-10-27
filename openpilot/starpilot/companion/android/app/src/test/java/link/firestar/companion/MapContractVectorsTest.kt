package link.firestar.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * The vectors of contract version 3 (docs/dev/bluetooth-map-contract.md section 3), read by the Companion's code exactly as the Python reference and the comma read them: ALL 779 cases of
 * `allowlist-v3.json` (the 273 of version 2 first and unchanged), the 2,506 generated requests of `allowlist-diff-v3.json` with the verdict of the reference, the 121 results of
 * `results-v3.json`, and the sections of `lane-map.json` that belong to the Companion (the planner, the classes and deadlines of the kinds, the announcement bytes, the flags of the health
 * answer, the meter). The sections that belong to the comma (queue limits, the re-probe, the broker) are the comma's own tests.
 */
class MapContractVectorsTest {
    private fun bytesOf(name: String): ByteArray = javaClass.getResourceAsStream("/btlink/$name")!!.readBytes()
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun bodyOf(case: StrictJson.Obj): ByteArray = (case["body"] as? String)?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
    private fun num(item: Any?) = (item as StrictJson.Num)
    @Suppress("UNCHECKED_CAST") private fun list(item: Any?) = item as List<Any?>

    // ---- the files are the contract's files ---------------------------------------------------------------------------------------------

    @Test fun theVectorFilesAreTheOnesTheContractPins() {
        // a truncated or edited copy would silently weaken every test below; the pins are those of bluetooth-map-contract.md section 3
        val pins = mapOf(
            "allowlist.json" to "dc2c25db6a91927868d0e0c2731eb50dc1010393d28bfc96245f5a238a5ba97e",
            "age.json" to "999143d77e4fbd19a3722480a1463fec384b6ee7a92845e5cc8f45f5756a8c58",
            "results.json" to "b6626a67a8d3fb8603296b605b180f3f38a8a68db861a3b216c916268d29eb53",
            "chunk-targets.json" to "bc9255170e40b921963918d22e241984d0ef1380582b57a7fc9b5d06b44a021a",
            "allowlist-v3.json" to "da9e34850db06ed944b1d2f82ce476afe6f7897589f81925a91273c19a2df26a",
            "results-v3.json" to "c50d123688fdc78c6f04fddbc33ad3e088f684a90225279c5c1258bacf5123fb",
            "bundles.json" to "4c161d23c7757f2fa8431e9c7b0fd11b4e93532b779eb10995c0dabbea46b2e5",
            "bundles-differential.json" to "b1d4efcf738492f47b70ffcf064de1ad523cf901839533071abffc13c3e31a9a",
            "lane-map.json" to "1c03a630b60084dce4d89a9f306e4e5fb328cb581b510fc7573b6ca9494066a4",
        )
        for ((file, pin) in pins) assertEquals(file, pin, sha256(bytesOf(file)))
        assertEquals(779, TestVectors.cases("allowlist-v3.json").size)
        assertEquals(121, TestVectors.cases("results-v3.json").size)
        assertEquals("3", num(TestVectors.file("allowlist-v3.json")["version"]).text)
    }

    // ---- the allow-list: all 779 cases ---------------------------------------------------------------------------------------------------

    @Test fun everyCaseOfTheVersion3AllowListGetsItsVerdict() {
        val cases = TestVectors.cases("allowlist-v3.json")
        val wrong = ArrayList<String>()
        val accepted = HashMap<String, Int>()
        for (case in cases) {
            val verdict = Allowlist.check(case["method"] as? String, case["target"] as? String, bodyOf(case))
            val text = if (verdict.ok) "accept:${verdict.shape!!.wire}" else "reject"
            if (verdict.ok) accepted.merge(verdict.shape!!.wire, 1, Int::plus)
            if (text != case["expect"]) wrong += "${case["name"]}: expected ${case["expect"]}, got $text (${verdict.reason})"
        }
        assertTrue("${wrong.size} wrong:\n" + wrong.take(30).joinToString("\n"), wrong.isEmpty())
        // the contract note: 64 accepted of the 506 new cases, 47 of the 273 old ones
        assertEquals(mapOf("route" to 6, "status" to 1, "health" to 1, "vehicle" to 39, "roadtiles" to 14, "stile" to 8, "geocode" to 23, "suggest" to 14, "retrieve" to 5), accepted.toMap())
        assertEquals(111, accepted.values.sum())
    }

    @Test fun theFirst273CasesAreTheCasesOfVersion2AndStayAsTheyWere() {
        val old = TestVectors.cases("allowlist.json")
        val new = TestVectors.cases("allowlist-v3.json")
        assertEquals(273, old.size)
        for (i in old.indices) {
            for (key in listOf("name", "method", "target", "body", "expect")) assertEquals("case $i $key", old[i][key], new[i][key])
        }
        assertTrue("the 506 new cases are named for the map shapes", new.drop(273).all { (it["name"] as String).let { n -> n.startsWith("roadtiles") || n.startsWith("stile") || n.startsWith("geocode") || n.startsWith("suggest") || n.startsWith("retrieve") || n.startsWith("maps-") } })
        assertEquals("the names are unique", new.size, new.map { it["name"] }.toSet().size)
    }

    @Test fun aPeerOfVersion2RefusesEveryMapShapeAndJudgesTheOldOnesAsBefore() {
        val wrong = ArrayList<String>()
        for ((i, case) in TestVectors.cases("allowlist-v3.json").withIndex()) {
            val verdict = Allowlist.check(case["method"] as? String, case["target"] as? String, bodyOf(case), version = 2)
            val expect = case["expect"] as String
            val expectOk = i < 273 && expect.startsWith("accept")
            if (verdict.ok != expectOk) wrong += "${case["name"]}: version 2 says ${verdict.ok}, expected $expectOk"
            if (expectOk && "accept:${verdict.shape!!.wire}" != expect) wrong += "${case["name"]}: shape ${verdict.shape}"
        }
        assertTrue(wrong.take(20).joinToString("\n"), wrong.isEmpty())
    }

    @Test fun generatedMapRequestsGetTheVerdictOfTheReference() {
        val file = TestVectors.file("allowlist-diff-v3.json")
        val cases = list(file["cases"]).map { it as StrictJson.Obj }
        assertEquals(2506, cases.size)
        assertEquals(2506, num(file["count"]).text.toInt())
        val wrong = ArrayList<String>()
        var accepted = 0
        for (case in cases) {
            val method = case["method"] as String; val target = case["target"] as String; val body = (case["body"] as String).toByteArray(Charsets.ISO_8859_1)
            val verdict = Allowlist.check(method, target, body)
            val expectOk = case["ok"] == true
            if (verdict.ok) accepted++
            if (verdict.ok != expectOk) wrong += "$method ${target.take(120)} body=${(case["body"] as String).take(20)}: reference says $expectOk, port says ${verdict.ok} (${verdict.reason})"
            else if (expectOk && verdict.shape?.wire != case["shape"]) wrong += "${target.take(120)}: shape ${verdict.shape} against ${case["shape"]}"
            val old = Allowlist.check(method, target, body, version = 2)
            if (old.ok != (case["ok_v2"] == true)) wrong += "$method ${target.take(120)}: judge of version 2 says ${old.ok}, reference ${case["ok_v2"]}"
        }
        assertTrue("${wrong.size} disagreements:\n" + wrong.take(20).joinToString("\n"), wrong.isEmpty())
        assertEquals(num(file["accepted"]).text.toInt(), accepted)
        assertTrue("the generator makes accepted and refused requests of every shape", accepted > 1000 && accepted < 2000)
    }

    @Test fun theTrapsForAnImplementerAreRefusedOrTakenAsTheContractSays() {
        val token = "TokenForTests"
        fun geocode(q: String) = "/search/geocode/v6/forward?q=$q&access_token=$token"
        fun ok(target: String) = Allowlist.check("GET", target).ok
        // 5,000-digit numbers: no int()-style overflow, and no exception
        val digits = "9".repeat(5000)
        assertFalse(ok("/sidecar/v1/roadtiles?z=14&t=$digits.1&access_token=$token"))
        assertFalse(ok("/styles/v1/mapbox/light-v11/tiles/512/$digits/1/1?access_token=$token"))
        // code points, not UTF-16 units and not bytes
        assertTrue(ok(geocode("%F0%9F%98%80".repeat(256)))); assertFalse(ok(geocode("%F0%9F%98%80".repeat(257))))
        assertTrue(ok(geocode("%E3%81%82".repeat(256)))); assertFalse(ok(geocode("%E3%81%82".repeat(257))))
        // overlong, surrogate, above U+10FFFF, a cut sequence, a control character, a NUL
        for (bad in listOf("%C0%AF", "%ED%A0%80", "%F4%90%80%80", "%E3%81", "%FF", "%00", "%0A", "%7F", "%1F")) assertFalse(bad, ok(geocode(bad)))
        // the percent rule: an escape of an unreserved character, lower-case hex, a plus
        for (bad in listOf("%41", "%7e", "%2e", "%2c", "a+b", "%20%", "%2")) assertFalse(bad, ok(geocode(bad)))
        assertTrue(ok(geocode("a%20b%2Cc,d"))); assertTrue(ok(geocode("%7E".replace("%7E", "~"))))
        // the session token: a UUID version 4 in lower case with hyphens
        val session = "123e4567-e89b-42d3-a456-426614174000"
        fun suggest(s: String) = "/search/searchbox/v1/suggest?q=a&access_token=$token&session_token=$s"
        assertTrue(ok(suggest(session)))
        for (bad in listOf(session.uppercase(), session.replace("-", ""), session.replace("-42d3-", "-12d3-"), session.replace("-a456-", "-c456-"), "$session\n")) assertFalse(bad, ok(suggest(bad)))
        // a token that is a valid pair and a pair that is a valid token: only the key order says no
        assertFalse(ok("/sidecar/v1/roadtiles?access_token=$token&z=14&t=1.1"))
        assertFalse(ok("/search/searchbox/v1/retrieve/abc?session_token=$session&access_token=$token"))
        assertTrue(ok("/search/searchbox/v1/retrieve/abc?access_token=$token&session_token=$session"))
        // the method is upper-case GET, the body is empty
        assertFalse(Allowlist.check("get", "/sidecar/v1/roadtiles?z=14&t=1.1&access_token=$token").ok)
        assertFalse(Allowlist.check("POST", "/sidecar/v1/roadtiles?z=14&t=1.1&access_token=$token").ok)
        assertFalse(Allowlist.check("GET", "/sidecar/v1/roadtiles?z=14&t=1.1&access_token=$token", "x".toByteArray()).ok)
    }

    // ---- results: all 121 cases --------------------------------------------------------------------------------------------------------

    private fun payloadOf(case: StrictJson.Obj): ByteArray {
        (case["payload_fill"] as? StrictJson.Obj)?.let { fill ->
            val byte = num(fill["byte"]).text.toInt(); val count = num(fill["count"]).text.toInt()
            return ByteArray(count) { byte.toByte() }
        }
        return TestVectors.hex(case["payload_hex"] as String)
    }

    @Test fun everyMapResultGetsItsVerdict() {
        val cases = TestVectors.cases("results-v3.json")
        val wrong = ArrayList<String>()
        var accepted = 0
        for (case in cases) {
            val payload = payloadOf(case)
            val size = TestVectors.int(case["size"])
            try {
                val body = JobContract.decodeMapResult(case["kind"] as String, TestVectors.int(case["status"]), case["encoding"] as String, payload, size, case["sha256"] as String)
                accepted++
                if (case["expect"] != "accept") wrong += "${case["name"]}: accepted, expected a refusal"
                else if (body.size != size || JobContract.sha256Hex(body) != case["sha256"]) wrong += "${case["name"]}: accepted with another body"
            } catch (error: ContractError) {
                if (case["expect"] == "accept") wrong += "${case["name"]}: refused (${error.message}), expected an accept"
            }
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
        assertEquals(53, accepted)
    }

    @Test fun theMapResultsAlsoGoThroughTheDispatcherOfTheJobsAndTheOldKindsKeepTheirRules() {
        // `decodeResult` is what the job path calls: for the five kinds it is the map rule, for the three old kinds it is the rule of results.json
        for (case in TestVectors.cases("results-v3.json")) {
            val kind = case["kind"] as String
            if (Shape.ofWire(kind)?.isMap != true) continue
            val payload = payloadOf(case)
            val viaMap = runCatching { JobContract.decodeMapResult(kind, TestVectors.int(case["status"]), case["encoding"] as String, payload, TestVectors.int(case["size"]), case["sha256"] as String) }
            val viaAny = runCatching { JobContract.decodeResult(kind, TestVectors.int(case["status"]), case["encoding"] as String, payload, TestVectors.int(case["size"]), case["sha256"] as String) }
            assertEquals(case["name"] as String, viaMap.isSuccess, viaAny.isSuccess)
        }
        for (case in TestVectors.cases("results.json")) {
            // the 34 old vectors: unchanged (ContractVectorsTest.resultVectors holds them; here only that no old kind is read by the map rule)
            assertFalse(case["kind"] as String, Shape.ofWire(case["kind"])?.isMap == true)
        }
    }

    // ---- the lane: the planner ---------------------------------------------------------------------------------------------------------

    @Test fun theChunkPlannerGivesTheSizesOfTheVectors() {
        val file = TestVectors.file("lane-map.json")
        val planner = file["planner"] as StrictJson.Obj
        val constants = planner["constants"] as StrictJson.Obj
        assertEquals(1024, TestVectors.int(constants["min_chunk"])); assertEquals(ChunkPlanner.MIN_CHUNK, 1024)
        assertEquals(4096, TestVectors.int(constants["map_min_chunk"])); assertEquals(4096, ChunkPlanner.MAP_MIN_CHUNK)
        assertEquals(4096, TestVectors.int(constants["map_start_bytes"])); assertEquals(4096, ChunkPlanner.MAP_START_BYTES)
        assertEquals(65536, TestVectors.int(constants["max_chunk"])); assertEquals(65536, ChunkPlanner.MAX_CHUNK)
        assertEquals(239, TestVectors.int(constants["packet_data"])); assertEquals(239, ChunkPlanner.PACKET_DATA)
        assertEquals(6144, TestVectors.int(constants["start_bytes"]))
        assertEquals(5.0, num(constants["route_cap_s"]).text.toDouble(), 0.0); assertEquals(5.0, ChunkPlanner.DEFAULT_CAP_S, 0.0)
        assertEquals(1.0, num(constants["map_cap_s"]).text.toDouble(), 0.0); assertEquals(1.0, ChunkPlanner.MAP_CAP_S, 0.0)
        val cases = list(planner["cases"]).map { it as StrictJson.Obj }
        assertEquals(16, cases.size)
        for (case in cases) {
            val start = if (case.has("start_bytes")) TestVectors.int(case["start_bytes"]) else 6144
            val planned = ChunkPlanner(num(case["cap_s"]).text.toDouble(), start, TestVectors.int(case["min_chunk"]))
            for (observation in list(case["observations"])) {
                val pair = list(observation)
                planned.observe(num(pair[0]).text.toInt(), num(pair[1]).text.toDouble())
            }
            assertEquals(case["name"] as String, TestVectors.int(case["expect"]), planned.nextBytes(TestVectors.int(case["remaining"])))
        }
    }

    @Test fun theLaneBuildsItsThreePlannersWithTheContractsSettings() {
        val lane = BleLane(object : LaneLink {
            override fun exchange(meta: org.json.JSONObject, body: ByteArray?, deadlineSeconds: Long) = org.json.JSONObject().put("status", 200)
            override fun control(action: String) = org.json.JSONObject().put("status", 200)
            override fun setHighPriority(on: Boolean) = Unit
            override fun close() = Unit
        }, System::nanoTime, false)
        try {
            assertEquals("the route: 5 s, 1 KiB at least, 6 KiB at the start", 5975, lane.planChunk(LaneClass.BULK, 1_000_000))
            assertEquals(5.0, lane.planner.capSeconds, 0.0); assertEquals(1024, lane.planner.minChunk)
            assertEquals("the class map: 1 s, 4 KiB at the start and at least", 4096, lane.planChunk(LaneClass.MAP, 1_000_000))
            assertEquals(1.0, lane.mapPlanner.capSeconds, 0.0); assertEquals(4096, lane.mapPlanner.minChunk)
            assertEquals("the answers of the searches: 1 s, the route's start and minimum", 5975, lane.planChunk(LaneClass.SMALL, 1_000_000))
            assertEquals(1.0, lane.searchPlanner.capSeconds, 0.0); assertEquals(1024, lane.searchPlanner.minChunk)
            assertEquals(777, lane.planChunk(LaneClass.INTERACTIVE, 777))
        } finally { lane.close() }
    }

    // ---- the kinds: class and deadline ------------------------------------------------------------------------------------------------

    @Test fun theKindsHaveTheClassesAndDeadlinesOfTheVectors() {
        val file = TestVectors.file("lane-map.json")
        val deadlines = file["deadlines_s"] as StrictJson.Obj
        val classes = file["classes"] as StrictJson.Obj
        assertEquals(8, deadlines.members.size); assertEquals(8, classes.members.size)
        for ((kind, deadline) in deadlines.members) {
            val shape = Shape.ofWire(kind)!!
            assertEquals(kind, num(deadline).text.toInt(), shape.deadlineSeconds)
            val lane = when (classes[kind] as String) { "bulk" -> LaneClass.BULK; "small" -> LaneClass.SMALL; "map" -> LaneClass.MAP; else -> error("class") }
            assertEquals(kind, lane, shape.jobClass)
        }
        assertEquals("the vehicle report is a POST, not a job", null, Shape.VEHICLE.jobClass)
        assertEquals(listOf("roadtiles", "stile", "geocode", "suggest", "retrieve"), Shape.values().filter { it.isMap }.map { it.wire })
        assertEquals(listOf("geocode", "suggest", "retrieve"), Shape.values().filter { it.isSearch }.map { it.wire })
    }

    // ---- the announcement --------------------------------------------------------------------------------------------------------------

    /** The bridge's reading of a `push-enable` body (`parse_announcement` of mapjobs.py), ported to test that the Companion's own bytes mean what it intends and that the vectors are what Kotlin reads. */
    private fun parseAnnouncement(body: ByteArray): Pair<Boolean, Boolean> {
        if (body.isEmpty() || body.size > 64 || body.any { it < 0 }) return false to false
        val value = try { StrictJson.parseObject(String(body, Charsets.US_ASCII)) } catch (_: StrictJson.Invalid) { return false to false }
        if (value.duplicateKey || value.members.isEmpty() || value.members.any { (k, v) -> (k != "nav" && k != "maps") || v != true }) return false to false
        return value.has("nav") to value.has("maps")
    }

    @Test fun theAnnouncementBytesAreTheContractsAndMeanWhatTheyShould() {
        assertEquals("{\"nav\":true}", PushEnable.NAV_BODY)
        assertEquals("{\"maps\":true}", PushEnable.MAPS_BODY)
        assertEquals("{\"nav\":true,\"maps\":true}", PushEnable.NAV_MAPS_BODY)
        assertEquals(null, PushEnable.body(false, false))
        assertEquals("{\"nav\":true}", PushEnable.body(true, false))
        assertEquals("{\"maps\":true}", PushEnable.body(false, true))
        assertEquals("{\"nav\":true,\"maps\":true}", PushEnable.body(true, true))
        for (nav in listOf(false, true)) for (maps in listOf(false, true)) {
            val body = PushEnable.body(nav, maps)?.toByteArray(Charsets.US_ASCII) ?: ByteArray(0)
            assertEquals("nav=$nav maps=$maps", nav to maps, parseAnnouncement(body))
        }
        // the request that carries it: codec 0, the body as base64, nothing else changes
        val request = PushEnable.request(true, true)
        assertEquals("POST", request.getString("method")); assertEquals("/_bridge/push/enable", request.getString("path"))
        assertArrayEquals("{\"nav\":true,\"maps\":true}".toByteArray(), java.util.Base64.getDecoder().decode(request.getString("body")))
        assertFalse("no body at all for a bridge that announced nothing", PushEnable.request(false, false).has("body"))
        assertArrayEquals("{\"nav\":true}".toByteArray(), java.util.Base64.getDecoder().decode(PushEnable.request(true).getString("body")))
    }

    @Test fun everyAnnouncementVectorIsReadAsTheVectorSays() {
        val cases = list(TestVectors.file("lane-map.json")["announcement"]).map { it as StrictJson.Obj }
        assertEquals(24, cases.size)
        val bodies = HashSet<String>()
        for (case in cases) {
            val body = case["body"] as String
            bodies += body
            val expect = case["expect"] as StrictJson.Obj
            assertEquals(case["name"] as String, (expect["nav"] == true) to (expect["maps"] == true), parseAnnouncement(body.toByteArray(Charsets.ISO_8859_1).let { if (body.any { it.code > 127 }) body.toByteArray(Charsets.UTF_8) else it }))
        }
        for (own in listOf(PushEnable.NAV_BODY, PushEnable.MAPS_BODY, PushEnable.NAV_MAPS_BODY)) assertTrue("the vectors hold the Companion's own body $own", own in bodies)
    }

    // ---- the flags of the health answer ----------------------------------------------------------------------------------------------

    @Test fun theAdmissionVectorsAreReadFromTheHealthAnswerAsTheCommaReadsThem() {
        val cases = list(TestVectors.file("lane-map.json")["admission"]).map { it as StrictJson.Obj }
        assertEquals(26, cases.size)
        for (case in cases) {
            val health = case["health"]
            val text = when (health) { is StrictJson.Obj -> health.raw; is List<*> -> "[]"; else -> "null" }
            val features = BridgeFeatures.parse(text.toByteArray())
            val admission = features.mapAdmission
            val expect = case["expect"] as String
            assertEquals(case["name"] as String, expect, admission.mode.name.lowercase())
            assertEquals(case["name"] as String, when (expect) { "full" -> 16; "probe" -> 2; else -> 0 }, admission.maxTiles)
        }
        assertEquals(2, TestVectors.int(TestVectors.file("lane-map.json")["probe_max_tiles"])); assertEquals(2, MapAdmission.PROBE_MAX_TILES)
        assertEquals(10.0, num(TestVectors.file("lane-map.json")["min_up_kb_s"]).text.toDouble(), 0.0); assertEquals(10.0, MapAdmission.MIN_UP_KB_S, 0.0)
    }

    @Test fun theHealthFlagsAreTheLiteralTrueAndAFiniteNumber() {
        fun features(json: String) = BridgeFeatures.parse(json.toByteArray())
        val f = features("{\"protocol\":1,\"poll2\":true,\"results2\":true,\"push\":true,\"pushNav\":true,\"maps\":true,\"wwr\":true,\"up_kb_s\":41.3}")
        assertTrue(f.maps); assertTrue(f.wwr); assertEquals(41.3, f.upKbS!!, 0.0); assertFalse(f.upKbSInvalid); assertTrue(f.pushNav)
        for (bad in listOf("1", "\"true\"", "null", "false", "[true]")) { val g = features("{\"maps\":$bad,\"wwr\":$bad}"); assertFalse(bad, g.maps); assertFalse(bad, g.wwr) }
        assertEquals(null, features("{\"maps\":true,\"wwr\":true}").upKbS)
        assertFalse(features("{\"maps\":true,\"wwr\":true}").upKbSInvalid)
        for (bad in listOf("\"41.3\"", "null", "true", "[41]", "1e999")) { val g = features("{\"maps\":true,\"wwr\":true,\"up_kb_s\":$bad}"); assertEquals(bad, null, g.upKbS); assertTrue(bad, g.upKbSInvalid) }
        val legacy = BridgeFeatures.LEGACY
        assertFalse(legacy.maps); assertFalse(legacy.wwr); assertEquals(null, legacy.upKbS)
        assertEquals("not JSON: the old bridge's behaviour", MapAdmission.Mode.CLOSED, features("{\"maps\":true").mapAdmission.mode)
    }

    @Test fun theMeterFollowsTheVectorsOfTheBridgesUplinkMeter() {
        val cases = list(TestVectors.file("lane-map.json")["uplink_meter"]).map { it as StrictJson.Obj }
        assertEquals(10, cases.size)
        for (case in cases) {
            val meter = UplinkMeter()
            for (chunk in list(case["chunks"])) { val pair = list(chunk); meter.observe(num(pair[0]).text.toInt(), num(pair[1]).text.toDouble()) }
            val expect = case["expect"]
            if (expect == StrictJson.Null) assertEquals(case["name"] as String, null, meter.upKbS)
            else assertEquals(case["name"] as String, num(expect).text.toDouble(), meter.upKbS!!, 0.0)
        }
        assertNotNull(UplinkMeter().also { it.observe(UplinkMeter.MIN_BODY, 1.0) }.upKbS)
    }
}
