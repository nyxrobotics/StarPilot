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
 * What comma's bridge announces in `GET /_bridge/health` (docs/dev/bluetooth-link.md section 5.3 and the deviation of the bridge, bluetooth-comma-deploy.md): the flags
 * `poll2`, `results2` and `push` (and, since contract version 2, `pushNav`: the push frames may carry the beacon's `nav` object to a phone that announces it in `push-enable`; since
 * contract version 3 `maps`, `wwr` and `up_kb_s`: the bridge takes the five map shapes, its RX takes write without response, and it measures its uplink; a phone that read `maps` announces it in `push-enable`).
 * **The flags decide, never `protocol`**: the iOS Galaxy app refuses a health `protocol` other than 1, so the bridge keeps 1 and adds
 * `sidecarProtocol` 2 next to the flags ([protocol] is the larger of the two, for the status page only). A bridge that has no flag, or an answer this reader does
 * not understand, is the old one: the old poll and chunk calls (the route worker's legacy path) keep working with it and nothing new is used (a new Companion on an old
 * bridge, and the old Companion on a new bridge, both work).
 */
internal class BridgeFeatures(
    val protocol: Int, val poll2: Boolean, val results2: Boolean, val push: Boolean, val xz: Boolean, val pushNav: Boolean = false,
    /** Contract version 3: the bridge takes the five map shapes (the literal `true`). The Companion announces `maps` in `push-enable` only to a bridge that said so. */
    val maps: Boolean = false,
    /** The bridge's RX characteristic takes write without response (the literal `true`). */
    val wwr: Boolean = false,
    /** The bridge's own measure of its uplink in KB/s (the median of the last five chunk uploads, from the first to the last data packet); null while it has none. */
    val upKbS: Double? = null,
    /** `up_kb_s` is there and is not a finite number: the comma's client treats the link as closed. */
    val upKbSInvalid: Boolean = false,
) {
    /** Jobs by `poll2` and results with a status, an encoding and a digest. */
    val jobsV2: Boolean get() = poll2 && results2
    /**
     * A phone announces `maps` in `push-enable` (contract version 3) only to a bridge that said `maps` and that speaks the jobs of version 2 which the map shapes need (a bridge that did not say
     * it is given the announcement it always got, and a Companion never announces a capability it cannot carry).
     */
    val announcesMaps: Boolean get() = maps && jobsV2
    /** What the comma's tile client will do with this health answer. */
    val mapAdmission: MapAdmission get() = MapAdmission.of(this)

    companion object {
        val LEGACY = BridgeFeatures(1, poll2 = false, results2 = false, push = false, xz = false)

        fun parse(body: ByteArray): BridgeFeatures {
            val value = try { StrictJson.parseObject(body, 4096) } catch (_: StrictJson.Invalid) { return LEGACY }
            fun level(name: String) = (value[name] as? StrictJson.Num)?.toLongOrNull()?.takeIf { it in 1..1000 }?.toInt() ?: 1
            fun flag(name: String) = value[name] == true
            // `xz` is announced only to switch it off: the bridge's interpreter has `lzma` (measured), and a refused `xz` result is a failed job, never a wrong route.
            val rate = (value["up_kb_s"] as? StrictJson.Num)?.toDoubleOrNull()?.takeIf { it.isFinite() }
            return BridgeFeatures(maxOf(level("protocol"), level("sidecarProtocol")), flag("poll2"), flag("results2"), flag("push"), value["xz"] != false, flag("pushNav"),
                maps = flag("maps"), wwr = flag("wwr"), upKbS = rate, upKbSInvalid = value.has("up_kb_s") && rate == null)
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
 *  * each job is one of the allow-listed GET shapes (route, status, health and, for a Companion that announced `maps`, the five map shapes of contract version 3: `roadtiles`,
 *    `stile`, `geocode`, `suggest`, `retrieve`), fetched from `127.0.0.1:8765` only, never following a redirect;
 *  * the answer goes back with its HTTP status (200, 204, 400, 401, 403, 404, 422, 429, 503, 504), as `identity`, `gzip` or (the fallback) `xz`, as raw bytes
 *    (codec 2) in chunks sized by the lane's planner, the last chunk declaring the decoded size and the SHA-256; the result is checked against the contract
 *    before the first chunk is sent;
 *  * route chunks are BULK, the chunks of `roadtiles` and `stile` are MAP (strictly below the route), status, health and search results SMALL; the poll is SMALL; so a status job completes
 *    while a route is mid-upload and a tile never holds anything else for more than one chunk (the planner of each class, `BleLane`);
 *  * a map job has its own deadline (120 s `roadtiles`, 30 s `stile`, 15 s a search), counted here from the first poll that listed it (the comma counted from its queueing, and its own
 *    404 ends the job); a job past it sends nothing more and its bytes go;
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
    /** True while this phone has announced `maps` to the bridge (the bridge said `maps` in its health answer): only then are the five map shapes allowed. */
    private val mapsAnnounced: () -> Boolean = { false },
) : Closeable {
    @Volatile private var running = true
    private val active = ConcurrentHashMap<String, Shape>()
    private val transfers = ConcurrentHashMap<String, Transfer>()
    /** When the first poll listed each job of a map kind (the local deadline counts from it). */
    private val firstSeen = ConcurrentHashMap<String, Long>()
    private val lostIds = ConcurrentHashMap.newKeySet<String>()
    private var scheduled: ScheduledFuture<*>? = null

    /** The compressed answer of a job that is being uploaded, and where each chunk ended: the resume table. */
    private class Transfer(val id: String, val target: String, val kind: Shape, val encoded: JobContract.Encoded, val createdNs: Long, val deadlineNs: Long = 0L) {
        /** `ends[n]` is the end offset of chunk n; recorded before the chunk goes out, because its acknowledgement can be lost after comma committed it. */
        val ends = ArrayList<Int>()
    }

    private val done = AtomicInteger()
    private val failed = AtomicInteger()
    private val refused = AtomicInteger()
    private val lost = AtomicInteger()
    /** Counters for the status page and the tests; they never hold a target or a body. */
    val jobsDone: Int get() = done.get()
    val jobsFailed: Int get() = failed.get()
    val jobsRefused: Int get() = refused.get()
    /** Map jobs that passed their own deadline before their last chunk went out. */
    val jobsLost: Int get() = lost.get()

    /** Transfers whose compressed bytes are held for a resume. */
    internal val heldTransfers: Int get() = transfers.size

    /** How many immediate polls were asked for (tests and the status page). */
    internal val pollRequests = AtomicInteger()

    /** A poll was asked for since the cycle that is running now began: its own timer must not replace it (found by the twin: a wake that arrived during a poll waited for the 10 s idle timer). */
    @Volatile private var pollAsked = false

    /** Polls soon: from the push wake-up, after a connect, after a job, after the user sends a destination. */
    fun pollNow() { pollRequests.incrementAndGet(); pollAsked = true; schedule(0) }

    @Synchronized private fun schedule(delayMs: Long) {
        if (!running) return
        scheduled?.cancel(false)
        val executor = poller ?: return
        scheduled = try { executor.schedule({ cycle() }, delayMs, TimeUnit.MILLISECONDS) } catch (_: java.util.concurrent.RejectedExecutionException) { null }
    }

    private fun cycle() {
        if (!running) return
        pollAsked = false
        var next = IDLE_POLL_MS
        try {
            val lane = laneSource()
            if (lane != null && lane.isOpen) next = pollOnce(lane)
        } catch (error: Throwable) {
            warn("Job poll failed (${error.javaClass.simpleName})")
        }
        schedule(if (pollAsked) 0 else next) // a poll that was asked for while this one ran comes next, at once
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
        val now = clock()
        for ((id, transfer) in transfers) if (!active.containsKey(id) && (id !in listed || transfer.createdNs < now - keepNs(transfer.kind))) transfers.remove(id)
        for (id in firstSeen.keys) if (id !in listed && !active.containsKey(id)) { firstSeen.remove(id); lostIds.remove(id) }
    }

    /** Runs one job as far as it goes. True when the job reached its end here (answered, failed, or gone at comma), false when it stays open. */
    private fun runJob(lane: BleLane, job: JobInfo): Boolean {
        try {
            // A phone that did not announce `maps` judges as a peer of version 2: the five map shapes are refused (a bridge never lists one to it; a job listed anyway is failed, never fetched).
            val verdict = Allowlist.check("GET", job.target, version = if (mapsAnnounced()) Allowlist.CONTRACT_VERSION else 2)
            if (!verdict.ok || verdict.shape != job.kind) {
                refused.incrementAndGet()
                warn("Job refused (reason=not-allowed, kind=${job.kind.wire})")
                failJob(lane, job.id)
                return true
            }
            val seen = if (job.kind.isMap) firstSeen.computeIfAbsent(job.id) { clock() } else 0L
            if (job.kind.isMap && clock() - seen > TimeUnit.SECONDS.toNanos(job.kind.deadlineSeconds.toLong())) { transfers.remove(job.id); return endedByDeadline(job.kind, job.id, false) }
            var transfer = transfers[job.id]?.takeIf { it.target == job.target }
            if (transfer == null) {
                transfers.remove(job.id)
                if (job.nextSequence != 0) {
                    // A restart lost the original bytes; a new download could differ and must never be spliced onto them.
                    warn("Job cannot resume without its original payload (kind=${job.kind.wire})")
                    failJob(lane, job.id)
                    return true
                }
                val begun = begin(lane, job, seen)
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
    private fun begin(lane: BleLane, job: JobInfo, seen: Long): Begun {
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
        val deadline = if (job.kind.isMap) seen + TimeUnit.SECONDS.toNanos(job.kind.deadlineSeconds.toLong()) else 0L
        return Begun(Transfer(job.id, job.target, job.kind, encoded, clock(), deadline), false)
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
        val cls = transfer.kind.jobClass ?: LaneClass.SMALL
        // The route, the tiles and the answers of the searches go in chunks of their class's planner; a status or health answer (at most 4 KiB) is one chunk.
        val chunked = transfer.kind == Shape.ROUTE || transfer.kind.isMap
        var sequence = nextSequence
        var offset = if (sequence == 0) 0 else transfer.ends[sequence - 1]
        do {
            if (!running) throw IOException("Sidecar jobs stopped")
            if (transfer.deadlineNs != 0L && clock() > transfer.deadlineNs) { transfers.remove(transfer.id); return endedByDeadline(transfer.kind, transfer.id, true) }
            val remaining = payload.size - offset
            val size = if (chunked) lane.planChunk(cls, remaining) else remaining
            val end = offset + size
            val last = end >= payload.size
            val path = JobContract.chunkPath(transfer.id, sequence, last, transfer.encoded.status, transfer.encoded.encoding,
                if (last) transfer.encoded.size else null, if (last) transfer.encoded.sha256 else null)
            transfer.ends.add(end)
            val answer = lane.call(cls, 30, laneRequest("POST", path), payload.copyOfRange(offset, end), if (chunked) size else 0, "chunk")
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

    /** A map job past its own deadline: the comma ended it (its deadline started earlier), so nothing more is fetched or sent. True when the bytes of a transfer were dropped (the job is over here), false before any. */
    private fun endedByDeadline(kind: Shape, id: String, hadTransfer: Boolean): Boolean {
        if (lostIds.add(id)) { lost.incrementAndGet(); warn("Job past its deadline (kind=${kind.wire})") }
        // Without a transfer the job stays listed until the comma drops it; polling again at once would only repeat this.
        return hadTransfer
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
        /** The comma's job deadline of a route is 90 s; bytes older than this are of no use. */
        private val TRANSFER_KEEP_NS = TimeUnit.SECONDS.toNanos(100)

        /** How long the bytes of a transfer are held for a resume: the comma's deadline of its kind plus ten seconds (a route: 100 s, as before). */
        private fun keepNs(kind: Shape): Long = if (kind.isMap) TimeUnit.SECONDS.toNanos(kind.deadlineSeconds + 10L) else TRANSFER_KEEP_NS

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
 * The X10 route API on `127.0.0.1:8765`, the only place the Companion fetches from. The status is passed through (a 503 `RouteAwaitingApproval` is an answer, not a failure, and a 4xx is
 * an answer that is carried as it came and never fetched again); a redirect, a body over the bound, or a body that is not the length it says are refused. Nothing but the request line
 * and the headers built here is sent (`Accept-Encoding: identity`, `Connection: close`: no header of the comma or of the caller is forwarded).
 *
 * The bound of a body (contract version 3, docs/dev/bluetooth-map-contract.md 2.3): a bundle of `roadtiles` 524,288 bytes, a style tile 2 MiB, the JSON of a search 64 KiB, and 4 KiB for the body of
 * any status other than 200 of a map shape (the older shapes keep their bounds: a route 8 MiB, status and health 4 KiB). A map answer that says it is encoded (a `Content-Encoding` other than
 * `identity`) is refused: the result layer carries `identity` only.
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
        val endpoint = try { URL("http://127.0.0.1:$port$target") } catch (_: MalformedURLException) { throw RouteFetchRejected("invalid-target") }
        val connection = endpoint.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        var stage = "setup"
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 3_000
            connection.readTimeout = when { route -> 10_000; shape == Shape.ROADTILES || shape == Shape.STILE -> 10_000; else -> 5_000 }
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Connection", "close")
            stage = "response"
            val status = connection.responseCode
            if (status in 300..399) throw RouteFetchRejected("redirect", status)
            val limit = if (shape.isMap) JobContract.mapLimit(shape, status) else if (route) JobContract.MAX_ROUTE_BYTES else JobContract.MAX_SMALL_BYTES
            if (shape.isMap) {
                val encoding = connection.contentEncoding
                if (encoding != null && !encoding.equals("identity", ignoreCase = true)) throw RouteFetchRejected("content-encoding", status)
            }
            val declared = connection.contentLengthLong
            if (declared > limit) throw RouteFetchRejected("oversize", status)
            val result = ByteArrayOutputStream()
            val seconds = when { route -> 45L; shape == Shape.ROADTILES -> 30L; shape == Shape.STILE -> 20L; else -> 10L }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
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
