package link.firestar.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** The allow-list beyond the hand-written vectors: 960 generated requests (560 of contract version 1, 400 with a nav object) with the verdict of the reference (Python's `json` and `re`), properties, and a fuzz run. */
class AllowlistTest {
    private fun accepts(method: String, target: String, body: String = "") = Allowlist.check(method, target, body.toByteArray(Charsets.ISO_8859_1)).ok
    private fun v(vararg members: String) = "{" + members.joinToString(",") + "}"

    @Test fun generatedRequestsGetTheVerdictOfTheReference() {
        val cases = TestVectors.cases("allowlist-diff.json")
        assertEquals(960, cases.size)
        val wrong = ArrayList<String>()
        var accepted = 0
        for (case in cases) {
            val verdict = Allowlist.check(case["method"] as String, case["target"] as String, (case["body"] as String).toByteArray(Charsets.ISO_8859_1))
            val expectOk = case["ok"] == true
            if (verdict.ok) accepted++
            if (verdict.ok != expectOk) wrong += "${case["method"]} ${(case["target"] as String).take(90).replace("\n", "\\n")} body=${(case["body"] as String).take(100)}: reference says $expectOk, port says ${verdict.ok} (${verdict.reason})"
            else if (expectOk && verdict.shape?.wire != case["shape"]) wrong += "${case["target"]}: shape ${verdict.shape} against ${case["shape"]}"
        }
        assertTrue("${wrong.size} disagreements:\n" + wrong.take(20).joinToString("\n"), wrong.isEmpty())
        assertEquals(233, accepted)
    }

    @Test fun theFourShapesAndNothingElse() {
        assertTrue(accepts("GET", "/directions/v5/mapbox/driving-traffic/139.7671,35.6812;139.7016,35.6580?access_token=T"))
        assertTrue(accepts("GET", "/status")); assertTrue(accepts("GET", "/health"))
        assertTrue(accepts("POST", "/v1/vehicle", v("\"v\":1", "\"seq\":1", "\"fix\":false")))
        for (target in listOf("/api/navigation", "/api/software/status", "/v1/vehicle", "/directions/v5/mapbox/driving/139.7,35.6;139.8,35.7?x=1", "/_bridge/health", "/")) assertFalse(target, accepts("GET", target))
        for (method in listOf("PUT", "DELETE", "HEAD", "OPTIONS", "PATCH", "get", "Get", "")) assertFalse(method, accepts(method, "/status"))
    }

    @Test fun aRouteNeedsACoordinateListAndAQuery() {
        val p = "/directions/v5/mapbox/driving-traffic/"
        assertFalse(accepts("GET", p + "139.7,35.6?x=1")) // one point
        assertFalse(accepts("GET", p + "139.7,35.6;139.8,35.7")) // no query
        assertFalse(accepts("GET", p + "139.7,35.6;139.8,35.7?")) // an empty query
        assertFalse(accepts("GET", p + "139.7,35.6;139.8,35.7;?x=1")) // a trailing ;
        assertFalse(accepts("GET", p + "abc?x=1"))
        assertFalse(accepts("GET", p + "..?x=1"))
        assertFalse(accepts("GET", p + "139.7,35.6;139.8,35.7/x?x=1"))
        assertFalse(accepts("GET", p + "%31,2;3,4?x=1"))
        assertTrue(accepts("GET", p + "-1.5e-7,+2.5;3,4?x=1"))
    }

    /** A coordinate list of exactly [n] characters: pairs of `1,2` joined by `;`, the last number padded with digits. */
    private fun segmentOf(n: Int): String {
        var s = "1,2"
        while (s.length + 4 <= n) s += ";1,2"
        return s + "2".repeat(n - s.length)
    }

    @Test fun aRouteSegmentOfExactly256CharactersPassesAndOneMoreDoesNot() {
        val p = "/directions/v5/mapbox/driving-traffic/"
        assertEquals(256, segmentOf(256).length)
        assertTrue(accepts("GET", p + segmentOf(256) + "?x=1"))
        assertFalse(accepts("GET", p + segmentOf(257) + "?x=1"))
    }

    @Test fun printableAsciiOnlyAndNoFragmentOrBackslash() {
        val base = "/directions/v5/mapbox/driving-traffic/1,2;3,4?x="
        for (bad in listOf(" ", "\t", "\n", "\r", "\u007f", "é", "\u0000", "#frag", "\\", "\u202e", "\ufeff")) assertFalse(bad.toByteArray().contentToString(), accepts("GET", base + bad))
        assertTrue(accepts("GET", base + "a%20b&c=d~!"))
    }

    @Test fun aTargetOver8192BytesIsRefusedAndOneOf8192Passes() {
        val prefix = "/directions/v5/mapbox/driving-traffic/1,2;3,4?x="
        assertTrue(accepts("GET", prefix + "a".repeat(8192 - prefix.length)))
        assertFalse(accepts("GET", prefix + "a".repeat(8193 - prefix.length)))
    }

    @Test fun vehicleBodiesFollowTheBeaconContract() {
        val ok = listOf(
            v("\"v\":1", "\"seq\":5", "\"fix\":false"),
            v("\"v\":1", "\"seq\":5", "\"fix\":true", "\"lat\":35.681201", "\"lon\":139.767102", "\"bearing\":250.5", "\"speed\":13.89", "\"age_ms\":120", "\"src\":\"gnss\""),
            v("\"v\":1", "\"hold\":true", "\"hdg\":\"ok2\"", "\"snap_m\":-0", "\"vego\":1E5", "\"dr_s\":1e-05"),
        )
        for (body in ok) assertTrue(body, accepts("POST", "/v1/vehicle", body))
        val bad = listOf(
            "", "{}", "[]", "null", "1", "\"v\"", v("\"seq\":1"), v("\"v\":2"), v("\"v\":1.0"), v("\"v\":true"), v("\"v\":\"1\""), v("\"v\":1", "\"v\":1"), v("\"v\":1", "\"cmd\":1"),
            v("\"v\":1", "\"lat\":null"), v("\"v\":1", "\"lat\":\"35\""), v("\"v\":1", "\"lat\":[1]"), v("\"v\":1", "\"lat\":{\"a\":1}"), v("\"v\":1", "\"lat\":NaN"), v("\"v\":1", "\"lat\":Infinity"),
            v("\"v\":1", "\"lat\":-Infinity"), v("\"v\":1", "\"lat\":1e999"), v("\"v\":1", "\"lat\":01"), v("\"v\":1", "\"lat\":1."), v("\"v\":1", "\"lat\":.5"), v("\"v\":1", "\"lat\":+1"),
            v("\"v\":1", "\"src\":1"), v("\"v\":1", "\"src\":\"\""), v("\"v\":1", "\"src\":\"a b\""), v("\"v\":1", "\"src\":\"" + "a".repeat(17) + "\""), v("\"v\":1", "\"src\":\"a-b\""), v("\"v\":1", "\"hdg\":null"),
            v("\"v\":1", "\"src\":\"\\u00e9\""), v("\"v\":1", "\"V\":1"), v("\"v\":1", "\"\\u0063md\":1"), "{\"v\":1} x", " {\"v\":1}\n", "{\"v\":1,}", "{'v':1}",
        )
        for (body in bad) assertFalse("accepted: $body", accepts("POST", "/v1/vehicle", body))
        assertFalse("a query", accepts("POST", "/v1/vehicle?x=1", ok[0]))
        assertFalse("GET with a vehicle body", accepts("GET", "/v1/vehicle", ok[0]))
        assertFalse("a GET status with a body", accepts("GET", "/status", ok[0]))
    }

    @Test fun aBodyOf512BytesPassesAndOneOf513DoesNot() {
        val head = "{\"v\":1,\"seq\":1,\"fix\":true,\"lat\":35.11111111,\"lon\":139.11111111,\"snap_m\":"
        fun body(n: Int) = head + "1".repeat(n - head.length - 1) + "}"
        assertEquals(512, body(512).length)
        assertTrue(accepts("POST", "/v1/vehicle", body(512)))
        assertFalse(accepts("POST", "/v1/vehicle", body(513)))
    }

    @Test fun fuzzNoInputMakesTheCheckThrow() {
        val rng = Random(9)
        val alphabet = "{}[]\",:.-+eE0123456789 \tnulltruefalseNaN/?;#\\%v seq lat src hdgé\u0000\n~!".toCharArray()
        repeat(20_000) {
            val text = String(CharArray(rng.nextInt(60)) { alphabet[rng.nextInt(alphabet.size)] })
            val method = listOf("GET", "POST", "get", "")[rng.nextInt(4)]
            val target = listOf("/status", "/v1/vehicle", "/directions/v5/mapbox/driving-traffic/$text", "/$text", text)[rng.nextInt(5)]
            Allowlist.check(method, target, text.toByteArray(Charsets.ISO_8859_1))
            Allowlist.check(method, null, ByteArray(0)); Allowlist.check(null, target)
        }
    }

    @Test fun theRouteShapeIsWhatTheRouteWorkerAccepts() {
        assertTrue(SidecarRouteWorker.validTarget("/directions/v5/mapbox/driving-traffic/139.0,35.0;139.1,35.1?access_token=abcdefghij&steps=true"))
        assertFalse(SidecarRouteWorker.validTarget("/status"))
        assertFalse(SidecarRouteWorker.validTarget("http://evil.invalid/directions/v5/mapbox/driving-traffic/139.0,35.0;139.1,35.1?x=1"))
        assertFalse("the old rule let any one segment through", SidecarRouteWorker.validTarget("/directions/v5/mapbox/driving-traffic/anything?x=1"))
        assertFalse("the old rule accepted a % in the query only; a % in the path is refused as before", SidecarRouteWorker.validTarget("/directions/v5/mapbox/driving-traffic/%2e%2e?access_token=x"))
    }
}
