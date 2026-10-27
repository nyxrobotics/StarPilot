package link.firestar.companion

import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.TimeUnit

/** The GATT calls of one exchange, apart from Android's classes, so that the exchange can be tested on the JVM. */
internal interface GattIo {
    /** One data packet to RX; [noResponse] sends it as write without response. Returns when the stack has completed the write. */
    fun write(packet: ByteArray, noResponse: Boolean)
    /** One read of TX: a data packet, or a lone `0x00` when comma has nothing ready. */
    fun read(): ByteArray
    fun pause(millis: Long)
}

/** comma did not answer within the request's deadline. The link is closed after it; the outcome of a change must be checked before it is repeated. */
internal class ResponseTimeout : IOException("Bluetooth response timed out. Check the result before retrying a change.")

/**
 * One request and its answer over the Galaxy BLE v1 framing (docs/dev/bluetooth-link.md sections 5.2 and 6): seal, cut into packets, write, read the answer by
 * polling TX, check that it is the answer to this request (id, session, counter), acknowledge it. A request with a raw body is sealed as codec 2 (no base64) and,
 * when [writeNoResponse] is set, all its data packets but the last go without a response each; the last packet always waits for comma's response, so its answer proves
 * the earlier ones arrived in order (a lost or reordered packet fails the frame at comma, and this request at its deadline).
 */
internal object BleExchange {
    fun run(io: GattIo, key: ByteArray, session: String, counter: Long, id: String, meta: JSONObject, body: ByteArray?, deadlineSeconds: Long,
            packetSize: Int, writeNoResponse: Boolean, nanoTime: () -> Long = System::nanoTime): JSONObject {
        val request = JSONObject(meta.toString()).put("id", id).put("session", session).put("counter", counter).put("responseCodec", 2).put("responseFlow", "read-stream")
        val frame = if (body == null) BleWire.seal(request.toString().toByteArray(), key) else BleWire.sealRaw(request.toString().toByteArray(), body, key)
        val packets = BleWire.packets(frame, packetSize)
        val quick = body != null && writeNoResponse
        for ((index, packet) in packets.withIndex()) io.write(packet, quick && index < packets.size - 1)
        val assembler = BleWire.Assembler()
        val deadline = nanoTime() + TimeUnit.SECONDS.toNanos(deadlineSeconds)
        while (nanoTime() < deadline) {
            val packet = io.read()
            if (packet.contentEquals(byteArrayOf(0))) { io.pause(30); continue }
            val result = assembler.add(packet)
            if (result != null) {
                val decoded = BleWire.decode(result, key)
                val response = JSONObject(String(decoded.json, Charsets.UTF_8))
                decoded.body?.let { require(!response.has("body")); response.put("body", Base64.getEncoder().encodeToString(it)) }
                require(response.getString("id") == id && response.getString("session") == session && response.getLong("counter") == counter)
                io.write(ByteBuffer.allocate(5).put(2).put(packet, 1, 4).array(), false)
                return response
            }
        }
        throw ResponseTimeout()
    }
}
