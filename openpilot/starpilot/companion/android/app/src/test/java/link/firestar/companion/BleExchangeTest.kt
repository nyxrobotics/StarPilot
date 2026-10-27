package link.firestar.companion

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Base64

/** One request and answer over the Galaxy BLE v1 framing against a fake comma at the GATT level: which packets go without a response, the acknowledgement, the checks of the answer. */
class BleExchangeTest {
    private val key = TestVectors.KEY
    private val session = "00112233445566778899aabbccddeeff"

    /** comma's side: reassembles the request (the production framing), opens it, answers with [reply]. */
    private class FakeGatt(val key: ByteArray, val session: String, val emptyReads: Int = 0, val reply: (JSONObject, ByteArray?) -> JSONObject = { _, _ -> JSONObject().put("status", 200) },
                           val tamper: (JSONObject) -> JSONObject = { it }) : GattIo {
        val writes = ArrayList<Pair<ByteArray, Boolean>>()
        var request: JSONObject? = null
        var body: ByteArray? = null
        var pauses = 0
        private val assembler = BleWire.Assembler()
        private var answer: ArrayDeque<ByteArray>? = null
        private var empty = emptyReads
        var acked: ByteArray? = null

        override fun write(packet: ByteArray, noResponse: Boolean) {
            writes += packet to noResponse
            if (packet[0].toInt() == 2) { acked = packet; return }
            val frame = assembler.add(packet) ?: return
            val opened = BleWire.decode(frame, key, "request")
            val meta = JSONObject(String(opened.json, Charsets.UTF_8))
            request = meta; body = opened.body
            val response = tamper(reply(meta, opened.body).put("id", meta.getString("id")).put("session", meta.getString("session")).put("counter", meta.getLong("counter")))
            val sealed = BleWire.seal(response.toString().toByteArray(), key, "response")
            answer = ArrayDeque(BleWire.packets(sealed, 244))
        }
        override fun read(): ByteArray {
            if (empty > 0) { empty--; return byteArrayOf(0) }
            return answer?.removeFirstOrNull() ?: byteArrayOf(0)
        }
        override fun pause(millis: Long) { pauses++ }
    }

    private fun run(io: GattIo, meta: JSONObject, body: ByteArray?, writeNoResponse: Boolean, deadline: Long = 20, nano: () -> Long = System::nanoTime) =
        BleExchange.run(io, key, session, 7, "id-1", meta, body, deadline, 244, writeNoResponse, nano)

    private fun meta(path: String = "/api/navigation") = JSONObject().put("method", "GET").put("path", path)

    @Test fun aPlainRequestIsCodecZeroAndEveryPacketWaitsForItsResponse() {
        val gatt = FakeGatt(key, session)
        val answer = run(gatt, meta().put("body", Base64.getEncoder().encodeToString(ByteArray(2000) { it.toByte() })), null, writeNoResponse = true)
        assertEquals(200, answer.getInt("status"))
        val sent = gatt.writes.filter { it.first[0].toInt() == 1 }
        assertTrue("several packets", sent.size > 5)
        assertTrue("write without response is for raw-body requests only", sent.none { it.second })
        assertEquals("GET", gatt.request!!.getString("method"))
        assertEquals(7L, gatt.request!!.getLong("counter"))
        assertEquals(2, gatt.request!!.getInt("responseCodec"))
        assertEquals("read-stream", gatt.request!!.getString("responseFlow"))
    }

    @Test fun aRawBodyGoesAsCodecTwoWithAllButTheLastPacketWithoutResponse() {
        val body = ByteArray(20_000) { (it * 31 + 7).toByte() }
        val gatt = FakeGatt(key, session)
        run(gatt, JSONObject().put("method", "POST").put("path", "/_bridge/sidecar/chunk/${"a".repeat(32)}/0?last=0&status=200&encoding=gzip"), body, writeNoResponse = true)
        val sent = gatt.writes.filter { it.first[0].toInt() == 1 }
        assertTrue(sent.size > 80)
        assertTrue("every data packet but the last without response", sent.dropLast(1).all { it.second })
        assertFalse("the last data packet waits for comma's response", sent.last().second)
        assertArrayEquals("the raw body arrived byte for byte", body, gatt.body)
        assertFalse("no base64 body member in the request", gatt.request!!.has("body"))
        assertFalse("the acknowledgement is a write with response", gatt.writes.last().second)
    }

