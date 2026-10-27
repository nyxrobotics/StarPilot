package link.firestar.companion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Companion's own view of the link (docs/dev/bluetooth-map-contract.md 2.5): the burst of the data packets of an exchange read by [BleExchange] for the [UplinkMeter]; the flags of the health answer. */
class MapLinkTest {
    private val key = TestVectors.KEY
    private val session = "00112233445566778899aabbccddeeff"

    /** comma's side at the GATT level on a virtual clock: a write takes [writeNoResponseMs] or [writeMs]; the request is opened and answered with a 200. */
    private class Gatt(val key: ByteArray, val clock: LongArray, val writeNoResponseMs: Double, val writeMs: Double) : GattIo {
        private val assembler = BleWire.Assembler()
        private var answer: ArrayDeque<ByteArray>? = null
        val flags = ArrayList<Boolean>()
        override fun write(packet: ByteArray, noResponse: Boolean) {
            flags += noResponse
            clock[0] += ((if (noResponse) writeNoResponseMs else writeMs) * 1e6).toLong()
            if (packet[0].toInt() == 2) return
            val frame = assembler.add(packet) ?: return
            val meta = JSONObject(String(BleWire.decode(frame, key, "request").json, Charsets.UTF_8))
            val response = JSONObject().put("status", 200).put("id", meta.getString("id")).put("session", meta.getString("session")).put("counter", meta.getLong("counter"))
            answer = ArrayDeque(BleWire.packets(BleWire.seal(response.toString().toByteArray(), key, "response"), 244))
        }
        override fun read(): ByteArray = answer?.removeFirstOrNull() ?: byteArrayOf(0)
        override fun pause(millis: Long) = Unit
    }

    private fun exchange(bodySize: Int, wwr: Boolean, writeNoResponseMs: Double, onBurst: ((Int, Double) -> Unit)?, body: ByteArray? = ByteArray(bodySize) { (it * 7).toByte() }): Gatt {
        val clock = longArrayOf(1_000_000_000L)
        val gatt = Gatt(key, clock, writeNoResponseMs, 60.0)
        BleExchange.run(gatt, key, session, 3, "id-3", JSONObject().put("method", "POST").put("path", "/_bridge/sidecar/chunk/x"), body, 20, 244, wwr, { clock[0] }, onBurst)
        return gatt
    }

    @Test fun theBurstIsTheBodyOfThePacketsSentWithoutAResponseOverTheTimeTheyTook() {
        val samples = ArrayList<Pair<Int, Double>>()
        // 20,000 bytes at 5.5 ms a packet of 244 bytes (239 of data): the burst of all packets but the last
        val gatt = exchange(20_000, true, 5.5, { b, s -> samples += b to s })
        val packets = gatt.flags.size - 1 // the last write is the acknowledgement of the answer; the data packets are the ones before it
        val data = gatt.flags.dropLast(1)
        assertTrue("all data packets but the last without a response", data.dropLast(1).all { it } && !data.last())
        assertEquals(1, samples.size)
        val (body, seconds) = samples.single()
        // the burst is the data packets but the last one: its time is (n - 1) packets of 5.5 ms, and its body is the share of the body they carried
        assertEquals((data.size - 1) * 5.5 / 1000.0, seconds, 1e-6)
        assertTrue("the body of the burst is nearly the whole body ($body of 20,000)", body in 19_000..20_000)
        val rate = body / seconds / 1000.0
        assertTrue("the rate is the packets' rate, about 43 KB/s ($rate)", rate in 40.0..45.0)
        assertTrue(packets > 80)
    }

    @Test fun noBurstIsReportedWithoutWriteWithoutResponseOrForARequestWithoutARawBody() {
        val samples = ArrayList<Pair<Int, Double>>()
        exchange(20_000, false, 5.5, { b, s -> samples += b to s })
        exchange(0, true, 5.5, { b, s -> samples += b to s }, body = null)
        assertTrue("write with a response for every packet is not a burst, and a plain request has no raw body", samples.isEmpty())
    }

    @Test fun theMeterTakesTheBurstsOfTheChunksAndReadsTheMedian() {
        val meter = UplinkMeter()
        for (run in 0 until 3) exchange(30_000, true, 5.5 + run, meter::observe)
        val value = meter.upKbS!!
        assertTrue("the median of three bursts of 5.5, 6.5 and 7.5 ms a packet: about 37 KB/s ($value)", value in 30.0..40.0)
        val small = UplinkMeter(); exchange(3_000, true, 5.5, small::observe)
        assertNull("a chunk below 4,096 bytes is not a sample", small.upKbS)
    }
}
