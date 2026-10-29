package link.firestar.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drive status of contract version 4 held to the shared vectors (`drive-v4.json`: 147 hand-written bodies with the strict verdict of the reference `problem()`, 26 announcement bodies,
 * 9 aging cases) and to 3,053 generated bodies with the verdict of the same reference (`drive-diff-v4.json`). The allow-list, the announcement bytes and the aging are the Companion's own code.
 */
class DriveContractVectorsTest {
    private val vectors get() = TestVectors.file("drive-v4.json")
    private fun cases(name: String): List<StrictJson.Obj> = TestVectors.file("drive-v4.json").let { @Suppress("UNCHECKED_CAST") (it[name] as List<StrictJson.Obj>) }
    /** The body of a vector: the latin-1 text of its raw bytes (that is what the `bytes` flag says; the others are plain ASCII). */
    private fun bodyOf(case: StrictJson.Obj): ByteArray = (case["body"] as String).toByteArray(Charsets.ISO_8859_1)

    @Test fun theVectorFileIsWhole() {
        assertEquals("4", (vectors["version"] as StrictJson.Num).text)
        assertEquals(147, cases("cases").size)
        assertEquals(26, cases("announce").size)
        assertEquals(9, cases("aged").size)
        assertEquals("a copy that lost cases would pass too easily", 49, cases("cases").count { it["allow"] == true })
        for (case in cases("cases")) assertEquals(case["name"].toString(), (case["bytes"] == true), case["body"].toString().any { it.code > 0x7E || it.code < 0x20 })
    }

