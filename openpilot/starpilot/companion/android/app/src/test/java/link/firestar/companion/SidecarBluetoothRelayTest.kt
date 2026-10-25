package link.firestar.companion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SidecarBluetoothRelayTest {
    @Test fun unsolicitedMtuUpdateDoesNotAbortServiceDiscovery() {
        val mailbox = GattEventMailbox()
        mailbox.offer(GattEvent("mtu", 0))
        mailbox.offer(GattEvent("services", 0))
        assertArrayEquals(byteArrayOf(), mailbox.await("services", 1) { false })

        val broken = GattEventMailbox()
        broken.offer(GattEvent("mtu", 0))
        broken.offer(GattEvent("disconnect", 8))
        val failure = assertThrows(GattEventFailure::class.java) { broken.await("services", 1) { false } }
        assertEquals("disconnect", failure.observed)
        assertEquals(8, failure.status)
    }

    private fun exchange(port: Int, request: String): String = Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
        socket.soTimeout = 3_000
        socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
        String(socket.getInputStream().readBytes(), Charsets.UTF_8)
    }

    @Test fun forwardsOnlyTheSidecarNavigationAPIWithItsBearer() {
        val seen = AtomicReference<JSONObject>()
        SidecarLoopbackServer(0) { request ->
            seen.set(request)
            JSONObject().put("status", 200).put("body", Base64.getEncoder().encodeToString("{\"destination\":\"\"}".toByteArray()))
        }.use { server ->
            val body = "{\"name\":\"Home\"}"
            val request = "POST /api/navigation HTTP/1.1\r\nHost: 127.0.0.1:${server.boundPort}\r\n" +
                "Authorization: Bearer abcdefghij\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${body.length}\r\n\r\n$body"
            val reply = exchange(server.boundPort, request)
            assertTrue(reply.startsWith("HTTP/1.1 200 OK\r\n"))
            assertTrue(reply.endsWith("{\"destination\":\"\"}"))
            val forwarded = seen.get()
            assertNotNull(forwarded)
            assertEquals("POST", forwarded.getString("method"))
            assertEquals("/api/navigation", forwarded.getString("path"))
            assertEquals("Bearer abcdefghij", forwarded.getJSONObject("headers").getString("authorization"))
            assertEquals(body, String(Base64.getDecoder().decode(forwarded.getString("body"))))
        }
    }

    @Test fun rejectsOtherPathsAndMissingTokenBeforeBluetooth() {
        val calls = AtomicInteger()
        SidecarLoopbackServer(0) { calls.incrementAndGet(); JSONObject().put("status", 200) }.use { server ->
            val other = exchange(server.boundPort,
                "GET /api/software/status HTTP/1.1\r\nHost: 127.0.0.1:${server.boundPort}\r\n\r\n")
            assertTrue(other.startsWith("HTTP/1.1 404 "))
            val missing = exchange(server.boundPort,
                "GET /api/navigation HTTP/1.1\r\nHost: 127.0.0.1:${server.boundPort}\r\n\r\n")
            assertTrue(missing.startsWith("HTTP/1.1 401 "))
            assertEquals(0, calls.get())
        }
    }

    @Test fun routeWorkerAcceptsOnlyRelativeDirectionsTargets() {
        assertTrue(SidecarRouteWorker.validTarget(
            "/directions/v5/mapbox/driving-traffic/139.0,35.0;139.1,35.1?access_token=abcdefghij&steps=true"))
        assertFalse(SidecarRouteWorker.validTarget("http://evil.invalid/directions/v5/mapbox/driving-traffic/a?access_token=x"))
        assertFalse(SidecarRouteWorker.validTarget("/directions/v5/mapbox/driving-traffic/%2e%2e?access_token=x"))
        assertFalse(SidecarRouteWorker.validTarget("/api/software/status?access_token=x"))
    }

    @Test fun routeWorkerResumesCommittedChunksWithoutRefetchingAfterLostGattAck() {
        val route = ByteArray(6200).also { java.util.Random(7).nextBytes(it) }
        val id = "0123456789abcdef0123456789abcdef"
        val target = "/directions/v5/mapbox/driving-traffic/139.0,35.0;139.1,35.1?steps=true"
        var cursor = 0
        var completed = false
        var loseFirstAck = true
        var fetches = 0
        val received = ByteArrayOutputStream()
        val sequences = mutableListOf<Int>()
        val priorities = mutableListOf<Boolean>()
        val link = object : RouteLink {
            override fun routePriority(high: Boolean) { priorities += high }
            override fun request(value: JSONObject, deadlineSeconds: Long): JSONObject {
                val path = value.getString("path")
                if (path == "/_bridge/sidecar/poll") {
                    if (completed) return JSONObject().put("status", 204)
                    val job = JSONObject().put("id", id).put("target", target).put("next_sequence", cursor)
                    return JSONObject().put("status", 200)
                        .put("body", Base64.getEncoder().encodeToString(job.toString().toByteArray()))
                }
                assertTrue(path.startsWith("/_bridge/sidecar/chunk/$id/"))
                val sequence = path.substringAfter("/_bridge/sidecar/chunk/$id/").substringBefore('?').toInt()
                assertEquals(cursor, sequence)
                sequences += sequence
                val chunk = Base64.getDecoder().decode(value.getString("body"))
                assertTrue(chunk.size <= SidecarRouteWorker.CHUNK_BYTES)
                received.write(chunk)
                cursor++
                if (loseFirstAck) {
                    loseFirstAck = false
                    throw IOException("GATT ACK lost after comma committed chunk")
                }
                if (path.contains("last=1")) completed = true
                val ack = JSONObject().put("next", cursor)
                return JSONObject().put("status", 200)
                    .put("body", Base64.getEncoder().encodeToString(ack.toString().toByteArray()))
            }
        }
        SidecarRouteWorker({ link }, autoStart = false).use { worker ->
            worker.routeFetcher = { fetches++; route }
            worker.warning = { /* expected first interruption */ }
            worker.tick()
            assertEquals(1, cursor)
            assertFalse(completed)
            worker.tick()
            assertTrue(completed)
            assertEquals(1, fetches)
            assertEquals((0 until cursor).toList(), sequences)
            assertEquals(listOf(true, false, true, false), priorities)
        }
        val restored = GZIPInputStream(ByteArrayInputStream(received.toByteArray())).readBytes()
        assertArrayEquals(route, restored)
    }

    @Test fun routeWorkerRejectsPartialJobAfterProcessLostOriginalPayload() {
        val id = "0123456789abcdef0123456789abcdef"
        val target = "/directions/v5/mapbox/driving-traffic/139.0,35.0;139.1,35.1?steps=true"
        var failed = false
        val link = object : RouteLink {
            override fun routePriority(high: Boolean) = Unit
            override fun request(value: JSONObject, deadlineSeconds: Long): JSONObject {
                val path = value.getString("path")
                if (path == "/_bridge/sidecar/poll") {
                    val job = JSONObject().put("id", id).put("target", target).put("next_sequence", 1)
                    return JSONObject().put("status", 200)
                        .put("body", Base64.getEncoder().encodeToString(job.toString().toByteArray()))
                }
                assertEquals("/_bridge/sidecar/fail/$id", path)
                failed = true
                return JSONObject().put("status", 200)
            }
        }
        SidecarRouteWorker({ link }, autoStart = false).use { worker ->
            worker.routeFetcher = { throw AssertionError("Must not splice a new route onto an old gzip stream") }
            worker.warning = {}
            worker.tick()
        }
        assertTrue(failed)
    }
}
