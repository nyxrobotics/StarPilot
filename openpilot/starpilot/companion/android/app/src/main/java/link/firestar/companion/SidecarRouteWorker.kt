package link.firestar.companion

import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.MalformedURLException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/** Only authenticated Companion GATT links can implement this request surface. */
interface RouteLink {
    fun request(value: JSONObject, deadlineSeconds: Long = 90): JSONObject
    fun routePriority(high: Boolean)
    /** The same request in another class of the lane (the lane is in BleLane.kt); a link without a lane treats every class alike. */
    fun request(value: JSONObject, deadlineSeconds: Long, cls: LaneClass): JSONObject = request(value, deadlineSeconds)
}

/** A complete HTTP response was received, but it cannot be used as a route. */
internal class RouteFetchRejected(val reason: String, val status: Int? = null) : IllegalStateException()
/** No complete HTTP response was received, so a fresh local GET is safe. */
internal class RouteFetchIo(val stage: String, val origin: String, cause: IOException) : IOException(cause)

/** Carries one local Sidecar directions reply back to comma over the paired, encrypted GATT session. */
internal class SidecarRouteWorker internal constructor(
    private val transport: () -> RouteLink?,
    autoStart: Boolean,
    /** The lane of a connection whose bridge speaks `poll2` and the v2 results (SidecarJobs); null for an old bridge, which keeps the legacy poll and chunk calls. */
    private val jobsLane: () -> BleLane? = { (transport() as? BleTransport)?.takeIf { it.jobsV2 }?.lane },
    private val preferXz: () -> Boolean = { (transport() as? BleTransport)?.preferXz == true },
    private val fetcher: LocalFetcher = LoopbackFetcher(),
) : Closeable {
    constructor(transport: () -> RouteLink?) : this(transport, true)
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "SidecarBLE-Route").apply { isDaemon = true }
    }
    @Volatile private var running = true
    /** Keep the exact compressed bytes until comma confirms completion or expires the job. */
    private data class Transfer(val id: String, val target: String, val body: ByteArray)
    private var pending: Transfer? = null
    private var jobs: SidecarJobs? = null

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

    /** The job poll of a bridge that speaks `poll2` and the v2 results; made on the first tick that finds one. */
    @Synchronized private fun jobsEngine(): SidecarJobs = jobs ?: SidecarJobs(
        laneSource = jobsLane,
        fetcher = fetcher,
        preferXz = preferXz,
        warn = { warning(it) },
    ).also { jobs = it }

    /** The push channel says a job is waiting (the wake-up frame), or a connection was just made: poll now instead of at the next tick. */
    fun wake() {
        if (!running) return
        try { worker.execute { tick() } } catch (_: java.util.concurrent.RejectedExecutionException) { /* service stopped */ }
    }

    internal fun tick() {
        if (!running) return
        val link = try { transport() } catch (_: Exception) { null } ?: return
        if (jobsLane() != null) {
            try { jobsEngine().pollNow() } catch (error: Exception) { warning("Route poll failed (${error.javaClass.simpleName})") }
            return
        }
        try {
            val answer = link.request(JSONObject().put("method", "GET").put("path", POLL), 10, LaneClass.SMALL)
            if (answer.optInt("status") == 204) { pending = null; return }
            if (answer.optInt("status") != 200) return
            val encoded = answer.optString("body", "")
            if (encoded.length > 16_384) return
            val job = JSONObject(String(Base64.getDecoder().decode(encoded), Charsets.UTF_8))
            val id = job.getString("id")
            if (!ID.matches(id)) return
            val target = job.getString("target")
            if (!validTarget(target)) {
                warning("Route fetch rejected (reason=invalid-target)")
                try { fail(link, id) } catch (_: Exception) { /* broker deadline still applies */ }
                return
            }
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
                val route = try { routeFetcher(target) } catch (error: IOException) {
                    // No compressed bytes or BLE chunks exist yet. Keep the
                    // broker job open and re-fetch on its next poll.
                    val origin = (error as? RouteFetchIo)?.origin ?: error.javaClass.simpleName
                    val stage = (error as? RouteFetchIo)?.stage ?: "fetch"
                    warning("Route fetch deferred ($origin, stage=$stage)")
                    return
                } catch (error: Exception) {
                    if (error is RouteFetchRejected)
                        warning("Route fetch rejected (reason=${error.reason}, status=${error.status ?: "none"})")
                    else warning("Route fetch failed (${error.javaClass.simpleName})")
                    try { fail(link, id) } catch (_: Exception) { /* broker deadline still applies */ }
                    return
                }
                transfer = try { Transfer(id, target, gzip(route)) } catch (error: Exception) {
                    warning("Route compression failed (${error.javaClass.simpleName})")
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
            val answer = link.request(request, 30, LaneClass.BULK)
            if (answer.optInt("status") != 200) error("Sidecar route chunk rejected")
            val ack = JSONObject(String(Base64.getDecoder().decode(answer.optString("body", "")), Charsets.UTF_8))
            if (ack.optInt("next", -1) != sequence + 1) error("Sidecar route chunk ACK mismatch")
            sequence++
        }
    }

    private fun fail(link: RouteLink, id: String) {
        val response = link.request(JSONObject().put("method", "POST").put("path", "/_bridge/sidecar/fail/$id"), 10, LaneClass.SMALL)
        if (response.optInt("status") !in setOf(200, 404)) throw IOException("Sidecar route failure was not acknowledged")
    }

    internal fun fetch(target: String, port: Int = 8765): ByteArray {
        var retriedPreResponseSocket = false
        while (true) {
            try { return fetchOnce(target, port) } catch (error: RouteFetchIo) {
                if (!retriedPreResponseSocket && error.stage == "response" && error.cause is SocketException) {
                    // Sidecar closes idle keep-alive sockets after five seconds.
                    // HttpURLConnection may select one just as it goes stale.
                    retriedPreResponseSocket = true
                    warning("Route fetch retrying fresh connection (${error.origin}, stage=response)")
                    continue
                }
                throw error
            }
        }
    }

    private fun fetchOnce(target: String, port: Int): ByteArray {
        val endpoint = try { URL("http://127.0.0.1:$port$target") }
            catch (_: MalformedURLException) { throw RouteFetchRejected("invalid-target") }
        val connection = endpoint.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        var stage = "setup"
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 3_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Connection", "close")
            stage = "response"
            val status = connection.responseCode
            if (status != 200) throw RouteFetchRejected("http-status", status)
            val declared = connection.contentLengthLong
            if (declared > MAX_BODY) throw RouteFetchRejected("oversize")
            val result = ByteArrayOutputStream()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            stage = "body"
            connection.inputStream.use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    if (!running || System.nanoTime() > deadline) throw SocketTimeoutException("Sidecar directions timed out")
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (result.size() + count > MAX_BODY) throw RouteFetchRejected("oversize")
                    result.write(buffer, 0, count)
                }
            }
            if (result.size() == 0 || (declared >= 0 && result.size().toLong() != declared))
                throw RouteFetchRejected("invalid-body")
            return result.toByteArray()
        } catch (error: IOException) {
            throw RouteFetchIo(stage, error.javaClass.simpleName, error)
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
        jobs?.close()
        worker.shutdownNow()
    }

    companion object {
        private const val POLL = "/_bridge/sidecar/poll"
        internal const val CHUNK_BYTES = 2048
        private const val MAX_BODY = 8 * 1024 * 1024
        private val ID = Regex("[0-9a-f]{32}")

        /** The shared allow-list's route shape (docs/dev/bluetooth-link.md section 5.1): a coordinate list and a query, printable ASCII only. */
        internal fun validTarget(value: String): Boolean = Allowlist.check("GET", value).let { it.ok && it.shape == Shape.ROUTE }
    }
}
