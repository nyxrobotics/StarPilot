package link.firestar.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The frozen contract vectors of docs/dev/bluetooth-link.md A.4, read by the Companion's code exactly as the comma's Python reads them. */
class ContractVectorsTest {
    private fun bodyOf(case: StrictJson.Obj): ByteArray = (case["body"] as? String)?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)

    @Test fun allowListVectors() {
        val cases = TestVectors.cases("allowlist.json")
        assertEquals(119, cases.size)
        val wrong = ArrayList<String>()
        var accepted = 0
        for (case in cases) {
            val verdict = Allowlist.check(case["method"] as? String, case["target"] as? String, bodyOf(case))
            val text = if (verdict.ok) "accept:${verdict.shape!!.wire}" else "reject"
            if (verdict.ok) accepted++
            if (text != case["expect"]) wrong += "${case["name"]}: expected ${case["expect"]}, got $text (${verdict.reason})"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
        assertEquals(11, accepted)
    }

    @Test fun resultVectors() {
        val cases = TestVectors.cases("results.json")
        assertEquals(34, cases.size)
        val wrong = ArrayList<String>()
        for (case in cases) {
            val payload = TestVectors.hex(case["payload_hex"] as String)
            val size = TestVectors.int(case["size"])
            try {
                val body = JobContract.decodeResult(case["kind"] as String, TestVectors.int(case["status"]), case["encoding"] as String, payload, size, case["sha256"] as String)
                if (case["expect"] != "accept") wrong += "${case["name"]}: accepted, expected a refusal"
                else if (body.size != size || JobContract.sha256Hex(body) != case["sha256"]) wrong += "${case["name"]}: accepted with another body"
            } catch (error: ContractError) {
                if (case["expect"] == "accept") wrong += "${case["name"]}: refused (${error.message}), expected an accept"
            }
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test fun chunkPathVectors() {
        val file = TestVectors.file("chunk-targets.json")
        val job = file["job_id"] as String
        val cases = file["cases"] as List<*>
        assertEquals(25, cases.size)
        val wrong = ArrayList<String>()
        for (item in cases) {
            val case = item as StrictJson.Obj
            try {
                val parsed = JobContract.parseChunkTarget(case["target"] as? String)
                if (case["expect"] != "accept") wrong += "${case["name"]}: accepted, expected a refusal"
                else if (parsed.jobId != job) wrong += "${case["name"]}: wrong job id"
            } catch (error: ContractError) {
                if (case["expect"] == "accept") wrong += "${case["name"]}: refused (${error.message}), expected an accept"
            }
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    /** `frames.json` holds frames made by the real production `protocol.py`: the codec-2 frame of the Companion's `sealRaw` is the one the lab twin made, byte for byte, and what production opens. */
    @Test fun rawBodyFrameIsByteForByteWhatProductionOpens() {
        val key = TestVectors.hex(TestVectors.string(TestVectors.file("frames.json")["key_hex"]))
        for (case in TestVectors.cases("frames.json")) {
            val request = case["request"] as StrictJson.Obj
            val body = TestVectors.hex(case["body_hex"] as String)
            val meta = TestVectors.compact(request.members.filterKeys { it != "body" }.map { (k, v) -> k to (v as String) })
            val frame = BleWire.sealRaw(meta.toByteArray(), body, key, "request", TestVectors.nonce(4))
            assertEquals("${case["name"]}: frame", case["twin_codec2_stored"], TestVectors.hex(frame))
            val decoded = BleWire.decode(frame.copyOfRange(4, frame.size), key, "request")
            assertEquals(meta, String(decoded.json, Charsets.UTF_8))
            assertArrayEquals(body, decoded.body)
            // the same frame as the production module opened it: the request without its body, and the body as base64
            val opened = case["production_opens_twin_codec2"] as StrictJson.Obj
            assertEquals(request["path"], opened["path"])
            assertEquals(java.util.Base64.getEncoder().encodeToString(body), opened["body"])
        }
    }

    /** The production module's own codec-2 request (real deflate) opens in the Companion's decoder as well: the two sides read each other's frames. */
    @Test fun productionCompactRequestOpens() {
        val key = TestVectors.hex(TestVectors.string(TestVectors.file("frames.json")["key_hex"]))
        for (case in TestVectors.cases("frames.json")) {
            val frame = TestVectors.hex(case["production_seal_compact"] as String)
            val decoded = BleWire.decode(frame.copyOfRange(4, frame.size), key, "request")
            assertArrayEquals(TestVectors.hex(case["body_hex"] as String), decoded.body)
            val plain = TestVectors.hex(case["production_seal"] as String)
            val whole = BleWire.decode(plain.copyOfRange(4, plain.size), key, "request")
            assertEquals((case["request"] as StrictJson.Obj)["path"], StrictJson.parseObject(whole.json)["path"])
        }
    }

    @Test fun vectorFilesAreWhatTheContractSays() {
        // a truncated or edited copy of a vector file would silently weaken every test above
        val expected = mapOf("allowlist.json" to 119, "results.json" to 34, "chunk-targets.json" to 25)
        for ((file, count) in expected) assertEquals(file, count, TestVectors.cases(file).size)
        val names = TestVectors.cases("allowlist.json").map { it["name"] }
        assertEquals("names are unique", names.size, names.toSet().size)
        for (shape in listOf("route", "status", "health", "vehicle"))
            if (TestVectors.cases("allowlist.json").none { it["expect"] == "accept:$shape" }) fail("no accept vector for $shape")
    }
}