    @Test fun withoutTheLeverEveryPacketOfARawBodyWaitsForItsResponse() {
        val gatt = FakeGatt(key, session)
        run(gatt, meta("/x"), ByteArray(3000), writeNoResponse = false)
        assertTrue(gatt.writes.none { it.second })
    }

    @Test fun aFrameOfOnePacketIsAlwaysWrittenWithResponse() {
        val gatt = FakeGatt(key, session)
        run(gatt, meta("/x"), ByteArray(10), writeNoResponse = true)
        assertEquals(2, gatt.writes.size) // the packet and the acknowledgement
        assertTrue(gatt.writes.none { it.second })
    }

    @Test fun theAnswerIsReadByPollingAndAcknowledgedWithItsLastSequence() {
        val gatt = FakeGatt(key, session, emptyReads = 4, reply = { _, _ -> JSONObject().put("status", 200).put("body", "e30=") })
        val answer = run(gatt, meta(), null, writeNoResponse = false)
        assertEquals(4, gatt.pauses)
        assertEquals("e30=", answer.getString("body"))
        val ack = gatt.acked!!
        assertEquals(5, ack.size)
        assertEquals(2, ack[0].toInt())
        assertEquals("the sequence number of the last answer packet (0 for a one-packet answer)", 0, ByteBuffer.wrap(ack, 1, 4).int)
    }

    @Test fun aBodyOfTheAnswerComesBackAsBase64() {
        val payload = ByteArray(3000) { (it % 251).toByte() }
        val gatt = object : GattIo by FakeGatt(key, session) {
            private val inner = FakeGatt(key, session)
            private var packets: ArrayDeque<ByteArray>? = null
            private var request: JSONObject? = null
            private val assembler = BleWire.Assembler()
            override fun write(packet: ByteArray, noResponse: Boolean) {
                if (packet[0].toInt() == 2) return
                val frame = assembler.add(packet) ?: return
                request = JSONObject(String(BleWire.decode(frame, key, "request").json, Charsets.UTF_8))
                val meta = JSONObject().put("status", 200).put("id", request!!.getString("id")).put("session", session).put("counter", request!!.getLong("counter"))
                packets = ArrayDeque(BleWire.packets(BleWire.sealRaw(meta.toString().toByteArray(), payload, key, "response"), 244))
            }
            override fun read(): ByteArray = packets?.removeFirstOrNull() ?: byteArrayOf(0)
            override fun pause(millis: Long) = Unit
        }
        val answer = run(gatt, meta(), null, writeNoResponse = false)
        assertArrayEquals(payload, Base64.getDecoder().decode(answer.getString("body")))
    }

    @Test fun anAnswerToAnotherRequestSessionOrCounterIsRefused() {
        for (change in listOf<(JSONObject) -> JSONObject>({ it.put("id", "other") }, { it.put("session", "ff".repeat(16)) }, { it.put("counter", 8) })) {
            val gatt = FakeGatt(key, session, tamper = change)
            assertThrows(IllegalArgumentException::class.java) { run(gatt, meta(), null, writeNoResponse = false) }
        }
    }

    @Test fun noAnswerWithinTheDeadlineIsAResponseTimeout() {
        var now = 0L
        val gatt = object : GattIo {
            override fun write(packet: ByteArray, noResponse: Boolean) = Unit
            override fun read() = byteArrayOf(0)
            override fun pause(millis: Long) { now += millis * 1_000_000 }
        }
        assertThrows(ResponseTimeout::class.java) { run(gatt, meta(), null, writeNoResponse = false, deadline = 3) { now } }
        assertTrue("the deadline is seconds of the clock it is given", now in 3_000_000_000L..3_100_000_000L)
    }

    @Test fun aDamagedAnswerIsRefused() {
        val gatt = FakeGatt(key, session)
        val damaged = object : GattIo by gatt {
            override fun read(): ByteArray = gatt.read().also { if (it.size > 20) it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
        }
        assertThrows(Exception::class.java) { run(damaged, meta(), null, writeNoResponse = false) }
    }
}