    @Test fun everyCaseOfTheVectorsGetsTheVerdictOfTheReference() {
        val wrong = ArrayList<String>()
        for (case in cases("cases")) {
            val body = bodyOf(case)
            val problem = Allowlist.driveBodyProblem(body)
            if ((problem.isEmpty()) != (case["allow"] == true)) wrong += "${case["name"]}: expected allow=${case["allow"]}, got '${problem}'"
            val verdict = Allowlist.check("POST", "/v1/drive", body)
            if (verdict.ok != (case["allow"] == true)) wrong += "${case["name"]}: check() says ${verdict.ok} (${verdict.reason})"
            if (verdict.ok && verdict.shape != Shape.DRIVE) wrong += "${case["name"]}: shape ${verdict.shape}"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test fun everyAllowedCaseIsAlsoReadByTheX10ReaderOfTheVectors() {
        for (case in cases("cases")) if (case["allow"] == true) assertNotNull("${case["name"]} is allowed but the reader reads nothing", case["read"].takeIf { it != StrictJson.Null })
    }

    @Test fun theGeneratedBodiesGetTheVerdictOfTheReference() {
        val file = TestVectors.file("drive-diff-v4.json")
        @Suppress("UNCHECKED_CAST") val generated = file["cases"] as List<StrictJson.Obj>
        assertEquals(3053, generated.size)
        var accepted = 0
        val wrong = ArrayList<String>()
        for (case in generated) {
            val body = (case["body"] as String).toByteArray(Charsets.ISO_8859_1)
            val ok = Allowlist.driveBodyProblem(body).isEmpty()
            if (ok) accepted++
            if (ok != (case["allow"] == true)) wrong += "${case["name"]}: reference says ${case["allow"]}, port says $ok (${Allowlist.driveBodyProblem(body)}) for ${(case["body"] as String).take(160).replace(Regex("[^ -~]"), "?")}"
            if (Allowlist.check("POST", "/v1/drive", body).ok != ok) wrong += "${case["name"]}: check() and driveBodyProblem() differ"
        }
        assertTrue("${wrong.size} disagreements:\n" + wrong.take(20).joinToString("\n"), wrong.isEmpty())
        assertEquals(1372, accepted)
    }

    @Test fun everyCharacterOfTheTextRuleAndNoOtherPrintableOne() {
        val allowed = ('A'..'Z') + ('a'..'z') + ('0'..'9') + " .,:;!?'%/()+-".toList()
        for (code in 0x20..0x7E) {
            val c = code.toChar()
            val raw = if (c == '"' || c == '\\') "\\$c" else c.toString() // a quote or a backslash needs its escape to be JSON at all
            val body = "{\"v\":1,\"seq\":7,\"age_ms\":12,\"al\":1,\"a1\":\"x${raw}x\"}".toByteArray()
            assertEquals("character '$c' (0x${code.toString(16)})", c in allowed, Allowlist.driveBodyProblem(body).isEmpty())
            val unicode = "{\"v\":1,\"seq\":7,\"age_ms\":12,\"al\":1,\"a2\":\"x\\u%04xx\"}".format(code).toByteArray()
            assertEquals("escaped character '$c'", c in allowed, Allowlist.driveBodyProblem(unicode).isEmpty())
        }
        for (n in listOf(0, 1, 39, 40, 41)) assertEquals("text of $n", n in 1..40, Allowlist.driveBodyProblem("{\"v\":1,\"seq\":7,\"age_ms\":12,\"al\":2,\"a1\":\"${"y".repeat(n)}\"}".toByteArray()).isEmpty())
    }

    @Test fun theBoundsOfTheNumbersAndOfTheBody() {
        fun ok(text: String) = Allowlist.driveBodyProblem(text.toByteArray()).isEmpty()
        assertTrue(ok("{\"v\":1,\"seq\":2147483647,\"age_ms\":60000}")); assertFalse(ok("{\"v\":1,\"seq\":2147483648,\"age_ms\":60000}"))
        assertTrue(ok("{\"v\":1,\"seq\":0,\"age_ms\":0}")); assertFalse(ok("{\"v\":1,\"seq\":-1,\"age_ms\":0}"))
        assertFalse(ok("{\"v\":1,\"seq\":0,\"age_ms\":60001}")); assertFalse(ok("{\"v\":1,\"seq\":0,\"age_ms\":-1}"))
        assertTrue("-0 is the integer 0 for the reference", ok("{\"v\":1,\"seq\":-0,\"age_ms\":0}"))
        for (al in listOf("-1", "4", "1.0", "1e0", "true", "\"1\"", "null")) assertFalse("al $al", ok("{\"v\":1,\"seq\":1,\"age_ms\":1,\"al\":$al}"))
        for (al in 0..3) assertTrue("al $al", ok("{\"v\":1,\"seq\":1,\"age_ms\":1,\"al\":$al}"))
        val core = "{\"v\":1,\"seq\":7,\"age_ms\":12}"
        for (size in listOf(511, 512)) assertTrue("a body of $size bytes", ok(core + " ".repeat(size - core.length)))
        assertFalse("a body of 513 bytes", ok(core + " ".repeat(513 - core.length)))
        assertFalse("an empty body", Allowlist.driveBodyProblem(ByteArray(0)).isEmpty())
    }

    @Test fun thePairsNeedTheirPartner() {
        fun ok(text: String) = Allowlist.driveBodyProblem("{\"v\":1,\"seq\":7,\"age_ms\":12,$text}".toByteArray()).isEmpty()
        assertFalse(ok("\"ld\":\"l\"")); assertFalse(ok("\"lc\":\"off\",\"ld\":\"r\"")); for (lc in listOf("pre", "go", "fin")) assertTrue(ok("\"lc\":\"$lc\",\"ld\":\"l\""))
        assertFalse(ok("\"lp\":\"man\"")); for (lc in listOf("off", "go", "fin")) assertFalse(ok("\"lc\":\"$lc\",\"lp\":\"na\"")); assertTrue(ok("\"lc\":\"pre\",\"lp\":\"blk\""))
        assertFalse(ok("\"ov\":\"gas\"")); for (st in listOf("dis", "pre", "en", "soft")) assertFalse(ok("\"st\":\"$st\",\"ov\":\"steer\"")); assertTrue(ok("\"st\":\"ovr\",\"ov\":\"steer\""))
        for (key in listOf("\"ak\":\"take\"", "\"a1\":\"x\"", "\"a2\":\"x\"")) {
            assertFalse(key, ok(key)); assertFalse("$key with al 0", ok("\"al\":0,$key")); for (al in 1..3) assertTrue("$key with al $al", ok("\"al\":$al,$key"))
        }
        assertTrue("the pairs do not depend on the order of the keys", ok("\"ld\":\"l\",\"lc\":\"go\""))
    }

    @Test fun onlyThePostOfTheShapeIsTheShape() {
        val body = "{\"v\":1,\"seq\":7,\"age_ms\":12,\"st\":\"en\"}".toByteArray()
        assertTrue(Allowlist.check("POST", "/v1/drive", body).ok)
        for (method in listOf("GET", "PUT", "DELETE", "HEAD", "PATCH", "post", "", null)) assertFalse("$method", Allowlist.check(method, "/v1/drive", body).ok)
        for (target in listOf("/v1/drive?x=1", "/v1/drive?", "/v1/drive/", "/v1/Drive", "/V1/drive", "/v1/drive#x", "//v1/drive", "v1/drive", "/v1/drive%20", "/v1/driver", "/v1//drive", "/v1/vehicle/drive", "/drive", "/v1/drive;x"))
            assertFalse(target, Allowlist.check("POST", target, body).ok)
        assertFalse("no body", Allowlist.check("POST", "/v1/drive", ByteArray(0)).ok)
    }

    @Test fun aPeerOfVersion3KnowsNoDriveShapeAndVersion3IsAsItWas() {
        val body = "{\"v\":1,\"seq\":7,\"age_ms\":12}".toByteArray()
        assertTrue(Allowlist.check("POST", "/v1/drive", body, version = 4).ok)
        val old = Allowlist.check("POST", "/v1/drive", body, version = 3)
        assertFalse(old.ok); assertEquals("path", old.reason)
        assertFalse(Allowlist.check("POST", "/v1/drive", body, version = 2).ok)
        assertEquals(4, Allowlist.CONTRACT_VERSION)
        // the vectors of versions 2 and 3: the same verdict, shape and reason as the judge of version 3 gave them (none of them has a drive request)
        for (file in listOf("allowlist.json", "allowlist-v3.json")) for (case in TestVectors.cases(file)) {
            val request = Triple(case["method"] as? String, case["target"] as? String, ((case["body"] as? String) ?: "").toByteArray(Charsets.UTF_8))
            val now = Allowlist.check(request.first, request.second, request.third)
            val then = Allowlist.check(request.first, request.second, request.third, version = 3)
            assertEquals("${case["name"]}", then.ok, now.ok); assertEquals("${case["name"]}", then.shape, now.shape); assertEquals("${case["name"]}", then.reason, now.reason)
        }
    }

    @Test fun theVehicleShapeAndTheDriveShapeDoNotTakeEachOthersBodies() {
        val position = "{\"v\":1,\"seq\":1,\"fix\":true,\"lat\":35.681201,\"lon\":139.767102,\"bearing\":250.5,\"speed\":13.89,\"age_ms\":120,\"src\":\"gnss\"}".toByteArray()
        assertTrue(Allowlist.check("POST", "/v1/vehicle", position).ok); assertFalse(Allowlist.check("POST", "/v1/drive", position).ok)
        val drive = "{\"v\":1,\"seq\":7,\"age_ms\":12,\"st\":\"en\",\"al\":1,\"a1\":\"Hello\",\"lc\":\"pre\",\"ld\":\"l\"}".toByteArray()
        assertTrue(Allowlist.check("POST", "/v1/drive", drive).ok); assertFalse(Allowlist.check("POST", "/v1/vehicle", drive).ok)
        val nav = "{\"v\":1,\"seq\":7,\"age_ms\":12,\"nav\":{\"v\":1,\"age_ms\":3}}".toByteArray()
        assertTrue(Allowlist.check("POST", "/v1/vehicle", nav).ok); assertFalse("a nav key inside a drive object", Allowlist.check("POST", "/v1/drive", nav).ok)
        // the drive-only keys: the vehicle shape refuses each of them
        for (item in listOf("\"st\":\"en\"", "\"rdy\":true", "\"ov\":\"gas\"", "\"al\":1", "\"ak\":\"lane\"", "\"a1\":\"x\"", "\"a2\":\"x\"", "\"lc\":\"pre\"", "\"ld\":\"l\"", "\"lp\":\"man\"", "\"crv\":true", "\"stp\":true"))
            assertFalse(item, Allowlist.check("POST", "/v1/vehicle", "{\"v\":1,\"seq\":1,\"age_ms\":5,$item}".toByteArray()).ok)
    }

    @Test fun aJobOfTheKindDriveIsNotAJob() {
        fun list(kind: String) = "{\"v\":2,\"jobs\":[{\"id\":\"${"a".repeat(32)}\",\"kind\":\"$kind\",\"target\":\"/status\",\"next_sequence\":0,\"claimed\":false,\"lease_ms\":5000}],\"next_poll_ms\":1000}".toByteArray()
        assertEquals(1, SidecarJobs.parseJobList(list("status")).jobs.size)
        for (kind in listOf("drive", "vehicle")) assertTrue(kind, runCatching { SidecarJobs.parseJobList(list(kind)) }.isFailure)
        assertEquals(listOf("roadtiles", "stile", "geocode", "suggest", "retrieve"), Shape.values().filter { it.isMap }.map { it.wire })
        assertFalse(Shape.DRIVE.isMap); assertFalse(Shape.DRIVE.isSearch); assertEquals(null, Shape.DRIVE.jobClass)
    }

    // ---- the announcement ------------------------------------------------------------------------------------------------------------------

    /** The reading of a `push-enable` body by the bridge (`announcement` of drive.py), ported here so that the Companion's own bytes are shown to mean what it intends. */
    private fun announced(body: ByteArray): Triple<Boolean, Boolean, Boolean> {
        val none = Triple(false, false, false)
        if (body.size !in 1..64 || body.any { it < 0 }) return none
        val value = try { StrictJson.parseObject(String(body, Charsets.US_ASCII)) } catch (_: StrictJson.Invalid) { return none }
        if (value.duplicateKey || value.members.isEmpty() || value.members.any { (k, v) -> k !in listOf("nav", "maps", "drive") || v != true }) return none
        return Triple(value.has("nav"), value.has("maps"), value.has("drive"))
    }

    @Test fun thePortOfTheAnnouncementReaderAgreesWithTheVectors() {
        for (case in cases("announce")) {
            val got = announced((case["body"] as String).toByteArray(Charsets.ISO_8859_1))
            assertEquals(case["body"].toString(), Triple(case["nav"] == true, case["maps"] == true, case["drive"] == true), got)
        }
    }

    @Test fun theAnnouncementIsTheExactBytesOfTheReferenceForAllEightCombinations() {
        // the first eight vectors are the canonical bodies of the eight combinations, in the order of the bits nav = 1, maps = 2, drive = 4
        val canonical = cases("announce").take(8)
        val seen = HashSet<Triple<Boolean, Boolean, Boolean>>()
        for (case in canonical) {
            val flags = Triple(case["nav"] == true, case["maps"] == true, case["drive"] == true)
            assertTrue("a combination twice", seen.add(flags))
            val mine = PushEnable.body(flags.first, flags.second, flags.third)
            assertEquals("the bytes of $flags", case["body"], mine ?: "")
            assertEquals(flags, announced((mine ?: "").toByteArray(Charsets.US_ASCII)))
            val request = PushEnable.request(flags.first, flags.second, flags.third)
            assertEquals("POST", request.getString("method")); assertEquals("/_bridge/push/enable", request.getString("path"))
            if (mine == null) assertFalse("no announcement, no body: the request an older bridge always got", request.has("body"))
            else assertEquals(mine, String(java.util.Base64.getDecoder().decode(request.getString("body")), Charsets.US_ASCII))
        }
        assertEquals(8, seen.size)
        // without drive: byte for byte what it was before contract version 4
        assertEquals("{\"nav\":true}", PushEnable.body(true, false)); assertEquals("{\"maps\":true}", PushEnable.body(false, true)); assertEquals("{\"nav\":true,\"maps\":true}", PushEnable.body(true, true))
        assertEquals(null, PushEnable.body(false, false))
        assertEquals("{\"nav\":true,\"maps\":true,\"drive\":true}", PushEnable.body(true, true, true))
    }

    // ---- the aging ---------------------------------------------------------------------------------------------------------------------------

    /** The compact JSON of an object of the vectors, in the order of the keys (`encode` of drive.py). */
    private fun encode(obj: StrictJson.Obj): ByteArray = DriveContract.KEYS.filter { obj.has(it) }.joinToString(",", "{", "}") { key ->
        val item = obj[key]
        "\"$key\":" + if (item is String) "\"$item\"" else item.toString()
    }.toByteArray(Charsets.US_ASCII)

    @Test fun theAgingOfTheVectorsIsTheAgingOfTheReference() {
        for (case in cases("aged")) {
            val wait = (case["wait_ms"] as StrictJson.Num).text.toLong()
            val before = encode(case["object"] as StrictJson.Obj)
            assertTrue(Allowlist.driveBodyProblem(before).isEmpty())
            val got = DriveFrames.withAge(before, wait)
            assertEquals("${case["object"]} after $wait ms", String(encode(case["expected"] as StrictJson.Obj)), String(got))
            assertTrue(Allowlist.driveBodyProblem(got).isEmpty())
        }
    }

    @Test fun onlyAgeMsGrowsAndItSaturatesAndNeverShrinks() {
        val body = "{\"v\":1,\"seq\":9,\"age_ms\":59990,\"st\":\"en\",\"al\":2,\"a1\":\"Take Over\"}".toByteArray()
        assertEquals("{\"v\":1,\"seq\":9,\"age_ms\":60000,\"st\":\"en\",\"al\":2,\"a1\":\"Take Over\"}", String(DriveFrames.withAge(body, 10)))
        assertEquals("{\"v\":1,\"seq\":9,\"age_ms\":60000,\"st\":\"en\",\"al\":2,\"a1\":\"Take Over\"}", String(DriveFrames.withAge(body, 10_000_000)))
        assertEquals("{\"v\":1,\"seq\":9,\"age_ms\":60000,\"st\":\"en\",\"al\":2,\"a1\":\"Take Over\"}", String(DriveFrames.withAge(body, Long.MAX_VALUE)))
        assertEquals("{\"v\":1,\"seq\":9,\"age_ms\":59991,\"st\":\"en\",\"al\":2,\"a1\":\"Take Over\"}", String(DriveFrames.withAge(body, 1)))
        assertArrayEquals("no wait: the body as it came", body, DriveFrames.withAge(body, 0))
        val spaced = "{ \"v\" : 1, \"seq\" : 9, \"age_ms\" : 5 }".toByteArray()
        assertArrayEquals("an age that does not change: the body as it came, byte for byte", spaced, DriveFrames.withAge(spaced, 0))
        assertEquals("an age that changes: the object written again", "{\"v\":1,\"seq\":9,\"age_ms\":6}", String(DriveFrames.withAge(spaced, 1)))
        assertEquals("a minus zero is the age 0", "{\"v\":1,\"seq\":9,\"age_ms\":0}", String(DriveFrames.withAge("{\"v\":1,\"seq\":9,\"age_ms\":-0}".toByteArray(), 0)))
        assertTrue(runCatching { DriveFrames.withAge(body, -1) }.exceptionOrNull() is PushError)
        for (bad in listOf("not json", "{\"v\":1,\"seq\":1}", "{\"v\":1,\"seq\":1,\"age_ms\":60001}", "{\"v\":1,\"seq\":1,\"age_ms\":-1}", "{\"v\":1,\"seq\":1,\"age_ms\":1.5}", "{\"v\":1,\"seq\":1,\"age_ms\":\"3\"}", "[]"))
            assertTrue(bad, runCatching { DriveFrames.withAge(bad.toByteArray(), 5) }.exceptionOrNull() is PushError)
        // a text that holds the characters of the rule is written back as it came
        val text = "{\"v\":1,\"seq\":9,\"age_ms\":1,\"al\":1,\"a1\":\"Az09 .,:;!?'%/()+-\"}"
        assertEquals(text.replace("\"age_ms\":1,", "\"age_ms\":251,"), String(DriveFrames.withAge(text.toByteArray(), 250)))
    }
}
