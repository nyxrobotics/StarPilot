package link.firestar.companion

import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** The Sidecar app's narrow HTTP entrance to comma's authenticated BLE bridge. */
internal class SidecarLoopbackServer(
    port: Int = 8082,
    private val relay: (JSONObject) -> JSONObject,
) : Closeable {
    private val listener = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8)
    }
    val boundPort: Int get() = listener.localPort
    private val clients = ThreadPoolExecutor(2, 2, 0L, TimeUnit.SECONDS, ArrayBlockingQueue(4)) { task ->
        Thread(task, "SidecarBLE-HTTP").apply { isDaemon = true }
    }
    private val acceptor = Thread({ accept() }, "SidecarBLE-Accept").apply { isDaemon = true; start() }

    private data class HttpRequest(val method: String, val authorization: String, val contentType: String?, val body: ByteArray)
    private data class HttpReply(val status: Int, val body: ByteArray) {
        companion object {
            fun error(status: Int, message: String) = HttpReply(status, JSONObject().put("message", message).toString().toByteArray())
        }
    }

    private fun accept() {
        while (!listener.isClosed) {
            val socket = try { listener.accept() } catch (_: SocketException) { return } catch (_: IOException) { return }
            try { clients.execute { socket.use { handle(it) } } } catch (_: RuntimeException) { socket.close() }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 12_000
        var stage = "parse"
        val response = try {
            val request = parse(BufferedInputStream(socket.getInputStream()))
            val headers = JSONObject().put("authorization", request.authorization)
            request.contentType?.let { headers.put("content-type", it) }
            val payload = JSONObject().put("method", request.method).put("path", "/api/navigation")
                .put("headers", headers).put("body", Base64.getEncoder().encodeToString(request.body))
            stage = "ble-request"
            val answer = relay(payload)
            stage = "ble-reply"
            val status = answer.getInt("status")
            if (status !in 200..599) throw IOException("Invalid Bluetooth reply")
            val body = Base64.getDecoder().decode(answer.optString("body", ""))
            if (body.size > MAX_REPLY) throw IOException("Bluetooth reply too large")
            HttpReply(status, body)
        } catch (bad: BadRequest) {
            HttpReply.error(bad.status, bad.message ?: "Invalid request")
        } catch (error: Exception) {
            // Never log the request, response, exception message or stack trace: the bearer is inside the request.
            Log.w("StarPilotSidecarBLE", "Loopback $stage failed (${error.javaClass.simpleName})")
            HttpReply.error(502, "Bluetooth connection unavailable")
        }
        try {
            val reason = when (response.status) {
                200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
                404 -> "Not Found"; 413 -> "Payload Too Large"; 415 -> "Unsupported Media Type"
                503 -> "Service Unavailable"; else -> "Bluetooth Reply"
            }
            val header = "HTTP/1.1 ${response.status} $reason\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${response.body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(response.body); flush() }
        } catch (_: IOException) { /* The Sidecar client may have timed out while BLE was busy. */ }
    }

    private class BadRequest(val status: Int, message: String) : IOException(message)

    private fun parse(input: BufferedInputStream): HttpRequest {
        val start = line(input, 4096).split(' ')
        if (start.size != 3 || start[1] != "/api/navigation" || start[2] != "HTTP/1.1")
            throw BadRequest(404, "Only the Sidecar navigation endpoint is available")
        val method = start[0]
        if (method !in setOf("GET", "POST", "DELETE")) throw BadRequest(400, "Unsupported method")
        val fields = mutableMapOf<String, String>()
        var total = 0
        while (true) {
            val entry = line(input, 4096)
            total += entry.length + 2
            if (total > 16_384) throw BadRequest(400, "Request headers too large")
            if (entry.isEmpty()) break
            val split = entry.indexOf(':')
            if (split < 1) throw BadRequest(400, "Invalid request header")
            val name = entry.substring(0, split).lowercase()
            if (!name.matches(Regex("[a-z0-9-]+")) || fields.containsKey(name))
                throw BadRequest(400, "Invalid request header")
            fields[name] = entry.substring(split + 1).trim()
        }
        if (fields["host"] != "127.0.0.1:$boundPort" || fields.containsKey("transfer-encoding"))
            throw BadRequest(400, "Invalid Sidecar request")
        val authorization = fields["authorization"] ?: throw BadRequest(401, "Sidecar token required")
        if (!authorization.startsWith("Bearer ") || authorization.length !in 17..135 ||
            authorization.drop(7).any { it.code !in 33..126 }) throw BadRequest(401, "Invalid Sidecar token")
        val length = fields["content-length"]?.let { it.toIntOrNull() ?: throw BadRequest(400, "Invalid request size") }
            ?: if (method == "POST") -1 else 0
        if (length < 0 || length > MAX_BODY) throw BadRequest(413, "Invalid Sidecar request size")
        if (method != "POST" && length != 0) throw BadRequest(400, "Unexpected request body")
        val contentType = fields["content-type"]
        if (method == "POST" && (contentType?.lowercase() != "application/json" || length == 0))
            throw BadRequest(415, "JSON required")
        val body = ByteArray(length)
        var offset = 0
        while (offset < body.size) {
            val read = input.read(body, offset, body.size - offset)
            if (read < 0) throw BadRequest(400, "Incomplete request body")
            offset += read
        }
        return HttpRequest(method, authorization, contentType?.lowercase(), body)
    }

    private fun line(input: BufferedInputStream, limit: Int): String {
        val data = ArrayList<Byte>()
        while (data.size <= limit) {
            val next = input.read()
            if (next < 0) throw BadRequest(400, "Incomplete request")
            if (next == 10) {
                if (data.isEmpty() || data.last() != 13.toByte()) throw BadRequest(400, "Invalid line ending")
                data.removeAt(data.lastIndex)
                return String(data.toByteArray(), Charsets.US_ASCII)
            }
            if (next > 127 || next == 0) throw BadRequest(400, "Invalid request bytes")
            data.add(next.toByte())
        }
        throw BadRequest(400, "Request line too large")
    }

    override fun close() {
        listener.close()
        clients.shutdownNow()
        acceptor.interrupt()
    }

    companion object {
        private const val MAX_BODY = 2048
        private const val MAX_REPLY = 128 * 1024
    }
}
