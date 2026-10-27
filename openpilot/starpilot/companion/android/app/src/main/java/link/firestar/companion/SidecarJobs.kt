package link.firestar.companion

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.Proxy
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * What comma's bridge announces in `GET /_bridge/health` (docs/dev/bluetooth-link.md section 5.3): `protocol` 2 and the flags `poll2`, `results2`, `push`.
 * A bridge that says nothing, or something this reader does not understand, is a protocol 1 bridge: the old poll and chunk calls (the route worker's legacy path)
 * keep working with it and nothing new is used (a new Companion on an old bridge, and the old Companion on a new bridge, both work).
 */
internal class BridgeFeatures(val protocol: Int, val poll2: Boolean, val results2: Boolean, val push: Boolean, val xz: Boolean) {
    /** Jobs by `poll2` and results with a status, an encoding and a digest. */
    val jobsV2: Boolean get() = protocol >= 2 && poll2 && results2

    companion object {
        val LEGACY = BridgeFeatures(1, poll2 = false, results2 = false, push = false, xz = false)

        fun parse(body: ByteArray): BridgeFeatures {
            val value = try { StrictJson.parseObject(body, 4096) } catch (_: StrictJson.Invalid) { return LEGACY }
            val protocol = (value["protocol"] as? StrictJson.Num)?.toLongOrNull()?.takeIf { it in 1..1000 }?.toInt() ?: return LEGACY
            fun flag(name: String) = value[name] == true
            // `xz` is announced only to switch it off: the bridge's interpreter has `lzma` (measured), and a refused `xz` result is a failed job, never a wrong route.
            return BridgeFeatures(protocol, flag("poll2"), flag("results2"), flag("push"), value["xz"] != false)
        }
    }
}

/** One job of a `poll2` answer. */
internal class JobInfo(val id: String, val kind: Shape, val target: String, val nextSequence: Int, val claimed: Boolean, val leaseMs: Long)
internal class JobList(val jobs: List<JobInfo>, val nextPollMs: Long)

/** The X10 route API's answer to one job. */
internal class LocalAnswer(val status: Int, val body: ByteArray)
internal fun interface LocalFetcher { fun fetch(shape: Shape, target: String): LocalAnswer }

/**
 * Carries comma's jobs to the X10 route API and the answers back (docs/dev/bluetooth-link.md sections 5.1 to 5.4, 7.1):
 *
 *  * `GET /_bridge/sidecar/poll2` lists the open jobs, small ones first; a job held by another phone is not touched;
 *  * each job is one of the three allow-listed GET shapes (route, status, health), fetched from `127.0.0.1:8765` only, never following a redirect;
 *  * the answer goes back with its HTTP status (200, 204, 400, 401, 403, 404, 422, 429, 503, 504), as `identity`, `gzip` or (the fallback) `xz`, as raw bytes
 *    (codec 2) in chunks sized by the lane's planner, the last chunk declaring the decoded size and the SHA-256; the result is checked against the contract
 *    before the first chunk is sent;
 *  * route chunks are BULK, status and health results SMALL; the poll is SMALL; so a status job completes while a route is mid-upload;
 *  * resume: after a link loss the comma's `next_sequence` says what it has committed; the transfer continues from there while the compressed bytes are held, and a
 *    job that outlived its deadline answers 404 and ends here. A job that is partly uploaded and whose bytes are gone is failed, never re-fetched and spliced.
 *
 * Nothing is logged that names a target, a body or a coordinate (the token is inside the target).
 */
internal class SidecarJobs(
    private val laneSource: () -> BleLane?,
    private val fetcher: LocalFetcher = LoopbackFetcher(),
    /** True when write without response is not available on this link: bytes cost more than the encoder's time, `xz` replaces `gzip`. */
    private val preferXz: () -> Boolean = { false },
    private val clock: () -> Long = System::nanoTime,
    private val warn: (String) -> Unit = { Log.w("StarPilotSidecarBLE", it) },
    private val workers: Executor = Executors.newCachedThreadPool { task -> Thread(task, "SidecarBLE-Job").apply { isDaemon = true } },
    /** Runs the poll cycle; null in tests, which call [pollOnce] themselves. */
    private val poller: ScheduledExecutorService? = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "SidecarBLE-Jobs").apply { isDaemon = true } },
) : Closeable {
    @Volatile private var running = true
    private val active = ConcurrentHashMap<String, Shape>()
    private val transfers = ConcurrentHashMap<String, Transfer>()
    private var scheduled: ScheduledFuture<*>? = null

    /** The compressed answer of a job that is being uploaded, and where each chunk ended: the resume table. */
    private class Transfer(val id: String, val target: String, val kind: Shape, val encoded: JobContract.Encoded, val createdNs: Long) {
        /** `ends[n]` is the end offset of chunk n; recorded before the chunk goes out, because its acknowledgement can be lost after comma committed it. */
        val ends = ArrayList<Int>()
    }

    private val done = AtomicInteger()
    private val failed = AtomicInteger()
    private val refused = AtomicInteger()
    /** Counters for the status page and the tests; they never hold a target or a body. */
    val jobsDone: Int get() = done.get()
    val jobsFailed: Int get() = failed.get()
    val jobsRefused: Int get() = refused.get()

    /** How many immediate polls were asked for (tests and the status page). */
    internal val pollRequests = AtomicInteger()

    /** Polls soon: from the push wake-up, after a connect, after a job, after the user sends a destination. */
    fun pollNow() { pollRequests.incrementAndGet(); schedule(0) }

    @Synchronized private fun schedule(delayMs: Long) {
        if (!running) return
        scheduled?.cancel(false)
        val executor = poller ?: return
        scheduled = try { executor.schedule({ cycle() }, delayMs, TimeUnit.MILLISECONDS) } catch (_: java.util.concurrent.RejectedExecutionException) { null }
    }

    private fun cycle() {
        if (!running) return
        var next = IDLE_POLL_MS
        try {
            val lane = laneSource()
            if (lane != null && lane.isOpen) next = pollOnce(lane)
        } catch (error: Throwable) {
            warn("Job poll failed (${error.javaClass.simpleName})")
        }
        schedule(next)
    }

    /** One poll and the dispatch of what it lists. Returns the delay until the next poll (the comma's hint, bounded). */
    internal fun pollOnce(lane: BleLane): Long {
        val list = poll(lane) ?: return IDLE_POLL_MS
        forgetStale(list)
        if (list.jobs.any { !it.claimed }) lane.wantHigh() // a job this phone may take: traffic is coming
        for (job in list.jobs) {
            if (job.claimed || active.containsKey(job.id) || active.containsValue(job.kind)) continue // another phone holds it, it is running, or this kind is busy
            active[job.id] = job.kind
            try {
                workers.execute {
                    var settled = false
                    try { settled = runJob(lane, job) } finally {
                        active.remove(job.id)
                        // A job that reached its end makes room for the next one at once. A job that stays open (no answer from the X10 yet, the link lost) waits for the
                        // next poll at the comma's cadence: polling again at once would repeat the same failure in a tight loop.
                        if (settled) pollNow()
                    }
                }
            } catch (e: java.util.concurrent.RejectedExecutionException) { active.remove(job.id) }
        }
        return list.nextPollMs
    }

    /** `GET /_bridge/sidecar/poll2`; null when nothing usable came back. */
    internal fun poll(lane: BleLane): JobList? {
        val answer = try { lane.call(LaneClass.SMALL, 10, laneRequest("GET", POLL2), label = "poll2") } catch (error: IOException) {
            warn("Job poll interrupted (${error.javaClass.simpleName})")
            return null
        }
        val status = answer.optInt("status")
        if (status == 204) return JobList(emptyList(), IDLE_POLL_MS)
        if (status != 200) return null
        return try { parseJobList(Base64.getDecoder().decode(answer.optString("body", ""))) } catch (_: IllegalArgumentException) {
            refused.incrementAndGet()
            warn("Job list refused")
            null
        }
    }

    /** A transfer whose job comma no longer lists is over (deadline, caller gone, finished): the bytes go, so no late answer is ever started again. */
    private fun forgetStale(list: JobList) {
        val listed = list.jobs.map { it.id }.toSet()
        val limit = clock() - TRANSFER_KEEP_NS
        for ((id, transfer) in transfers) if (!active.containsKey(id) && (id !in listed || transfer.createdNs < limit)) transfers.remove(id)
    }

    /** Runs one job as far as it goes. True when the job reached its end here (answered, failed, or gone at comma), false when it stays open. */
    private fun runJob(lane: BleLane, job: JobInfo): Boolean {
        try {
            val verdict = Allowlist.check("GET", job.target)
            if (!verdict.ok || verdict.shape != job.kind) {
                refused.incrementAndGet()
                warn("Job refused (reason=not-allowed, kind=${job.kind.wire})")
                failJob(lane, job.id)
                return true
            }
            var transfer = transfers[job.id]?.takeIf { it.target == job.target }
            if (transfer == null) {
                transfers.remove(job.id)
                if (job.nextSequence != 0) {
                    // A restart lost the original bytes; a new download could differ and must never be spliced onto them.
                    warn("Job cannot resume without its original payload (kind=${job.kind.wire})")
                    failJob(lane, job.id)
                    return true
                }
                val begun = begin(lane, job)
                transfer = begun.transfer ?: return begun.settled
                transfers[job.id] = transfer
            }
            return send(lane, transfer, job.nextSequence)
        } catch (error: IOException) {
            // The link failed or stalled: the bytes are kept for the next poll, after the reconnect.
            warn("Job transfer interrupted (${error.javaClass.simpleName}, kind=${job.kind.wire})")
            return false
        } catch (error: Exception) {
            failed.incrementAndGet()
            transfers.remove(job.id)
            warn("Job failed (${error.javaClass.simpleName}, kind=${job.kind.wire})")
            try { failJob(lane, job.id) } catch (_: Exception) { /* comma's deadline still applies */ }
            return true
        }
    }

    private class Begun(val transfer: Transfer?, val settled: Boolean)

    /** Fetches the X10's answer and makes the result. No transfer when the job stays open (no answer came: a fresh GET is safe on the next poll) or was failed. */
    private fun begin(lane: BleLane, job: JobInfo): Begun {
        val answer = try { fetcher.fetch(job.kind, job.target) } catch (error: IOException) {
            val origin = (error as? RouteFetchIo)?.origin ?: error.javaClass.simpleName
            val stage = (error as? RouteFetchIo)?.stage ?: "fetch"
            warn("Job fetch deferred ($origin, stage=$stage, kind=${job.kind.wire})")
            return Begun(null, false)
        } catch (error: RouteFetchRejected) {
            failed.incrementAndGet()
            warn("Job fetch rejected (reason=${error.reason}, status=${error.status ?: "none"}, kind=${job.kind.wire})")
            failJob(lane, job.id)
            return Begun(null, true)
        }
        if (answer.status == 200 && answer.body.isEmpty()) {
            failed.incrementAndGet()
            warn("Job fetch rejected (reason=invalid-body, status=200, kind=${job.kind.wire})")
            failJob(lane, job.id)
            return Begun(null, true)
        }
        val encoded = try { JobContract.encode(job.kind.wire, answer.status, answer.body, preferXz()) } catch (error: ContractError) {
            failed.incrementAndGet()
            warn("Job result refused (${error.message}, status=${answer.status}, kind=${job.kind.wire})")
            failJob(lane, job.id)
            return Begun(null, true)
        }
        return Begun(Transfer(job.id, job.target, job.kind, encoded, clock()), false)
    }

    /** Sends the chunks from [nextSequence]. True when the job ended here (the last chunk was accepted, comma ended it, or it was failed); an IOException means it stays open. */
    private fun send(lane: BleLane, transfer: Transfer, nextSequence: Int): Boolean {
        if (nextSequence > transfer.ends.size) {
            // comma holds more chunks than this transfer ever sent: the two sides disagree, so nothing more is sent for this job.
            warn("Job cursor ahead of the transfer (kind=${transfer.kind.wire})")
            transfers.remove(transfer.id)
            failJob(lane, transfer.id)
            return true
        }
        while (transfer.ends.size > nextSequence) transfer.ends.removeAt(transfer.ends.size - 1) // resume at the comma's cursor
        val payload = transfer.encoded.payload
        val cls = if (transfer.kind == Shape.ROUTE) LaneClass.BULK else LaneClass.SMALL
        var sequence = nextSequence
        var offset = if (sequence == 0) 0 else transfer.ends[sequence - 1]
        do {
            if (!running) throw IOException("Sidecar jobs stopped")
            val remaining = payload.size - offset
            val size = if (transfer.kind == Shape.ROUTE) lane.planBulkChunk(remaining) else remaining
            val end = offset + size
            val last = end >= payload.size
            val path = JobContract.chunkPath(transfer.id, sequence, last, transfer.encoded.status, transfer.encoded.encoding,
                if (last) transfer.encoded.size else null, if (last) transfer.encoded.sha256 else null)
            transfer.ends.add(end)
            val answer = lane.call(cls, 30, laneRequest("POST", path), payload.copyOfRange(offset, end), size, "chunk")
            when (answer.optInt("status")) {
                200 -> {
                    val ack = try { StrictJson.parseObject(Base64.getDecoder().decode(answer.optString("body", ""))) } catch (_: IllegalArgumentException) { throw IOException("Job chunk answer unreadable") }
                    val next = (ack["next"] as? StrictJson.Num)?.toLongOrNull()
                    if (next != sequence + 1L) throw ContractError("chunk acknowledgement mismatch")
                }
                404 -> { transfers.remove(transfer.id); warn("Job gone at comma (kind=${transfer.kind.wire})"); return true } // the job ended: a late answer never holds the lane
                400 -> { transfers.remove(transfer.id); failed.incrementAndGet(); warn("Job result refused by comma (kind=${transfer.kind.wire})"); return true }
                else -> throw IOException("Job chunk not accepted")
            }
            sequence++
            offset = end
        } while (!last)
        transfers.remove(transfer.id)
        done.incrementAndGet()
        return true
    }

    private fun failJob(lane: BleLane, id: String) {
        val answer = lane.call(LaneClass.SMALL, 10, laneRequest("POST", "/_bridge/sidecar/fail/$id"), label = "fail")
        if (answer.optInt("status") !in setOf(200, 404)) throw IOException("Job failure was not acknowledged")
    }

    override fun close() {
        running = false
        scheduled?.cancel(false)
        poller?.shutdownNow()
        (workers as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }

    companion object {
        const val POLL2 = "/_bridge/sidecar/poll2"
        const val IDLE_POLL_MS = 10_000L
        private const val MIN_POLL_MS = 1_000L
        private const val MAX_POLL_MS = 30_000L
        private const val MAX_JOBS = 8
        private const val MAX_LIST_BYTES = 64 * 1024
        /** The comma's job deadline is 90 s; bytes older than this are of no use. */
        private val TRANSFER_KEEP_NS = TimeUnit.SECONDS.toNanos(100)

        /** The `poll2` answer: `{"v":2,"jobs":[{"id","kind","target","next_sequence","claimed","lease_ms"}],"next_poll_ms":N}`. Anything else is refused. */
        internal fun parseJobList(body: ByteArray): JobList {
            if (body.size > MAX_LIST_BYTES) throw IllegalArgumentException("job list too large")
            val root = try { StrictJson.parseObject(body, MAX_LIST_BYTES) } catch (error: StrictJson.Invalid) { throw IllegalArgumentException("job list", error) }
            if ((root["v"] as? StrictJson.Num)?.text != "2") throw IllegalArgumentException("job list version")
            val rows = root["jobs"] as? List<*> ?: throw IllegalArgumentException("job list rows")
            if (rows.size > MAX_JOBS) throw IllegalArgumentException("job list length")
            val jobs = rows.map { row ->
                val job = row as? StrictJson.Obj ?: throw IllegalArgumentException("job row")
                val id = job["id"] as? String
                val kind = Shape.values().firstOrNull { it.wire == job["kind"] && it != Shape.VEHICLE }
                val target = job["target"] as? String
                val sequence = (job["next_sequence"] as? StrictJson.Num)?.toLongOrNull()
                val claimed = job["claimed"] as? Boolean
                val lease = (job["lease_ms"] as? StrictJson.Num)?.toLongOrNull()
                if (id == null || !JobContract.isJobId(id) || kind == null || target == null || sequence == null || sequence !in 0..999_999 || claimed == null || lease == null)
                    throw IllegalArgumentException("job row fields")
                JobInfo(id, kind, target, sequence.toInt(), claimed, lease)
            }
            if (jobs.map { it.id }.toSet().size != jobs.size) throw IllegalArgumentException("job ids repeat")
            val hint = (root["next_poll_ms"] as? StrictJson.Num)?.toLongOrNull() ?: IDLE_POLL_MS
            return JobList(jobs, hint.coerceIn(MIN_POLL_MS, MAX_POLL_MS))
        }
    }
}

/**
 * The X10 route API on `127.0.0.1:8765`, the only place the Companion fetches from. The status is passed through (a 503 `RouteAwaitingApproval` is an answer,
 * not a failure); a redirect, a body over the bound, or a body that is not the length it says are refused. Nothing but the request line and the headers built here
 * is sent.
 */
internal class LoopbackFetcher(private val port: Int = 8765, private val running: () -> Boolean = { true }) : LocalFetcher {
    override fun fetch(shape: Shape, target: String): LocalAnswer {
        var retried = false
        while (true) {
            try { return fetchOnce(shape, target) } catch (error: RouteFetchIo) {
                // The route API closes idle keep-alive sockets after five seconds; HttpURLConnection may pick one just as it goes stale.
                if (!retried && error.stage == "response" && error.cause is SocketException) { retried = true; continue }
                throw error
            }
        }
    }

    private fun fetchOnce(shape: Shape, target: String): LocalAnswer {
        val route = shape == Shape.ROUTE
        val limit = if (route) JobContract.MAX_ROUTE_BYTES else JobContract.MAX_SMALL_BYTES
        val endpoint = try { URL("http://127.0.0.1:$port$target") } catch (_: MalformedURLException) { throw RouteFetchRejected("invalid-target") }
        val connection = endpoint.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        var stage = "setup"
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 3_000
            connection.readTimeout = if (route) 10_000 else 5_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Connection", "close")
            stage = "response"
            val status = connection.responseCode
            if (status in 300..399) throw RouteFetchRejected("redirect", status)
            val declared = connection.contentLengthLong
            if (declared > limit) throw RouteFetchRejected("oversize", status)
            val result = ByteArrayOutputStream()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(if (route) 45 else 10)
            stage = "body"
            (if (status >= 400) connection.errorStream else connection.inputStream)?.use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    if (!running() || System.nanoTime() > deadline) throw SocketTimeoutException("local fetch timed out")
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (result.size() + count > limit) throw RouteFetchRejected("oversize", status)
                    result.write(buffer, 0, count)
                }
            }
            if (declared >= 0 && result.size().toLong() != declared) throw RouteFetchRejected("invalid-body", status)
            return LocalAnswer(status, result.toByteArray())
        } catch (error: IOException) {
            throw RouteFetchIo(stage, error.javaClass.simpleName, error)
        } finally { connection.disconnect() }
    }
}
