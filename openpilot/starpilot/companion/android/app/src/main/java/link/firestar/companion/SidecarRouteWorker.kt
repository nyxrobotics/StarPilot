package link.firestar.companion

import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/** Only authenticated Companion GATT links can implement this request surface. */
interface RouteLink {
    fun request(value: JSONObject, deadlineSeconds: Long = 90): JSONObject
    fun routePriority(high: Boolean)
}

/** Carries one local Sidecar directions reply back to comma over the paired, encrypted GATT session. */
internal class SidecarRouteWorker internal constructor(
    private val transport: () -> RouteLink?,
    autoStart: Boolean,
) : Closeable {
    constructor(transport: () -> RouteLink?) : this(transport, true)
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "SidecarBLE-Route").apply { isDaemon = true }
    }
    @Volatile private var running = true
    /** Keep the exact compressed bytes until comma confirms completion or expires the job. */
    private data class Transfer(val id: String, val target: String, val body: ByteArray)
    private var pending: Transfer? = null

    internal var routeFetcher: (String) -> ByteArray = ::fetch
    internal var warning: (String) -> Unit = { Log.w("StarPilotSidecarBLE", it) }

    init { if (autoStart) worker.scheduleWithFixedDelay({ tick() }, 2, 10, TimeUnit.SECONDS) }

    /** Promptly collect a new route after Sidecar selects a destination, without busy idle polling. */
    fun routeSelected() {
        if (!running) return
        try {
            worker.schedule({ tick() }, 2, TimeUnit.SECONDS)
            worker.schedule({ tick() }, 5, TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) { /* service stopped */ }
    }

    internal fun tick() {
        if (!running) return
        val link = try { transport() } catch (_: Exception) { null } ?: return
        try {
            val answer = link.request(JSONObject().put("method", "GET").put("path", POLL), 10)
            if (answer.optInt("status") == 204) { pending = null; return }
            if (answer.optInt("status") != 200) return
            val encoded = answer.optString("body", "")
            if (encoded.length > 16_384) return
            val job = JSONObject(String(Base64.getDecoder().decode(encoded), Charsets.UTF_8))
            val id = job.getString("id")
            if (!ID.matches(id)) return
            val target = job.getString("target")
            if (!validTarget(target)) throw IOException("Invalid Sidecar route target")
            // comma reports the first uncommitted chunk. In particular, if the
            // previous GATT ACK was lost, repeating that chunk is safe because
            // the broker's sequence check returns its already-advanced cursor.
            val nextSequence = job.getInt("next_sequence")
            var transfer = pending?.takeIf { it.id == id && it.target == target }
            if (transfer == null) {
                pending = null
                if (nextSequence != 0) {
                    // A process restart loses the original gzip stream. A new
                    // download could differ and must never be spliced onto it.
                    warning("Route transfer cannot resume without its original payload")
                    try { fail(link, id) } catch (_: Exception) { /* broker deadline still applies */ }
                    return
                }
                transfer = try { Transfer(id, target, gzip(routeFetcher(target))) } catch (error: Exception) {
                    warning("Route fetch failed (${error.javaClass.simpleName})")
                    try { fail(link, id) } catch (_: Exception) { /* broker deadline still applies */ }
                    return
                }
                pending = transfer
            }
            try {
                link.routePriority(true)
                try { send(link, transfer, nextSequence) } finally { link.routePriority(false) }
                pending = null
            } catch (error: IOException) {
                // A request may have reached comma even when its GATT ACK was
                // lost. Retain the same payload and ask the broker's cursor on
                // the next connection before sending anything else.
                warning("Route transfer interrupted (${error.javaClass.simpleName})")
            } catch (error: Exception) {
                warning("Route transfer rejected (${error.javaClass.simpleName})")
                pending = null
                try { fail(link, id) } catch (_: Exception) { /* broker deadline still applies */ }
            }
        } catch (error: Exception) {
            warning("Route poll failed (${error.javaClass.simpleName})")
        }
    }

    private fun send(link: RouteLink, transfer: Transfer, nextSequence: Int) {
        val body = transfer.body
        val chunks = (body.size + CHUNK_BYTES - 1) / CHUNK_BYTES
        require(nextSequence in 0 until chunks) { "Invalid Sidecar route cursor" }
        var sequence = nextSequence
        while (sequence < chunks) {
            if (!running) throw IOException("Sidecar relay stopped")
            val offset = sequence * CHUNK_BYTES
            val end = (offset + CHUNK_BYTES).coerceAtMost(body.size)
            val last = end == body.size
            val request = JSONObject().put("method", "POST")
                .put("path", "/_bridge/sidecar/chunk/${transfer.id}/$sequence?last=${if (last) 1 else 0}&status=200&encoding=gzip")
                .put("body", Base64.getEncoder().encodeToString(body.copyOfRange(offset, end)))
            val answer = link.request(request, 30)
            if (answer.optInt("status") != 200) error("Sidecar route chunk rejected")
            val ack = JSONObject(String(Base64.getDecoder().decode(answer.optString("body", "")), Charsets.UTF_8))
            if (ack.optInt("next", -1) != sequence + 1) error("Sidecar route chunk ACK mismatch")
            sequence++
        }
    }

    private fun fail(link: RouteLink, id: String) {
        val response = link.request(JSONObject().put("method", "POST").put("path", "/_bridge/sidecar/fail/$id"), 10)
        if (response.optInt("status") !in setOf(200, 404)) throw IOException("Sidecar route failure was not acknowledged")
    }

    private fun fetch(target: String): ByteArray {
        val connection = URL("http://127.0.0.1:8765$target").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 3_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("Accept-Encoding", "identity")
        try {
            if (connection.responseCode != 200 || connection.contentLengthLong > MAX_BODY)
                throw IOException("Sidecar directions unavailable")
            val result = ByteArrayOutputStream()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            connection.inputStream.use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    if (!running || System.nanoTime() > deadline) throw IOException("Sidecar directions timed out")
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (result.size() + count > MAX_BODY) throw IOException("Sidecar directions too large")
                    result.write(buffer, 0, count)
                }
            }
            return result.toByteArray()
        } finally { connection.disconnect() }
    }

    private fun gzip(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(data) }
        if (output.size() > MAX_BODY) throw IOException("Compressed Sidecar route too large")
        return output.toByteArray()
    }

    override fun close() {
        running = false
        worker.shutdownNow()
    }

    companion object {
        private const val POLL = "/_bridge/sidecar/poll"
        internal const val CHUNK_BYTES = 2048
        private const val MAX_BODY = 8 * 1024 * 1024
        private val ID = Regex("[0-9a-f]{32}")

        internal fun validTarget(value: String): Boolean {
            if (value.length !in 1..8192 || value.any { it.code < 32 || it.code == 127 || it == '\\' }) return false
            val parsed = try { URI(value) } catch (_: Exception) { return false }
            val path = parsed.rawPath ?: return false
            return parsed.scheme == null && parsed.rawAuthority == null && parsed.rawFragment == null &&
                path.startsWith("/directions/v5/mapbox/driving-traffic/") &&
                path.length <= 300 && path.drop("/directions/v5/mapbox/driving-traffic/".length).isNotEmpty() &&
                '%' !in path && "//" !in path && !parsed.rawQuery.isNullOrEmpty()
        }
    }
}
