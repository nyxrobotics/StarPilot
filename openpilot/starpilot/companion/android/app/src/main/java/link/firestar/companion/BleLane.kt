package link.firestar.companion

import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The three classes of the lane, highest priority first (docs/dev/bluetooth-link.md section 7.1). */
enum class LaneClass {
    /** The relay's POST, DELETE and state calls, the guidance poll, the phone GPS, the Galaxy screens. */
    INTERACTIVE,
    /** The job poll, the results of status and health jobs, `push-enable`. */
    SMALL,
    /** Route chunks. */
    BULK,
}

/** The one physical link under a lane. [exchange] is one request and its answer; it fails by throwing, and a failure ends the link. */
internal interface LaneLink : Closeable {
    /** [meta] is the request JSON; [body] is a raw body (codec 2) or null (codec 0 with the base64 `body` member of [meta], if any). */
    fun exchange(meta: JSONObject, body: ByteArray?, deadlineSeconds: Long): JSONObject
    /** A GATT operation that is not a request and still has to take its turn on the link (for example the descriptor write that switches notifications on). */
    fun control(action: String): JSONObject
    /** Asks the stack for the high ([on]) or the balanced connection priority. Never blocks behind a running exchange. */
    fun setHighPriority(on: Boolean)
}

/** The lane is closed: its link failed, stalled, or was closed. Nothing queued on it is replayed. */
internal class LaneClosed(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A request waited in the queue longer than its own deadline and was dropped before it started (the link stays open). */
internal class LaneWaitExceeded : IOException("Bluetooth request waited too long behind other traffic")

/**
 * Chooses the next bulk chunk from the rate the lane has measured: body bytes per second of a finished chunk, smoothed (EWMA 0.5), times a cap of [capSeconds]
 * seconds, in whole packets, between 1 KiB and 64 KiB; the first chunk is [startBytes]. So no interactive or small request waits longer than about one chunk
 * (docs/dev/bluetooth-link.md sections 6 L5 and 7.1; the reference is `tools/btlab/contract/lane.py` `ChunkPlanner`).
 */
internal class ChunkPlanner(val capSeconds: Double = DEFAULT_CAP_S, private val startBytes: Int = 6 * 1024) {
    /** Bytes per second of the last chunks, null before the first measurement. */
    @Volatile var rate: Double? = null
        private set

    @Synchronized fun observe(bodyBytes: Int, seconds: Double) {
        if (seconds <= 0.0 || bodyBytes <= 0) return
        val sample = bodyBytes / seconds
        rate = rate?.let { 0.5 * it + 0.5 * sample } ?: sample
    }

    fun nextBytes(remaining: Int): Int {
        if (remaining <= 0) return 0
        val measured = rate
        var size = if (measured == null) startBytes else (measured * capSeconds).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        size = maxOf(MIN_CHUNK, minOf(MAX_CHUNK, size))
        size -= size % PACKET_DATA // fill whole packets
        return minOf(maxOf(size, MIN_CHUNK), remaining)
    }

    companion object {
        const val DEFAULT_CAP_S = 5.0
        const val MIN_CHUNK = 1024
        const val MAX_CHUNK = 64 * 1024
        /** Frame bytes per data packet: ATT payload 244 minus the 5-byte header. */
        const val PACKET_DATA = 239
    }
}

/**
 * When the lane asks the stack for the high connection priority (15 ms interval instead of 45 to 68 ms): when a bulk transfer is about to start or when a job
 * list is not empty; it is kept while the lane is busy and given back after [idleReleaseMs] without a request. The switch needs about a second to apply, so single
 * exchanges (a 15 s state poll, a 10 s job poll, a 1 Hz push that comma sends by itself) never ask for it (section 6, "the connection-priority policy").
 * Times are in milliseconds of the lane's clock.
 */
internal class PriorityPolicy(private val idleReleaseMs: Long = 8_000) {
    var high = false
        private set
    private var lastActivityMs = 0L

    /** True when HIGH has to be requested now (it was off). */
    fun wantHigh(nowMs: Long): Boolean {
        lastActivityMs = nowMs
        if (high) return false
        high = true
        return true
    }

    /** A request started or ended: the lane is busy. */
    fun activity(nowMs: Long) { lastActivityMs = nowMs }

    /** True when BALANCED has to be requested now: HIGH is on and nothing ran for [idleReleaseMs]. */
    fun shouldRelease(nowMs: Long, busy: Boolean): Boolean {
        if (!high || busy || nowMs - lastActivityMs < idleReleaseMs) return false
        high = false
        return true
    }
}

/** One finished request, for the observer (tests, counters): times in nanoseconds of the lane's clock. */
internal class LaneEvent(val cls: LaneClass, val label: String, val submittedAt: Long, val startedAt: Long, val endedAt: Long, val failed: Boolean) {
    val waitSeconds get() = (startedAt - submittedAt) / 1e9
    val serviceSeconds get() = (endedAt - startedAt) / 1e9
}

/**
 * The lane: one link, one request in flight, three classes (docs/dev/bluetooth-link.md section 7.1). One owner thread runs the requests; callers block (or take a
 * [Ticket]). Strict priority INTERACTIVE > SMALL > BULK, first come first served inside a class, never preempting a request that has started. A bulk request is
 * one chunk sized by [planner], so the wait of anything else is at most one chunk plus the request in front of it.
 *
 * Fail closed: when a request fails, or runs past its deadline (plus [watchdogGraceSeconds]), the link is closed, **every request that is queued or in flight
 * fails at once**, nothing is replayed (the framing cannot recover and a mutating request must not be repeated), and the lane stays closed: the next
 * connection makes a new lane. [onLinkClosed] runs once.
 *
 * @param clock monotonic nanoseconds; tests inject a virtual one.
 * @param watchdogEnabled start the watchdog thread with the first request (deadline check and the priority release); tests call [checkDeadline] and [checkPriority] themselves.
 */
internal class BleLane(
    private val link: LaneLink,
    private val clock: () -> Long = System::nanoTime,
    private val watchdogEnabled: Boolean = true,
    val planner: ChunkPlanner = ChunkPlanner(),
    private val policy: PriorityPolicy = PriorityPolicy(),
    private val watchdogGraceSeconds: Long = 5,
) : Closeable {

    /** The handle of one request. */
    class Ticket internal constructor(val cls: LaneClass, val label: String, internal val meta: JSONObject, internal val body: ByteArray?,
                                      internal val bodyBytes: Int, internal val deadlineSeconds: Long, internal val control: String? = null) {
        internal val future = CompletableFuture<JSONObject>()
        internal var submittedAt = 0L
        internal var startedAt = 0L
        @Volatile internal var started = false

        /** Blocks until the answer; a failure comes back as the exception that caused it (an [IOException], usually a [LaneClosed]). */
        fun await(): JSONObject = try { future.get() } catch (e: ExecutionException) {
            throw e.cause.let { it as? IOException ?: it as? RuntimeException ?: IOException(it) }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(false)
            throw IOException("Bluetooth request interrupted")
        }
        val done get() = future.isDone
    }

    private val lock = ReentrantLock()
    private val wake = lock.newCondition()
    private val queues = Array(LaneClass.entries.size) { ArrayDeque<Ticket>() }
    private var inFlight: Ticket? = null
    private var closed = false
    private val lossReported = java.util.concurrent.atomic.AtomicBoolean()
    private var owner: Thread? = null
    private var watchdog: Thread? = null

    /** Runs once, from the thread that ended the lane, after every waiting request was failed. */
    @Volatile var onLinkClosed: () -> Unit = {}
    /** Called for every request that ended (answered or failed), from the owner thread. */
    @Volatile var observer: ((LaneEvent) -> Unit)? = null

    val isOpen: Boolean get() = lock.withLock { !closed }

    /** Queues a request. [bodyBytes] (bulk only) is the chunk's body size: its duration feeds the chunk planner. */
    fun submit(cls: LaneClass, deadlineSeconds: Long, meta: JSONObject, body: ByteArray? = null, bodyBytes: Int = 0, label: String = ""): Ticket =
        enqueue(Ticket(cls, label, meta, body, bodyBytes, deadlineSeconds))

    /** Queues a control operation of the link (see [LaneLink.control]) and waits for it. */
    fun control(cls: LaneClass, deadlineSeconds: Long, action: String): JSONObject = enqueue(Ticket(cls, action, JSONObject(), null, 0, deadlineSeconds, action)).await()

    private fun enqueue(ticket: Ticket): Ticket {
        val cls = ticket.cls
        var ask = false
        lock.withLock {
            if (closed) { ticket.future.completeExceptionally(LaneClosed("Bluetooth link is closed")); return ticket }
            ticket.submittedAt = clock()
            queues[cls.ordinal].addLast(ticket)
            if (cls == LaneClass.BULK) ask = policy.wantHigh(ticket.submittedAt / 1_000_000)
            if (owner == null) {
                owner = Thread({ run() }, "SidecarBLE-Lane").apply { isDaemon = true; start() }
                if (watchdogEnabled) startWatchdog()
            }
            wake.signalAll()
        }
        if (ask) applyPriority(true)
        return ticket
    }

    /** Runs one request and waits for its answer. */
    fun call(cls: LaneClass, deadlineSeconds: Long, meta: JSONObject, body: ByteArray? = null, bodyBytes: Int = 0, label: String = ""): JSONObject =
        submit(cls, deadlineSeconds, meta, body, bodyBytes, label).await()

    /** The size of the next bulk chunk of a transfer with [remaining] bytes left. */
    fun planBulkChunk(remaining: Int): Int = planner.nextBytes(remaining)

    /** The caller knows traffic is coming (a job list was not empty): ask for the high priority now. */
    fun wantHigh() {
        var ask = false
        lock.withLock { if (!closed) ask = policy.wantHigh(clock() / 1_000_000) }
        if (ask) applyPriority(true)
    }

    /** The watchdog's priority step: gives HIGH back after the idle time. */
    fun checkPriority() {
        var release = false
        lock.withLock { if (!closed) release = policy.shouldRelease(clock() / 1_000_000, inFlight != null || queues.any { it.isNotEmpty() }) }
        if (release) applyPriority(false)
    }

    /** The watchdog's deadline step: a request that has run past its deadline plus the grace closes the link. */
    fun checkDeadline() {
        val stuck = lock.withLock {
            val ticket = inFlight ?: return
            if (clock() - ticket.startedAt > (ticket.deadlineSeconds + watchdogGraceSeconds) * 1_000_000_000L) ticket else null
        } ?: return
        abort(LaneClosed("Bluetooth request ${stuck.label.ifEmpty { stuck.cls.name }} stalled; link closed"))
    }

    private fun applyPriority(high: Boolean) {
        try { link.setHighPriority(high) } catch (_: Exception) { /* a refused priority change is not a link failure */ }
    }

    private fun nextTicket(): Ticket? {
        lock.withLock {
            while (!closed) {
                val next = queues.firstNotNullOfOrNull { it.pollFirst() }
                if (next == null) { wake.await(); continue }
                if (next.future.isDone) continue // cancelled by its caller
                val now = clock()
                if (now - next.submittedAt > next.deadlineSeconds * 1_000_000_000L) {
                    next.future.completeExceptionally(LaneWaitExceeded())
                    continue
                }
                next.startedAt = now
                next.started = true
                inFlight = next
                policy.activity(now / 1_000_000)
                return next
            }
            return null
        }
    }

    private fun run() {
        try {
            while (true) {
                val ticket = nextTicket() ?: return
                val answer = try {
                    if (ticket.control != null) link.control(ticket.control) else link.exchange(ticket.meta, ticket.body, ticket.deadlineSeconds)
                } catch (error: Throwable) {
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    abort(error, completing = ticket)
                    return
                }
                if (!finishWith(ticket, answer, null)) return
            }
        } catch (error: Throwable) {
            abort(error) // the owner thread must never die silently: callers would wait for ever
        }
    }

    /** Ends the request in flight. False when the lane no longer owns it (the watchdog failed it first): the owner thread stops. */
    private fun finishWith(ticket: Ticket, answer: JSONObject?, error: Throwable?): Boolean {
        val ended = clock()
        lock.withLock {
            if (inFlight !== ticket) return false
            inFlight = null
            policy.activity(ended / 1_000_000)
        }
        if (answer != null && ticket.cls == LaneClass.BULK) planner.observe(ticket.bodyBytes, (ended - ticket.startedAt) / 1e9)
        observer?.invoke(LaneEvent(ticket.cls, ticket.label, ticket.submittedAt, ticket.startedAt, ended, answer == null))
        if (answer != null) ticket.future.complete(answer) else if (error != null) ticket.future.completeExceptionally(error)
        return true
    }

    /**
     * Ends the lane because the link failed: fails everything queued or in flight, closes the link, tells the owner of the lane. [completing] is the request the owner thread
     * was running: it gets the real error, but only after the lane is marked closed, so a caller that wakes up with the failure never finds an open lane.
     */
    private fun abort(cause: Throwable, completing: Ticket? = null) {
        val first = shutDown(cause, completing)
        completing?.let { finishWith(it, null, cause) }
        if (first) {
            try { link.close() } catch (_: Exception) { /* closing is best effort */ }
        }
        // The loss is reported once, also when the link closed the lane itself inside the failing request (the transport closes itself before it rethrows).
        if (lossReported.compareAndSet(false, true)) onLinkClosed()
    }

    private fun shutDown(cause: Throwable, alreadyFailed: Ticket?): Boolean {
        val victims = ArrayList<Ticket>()
        lock.withLock {
            if (closed) return false
            closed = true
            // When the owner thread itself closes the lane (the transport closes itself inside the failing request) the request in flight is completed by the owner
            // with the real error a moment later; any other thread fails it here.
            if (Thread.currentThread() !== owner) {
                inFlight?.let { victims.add(it) }
                inFlight = null
            }
            for (queue in queues) { victims.addAll(queue); queue.clear() }
            wake.signalAll()
        }
        for (ticket in victims) {
            if (ticket === alreadyFailed) continue
            val reason = if (cause is IOException && ticket.started) cause else LaneClosed("Bluetooth link closed", cause)
            if (ticket.started) observer?.invoke(LaneEvent(ticket.cls, ticket.label, ticket.submittedAt, ticket.startedAt, clock(), true))
            ticket.future.completeExceptionally(reason)
        }
        watchdog?.interrupt()
        return true
    }

    /** The owner of the link closes the lane (its own `close()`): everything waiting fails, the link is not closed again. */
    override fun close() { shutDown(LaneClosed("Bluetooth link closed"), null) }

    private fun startWatchdog() {
        watchdog = Thread({
            try {
                while (isOpen) {
                    Thread.sleep(250)
                    checkDeadline()
                    checkPriority()
                }
            } catch (_: InterruptedException) { /* the lane ended */ }
        }, "SidecarBLE-LaneWatch").apply { isDaemon = true; start() }
    }
}

/** The request JSON of a call without a body: what the lane carries and the transport seals. */
internal fun laneRequest(method: String, path: String): JSONObject = JSONObject().put("method", method).put("path", path)
