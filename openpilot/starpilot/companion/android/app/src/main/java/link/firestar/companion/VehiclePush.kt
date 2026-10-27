package link.firestar.companion

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** A push frame or packet that must not be used. The message is ours; nothing the peer sent is echoed. */
internal class PushError(message: String) : IllegalArgumentException(message)

/** An opened push frame: [body] is the exact bytes of the `POST /v1/vehicle` body (vehicle only), [auth] the token for its `Authorization` header. */
internal class PushMessage(val kind: String, val counter: Long, val tsMs: Long, val body: ByteArray?, val auth: String?)

/**
 * The push frames comma sends to the phone, one way (docs/dev/bluetooth-link.md section 5.5). Plaintext of a frame, sealed with the phone's key under the direction
 * label `push`: `{"t":"vehicle"|"wake","c":counter,"s":"<session challenge>","ts":<ms>,"b":{report},"a":"<token>"}`. One frame is 1 to 8 notifications of at most
 * 244 bytes: `0x10 | frame_id u32 | idx u8 | count u8 | data`. Reference: `tools/btlab/contract/push.py`.
 */
internal object PushFrames {
    const val PACKET_TYPE = 0x10
    const val HEADER = 7
    const val MAX_PACKETS = 8
    const val MAX_FRAME = 4096
    private val AUTH = Regex("[!-~]{10,128}")
    private val KEYS = setOf("t", "c", "s", "ts", "b", "a")

    /** Reassembles notification packets. A packet out of order, a changed count, a repeated finished frame id or an oversize frame resets it and raises. */
    class Assembler {
        private var frameId: Long? = null
        private var count = 0
        private val parts = ArrayList<ByteArray>()
        private var lastDone: Long? = null

        fun reset() { frameId = null; count = 0; parts.clear() }

        /** The finished frame (with its 4-byte length prefix) or null while parts are missing. */
        fun add(packet: ByteArray): ByteArray? {
            if (packet.size <= HEADER || (packet[0].toInt() and 255) != PACKET_TYPE) { reset(); throw PushError("push packet") }
            val id = ByteBuffer.wrap(packet, 1, 4).int.toLong() and 0xffffffffL
            val index = packet[5].toInt() and 255
            val total = packet[6].toInt() and 255
            if (total !in 1..MAX_PACKETS || index >= total) { reset(); throw PushError("push packet numbers") }
            if (frameId != id) {
                reset() // a newer frame replaces an unfinished one: only the newest position matters
                if (index != 0 || id == lastDone) throw PushError("push frame starts in the middle or repeats")
                frameId = id
                count = total
            } else if (total != count || index != parts.size) {
                reset()
                throw PushError("push packets out of order")
            }
            parts.add(packet.copyOfRange(HEADER, packet.size))
            if (parts.size == count) {
                val size = parts.sumOf { it.size }
                val frame = ByteArray(size)
                var at = 0
                for (part in parts) { System.arraycopy(part, 0, frame, at, part.size); at += part.size }
                lastDone = id
                reset()
                if (size > MAX_FRAME) throw PushError("push frame too large")
                return frame
            }
            return null
        }
    }

    /**
     * Verifies and opens a sealed frame (with its 4-byte length prefix, as the assembler returns it). Raises [PushError] for: a wrong key or direction (the GCM
     * tag), another session, a counter that did not rise, an unknown kind, extra fields, a report outside the allow-list, a token that cannot be a header value, and a
     * report with a `nav` object when [navAllowed] is false (the bridge did not announce `pushNav`: a bridge that never said it carries nav sends none).
     */
    fun open(frame: ByteArray, key: ByteArray, session: String, lastCounter: Long, navAllowed: Boolean = false): PushMessage {
        if (frame.size < 5 || ByteBuffer.wrap(frame, 0, 4).int != frame.size - 4) throw PushError("frame length")
        val message = try { BleWire.decode(frame.copyOfRange(4, frame.size), key, BleWire.PUSH_DIRECTION) } catch (_: Exception) { throw PushError("frame does not open") }
        if (message.body != null) throw PushError("frame fields") // codec 2 carries a body: a push has none
        val value = try { StrictJson.parseObject(message.json, MAX_FRAME) } catch (_: StrictJson.Invalid) { throw PushError("frame does not open") }
        val kind = value["t"] as? String
        if (!KEYS.containsAll(value.members.keys) || (kind != "vehicle" && kind != "wake")) throw PushError("frame fields")
        if (value["s"] != session) throw PushError("frame belongs to another session")
        val counter = (value["c"] as? StrictJson.Num)?.toLongOrNull()
        if (counter == null || counter <= lastCounter) throw PushError("counter did not rise")
        val ts = (value["ts"] as? StrictJson.Num)?.toLongOrNull()
        if (ts == null || ts < 0) throw PushError("timestamp")
        if (kind == "wake") {
            if (value.has("b") || value.has("a")) throw PushError("wake frame fields")
            return PushMessage("wake", counter, ts, null, null)
        }
        val report = value["b"] as? StrictJson.Obj
        val auth = value["a"] as? String
        if (report == null || auth == null || !AUTH.matches(auth)) throw PushError("vehicle frame fields")
        if (report.has(Allowlist.NAV_KEY) && !navAllowed) throw PushError("report carries nav that the bridge did not announce")
        val body = compact(report) ?: throw PushError("report is not a vehicle body")
        if (Allowlist.vehicleBodyProblem(body).isNotEmpty()) throw PushError("report is not a vehicle body")
        return PushMessage("vehicle", counter, ts, body, auth)
    }

    /**
     * The report as compact ASCII JSON, member by member as received; null when a member is not a number, a boolean or a plain word, or a key repeats. The `nav` object (contract
     * version 2) is written the same way, one level down: flat, its words are letters, space and hyphen. [replace] maps a top-level key to the text that takes its value, and
     * [replaceNav] does the same inside `nav` (the two ages).
     */
    internal fun compact(report: StrictJson.Obj, replace: Pair<String, String>? = null, replaceNav: Map<String, String> = emptyMap()): ByteArray? {
        val out = StringBuilder()
        if (!writeObject(report, out, true, replace, replaceNav)) return null
        return out.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun writeObject(obj: StrictJson.Obj, out: StringBuilder, top: Boolean, replace: Pair<String, String>?, replaceNav: Map<String, String>): Boolean {
        if (obj.duplicateKey) return false
        out.append('{')
        var first = true
        for ((key, item) in obj.members) {
            if (key.any { it !in 'a'..'z' && it !in '0'..'9' && it != '_' }) return false
            if (!first) out.append(',')
            first = false
            out.append('"').append(key).append("\":")
            val swap = if (top) replace?.takeIf { key == it.first }?.second else replaceNav[key]
            when {
                swap != null -> out.append(swap)
                top && key == Allowlist.NAV_KEY && item is StrictJson.Obj -> { if (!writeObject(item, out, false, null, replaceNav)) return false }
                item is Boolean -> out.append(item.toString())
                item is StrictJson.Num -> out.append(item.text)
                item is String && top && item.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' } -> out.append('"').append(item).append('"')
                item is String && !top && item.all { it in 'a'..'z' || it in 'A'..'Z' || it == ' ' || it == '-' } -> out.append('"').append(item).append('"')
                else -> return false
            }
        }
        out.append('}')
        return true
    }
}

/**
 * The freshness rule of the car position on the Bluetooth path (docs/dev/bluetooth-link.md section 5.6). The X10 app counts a position's age from its arrival at
 * `127.0.0.1:8765` plus `age_ms`, so a frame that waited in BlueZ, the controller or this process would look fresh. For every frame: `d = rx - ts` is the unloaded
 * path delay plus the offset between the two clocks; `late = d - min(d)` over the last [window] frames of this connection (the first sample is the wake frame sent
 * right after `push-enable`, when the lane is idle); `dwell` is the time from `rx` to the POST. A frame whose `late + dwell` exceeds [dropOverMs] is dropped; else
 * its `age_ms` grows by that sum. Both clocks are used only as differences, so no clock synchronization is needed. Reference: `tools/btlab/contract/fresh.py`.
 */
internal class FreshnessFilter(private val window: Int = WINDOW, private val dropOverMs: Long = DROP_OVER_MS) {
    class Decision(val forward: Boolean, val addMs: Long, val reason: String)

    private val deltas = ArrayDeque<Long>()

    /** At every reconnect: the clock offset and the path may have changed. */
    @Synchronized fun reset() { deltas.clear() }

    /** Records one arrival and returns its `late` in milliseconds. */
    @Synchronized fun observe(rxMs: Long, tsMs: Long): Long {
        deltas.addLast(rxMs - tsMs)
        while (deltas.size > window) deltas.removeFirst()
        return deltas.last - deltas.min()
    }

    fun decide(lateMs: Long, rxMs: Long, postMs: Long): Decision {
        val add = lateMs + maxOf(0L, postMs - rxMs)
        return if (add > dropOverMs) Decision(false, add, "late") else Decision(true, add, "ok")
    }

    companion object {
        /** Frames: ten minutes at 1 Hz. A shorter window cannot see a delay that lasts longer than it does; clock drift moves the minimum by tens of milliseconds in ten minutes, in the safe direction. */
        const val WINDOW = 600
        const val DROP_OVER_MS = 1000L

        /**
         * The body with its ages raised by [addMs] (contract version 2, `age.json`): `age_ms` of the position (a report with `fix` false carries none), and `age_ms` and `mage` of the
         * `nav` object, which saturate at 60000 and 5000 and grow also without a fix (the signal has an age whether or not there is a position). A body that has nothing to age
         * comes back as it came, byte for byte. Reference: `aged` of `tools/btlab/contract/fresh.py`.
         */
        fun withAge(body: ByteArray, addMs: Long): ByteArray {
            val report = try { StrictJson.parseObject(String(body, Charsets.US_ASCII), 1024) } catch (_: StrictJson.Invalid) { throw PushError("report unreadable") }
            var position: Pair<String, String>? = null
            val age = report["age_ms"]
            if (report["fix"] != false && age != null) {
                val old = (age as? StrictJson.Num)?.let { n -> n.toLongOrNull() ?: n.toDoubleOrNull()?.takeIf { it.isFinite() && Math.abs(it) < 1e15 }?.toLong() } ?: throw PushError("age_ms")
                position = "age_ms" to (old + addMs).toString()
            }
            val navAges = HashMap<String, String>()
            val nav = report[Allowlist.NAV_KEY]
            if (nav is StrictJson.Obj) {
                for ((name, cap) in listOf("age_ms" to NAV_AGE_CAP_MS, "mage" to NAV_FRAME_AGE_CAP_MS)) {
                    val value = nav[name] ?: continue
                    val old = (value as? StrictJson.Num)?.toLongOrNull() ?: throw PushError("nav $name")
                    navAges[name] = minOf(cap, old + addMs).toString()
                }
            }
            if (position == null && navAges.isEmpty()) return body
            val changed = (position != null && report["age_ms"].toString() != position.second) ||
                navAges.any { (name, text) -> (report[Allowlist.NAV_KEY] as StrictJson.Obj)[name].toString() != text }
            if (!changed) return body
            return PushFrames.compact(report, position, navAges) ?: throw PushError("report unreadable")
        }

        /** The bounds of `nav.age_ms` and `nav.mage` (the sender saturates `mage` itself). */
        const val NAV_AGE_CAP_MS = 60_000L
        const val NAV_FRAME_AGE_CAP_MS = 5_000L
    }
}

/** One POST in flight; the newest frame goes first; an older frame that waited is dropped (after a stall that delivers five frames at once, only the newest is forwarded). */
internal class Forwarder<T : Any> {
    private var inFlight = false
    private var pending: Pair<Long, T>? = null
    @Volatile var droppedStale = 0
        private set

    /** A frame arrived. Returns the frame to POST now, or null (it is kept as the newest pending one). */
    @Synchronized fun offer(rxMs: Long, item: T): Pair<Long, T>? {
        if (inFlight) {
            if (pending != null) droppedStale++
            pending = rxMs to item
            return null
        }
        inFlight = true
        return rxMs to item
    }

    /** The POST finished. Returns the newest pending frame to POST next, if any. */
    @Synchronized fun done(): Pair<Long, T>? {
        val next = pending
        pending = null
        if (next == null) inFlight = false
        return next
    }
}

/**
 * The `push-enable` request (a SMALL request of the lane). A bridge that announced `pushNav` in its health answer is told that this phone forwards the beacon's `nav`
 * object, and a bridge that announced `maps` is told that this phone carries the map shapes (contract version 3): the request body is `{"nav":true}`, `{"maps":true}` or
 * `{"nav":true,"maps":true}` (codec 0: the base64 `body` member), the exact bytes of `announce_body` of `tools/btlab/contract/mapjobs.py`. `maps` is announced only to a bridge that said
 * `maps`: a bridge of version 2 reads any body but `{"nav":true}` as no announcement at all and would drop the nav announcement with it. A bridge that announced neither gets the
 * request it always got, with no body.
 */
internal object PushEnable {
    const val PATH = "/_bridge/push/enable"
    const val NAV_BODY = "{\"nav\":true}"
    const val MAPS_BODY = "{\"maps\":true}"
    const val NAV_MAPS_BODY = "{\"nav\":true,\"maps\":true}"

    /** The announcement of a phone, or null for none. */
    fun body(announceNav: Boolean, announceMaps: Boolean): String? = when {
        announceNav && announceMaps -> NAV_MAPS_BODY
        announceMaps -> MAPS_BODY
        announceNav -> NAV_BODY
        else -> null
    }

    fun request(announceNav: Boolean, announceMaps: Boolean = false): org.json.JSONObject {
        val request = laneRequest("POST", PATH)
        body(announceNav, announceMaps)?.let { request.put("body", java.util.Base64.getEncoder().encodeToString(it.toByteArray(Charsets.US_ASCII))) }
        return request
    }
}

/** The part of a connection that [VehiclePush] needs; `BleTransport` implements it. */
internal interface PushLink {
    val pushKey: ByteArray
    val pushSession: String
    /** Switches the notifications of the push characteristic on (the descriptor write) and returns when it is written; fails with an [IOException]. */
    fun subscribe()
    /**
     * `POST /_bridge/push/enable` through the lane (a SMALL request); returns the HTTP status. When [carriesNav] is true the body is `{"nav":true}`: this phone forwards the beacon's
     * `nav` object and the bridge may send it.
     */
    fun pushEnable(): Int
    /** The bridge announced `pushNav` in its health answer: the frames may carry `nav`, and `push-enable` says that this phone takes it. Read flags only, never `protocol`. */
    val carriesNav: Boolean get() = false
}

/**
 * The receiving end of the push channel on the X10 (docs/dev/bluetooth-link.md sections 5.5, 5.6, 6): subscribes, sends `push-enable` after every handshake,
 * reassembles the notification packets, opens the frames, applies the freshness rule and POSTs the newest fresh vehicle report to the X10's own route API
 * (`127.0.0.1:8765`, one POST in flight). A `wake` frame ("a job is waiting") starts a job poll. A frame that does not pass is dropped and counted; nothing is
 * forwarded that the allow-list does not accept, and nothing is logged that holds a token or a coordinate.
 *
 * One instance per connection: the counter, the assembler and the freshness window start empty after every reconnect.
 *
 * @param post POSTs one body with the token and returns the HTTP status (an exception counts as a failed POST).
 * @param clock monotonic milliseconds (`SystemClock.elapsedRealtime`); tests inject their own.
 */
internal class VehiclePush(
    private val link: PushLink,
    private val post: (ByteArray, String) -> Int = LoopbackVehiclePoster()::post,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val worker: Executor = Executors.newSingleThreadExecutor { task -> Thread(task, "SidecarBLE-Push").apply { isDaemon = true } },
    private val onWake: () -> Unit = {},
    private val warn: (String) -> Unit = { Log.w("StarPilotSidecarBLE", it) },
    private val filter: FreshnessFilter = FreshnessFilter(),
) : Closeable {
    private class Frame(val rx: Long, val late: Long, val body: ByteArray, val auth: String)

    private val assembler = PushFrames.Assembler()
    private val forwarder = Forwarder<Frame>()
    @Volatile private var closed = false
    private var lastCounter = 0L
    private val counts = Array(Stat.values().size) { AtomicInteger() }

    private enum class Stat { FRAMES, WAKES, FORWARDED, REFUSED_BY_X10, POST_FAILED, DROPPED_LATE, BAD }

    /** Counters for the status page and the tests. */
    val framesOpened get() = counts[Stat.FRAMES.ordinal].get()
    val wakes get() = counts[Stat.WAKES.ordinal].get()
    val forwarded get() = counts[Stat.FORWARDED.ordinal].get()
    val refusedByX10 get() = counts[Stat.REFUSED_BY_X10.ordinal].get()
    val postFailed get() = counts[Stat.POST_FAILED.ordinal].get()
    val droppedLate get() = counts[Stat.DROPPED_LATE.ordinal].get()
    val droppedBad get() = counts[Stat.BAD.ordinal].get()
    val droppedStale get() = forwarder.droppedStale
    @Volatile var bound = false
        private set

    /** Notifications on, then `push-enable`, after EVERY handshake: a new INFO read drops the comma's binding. Returns whether comma bound the push to this session. */
    fun enable(): Boolean {
        filter.reset()
        link.subscribe()
        val status = try { link.pushEnable() } catch (error: IOException) {
            warn("Push enable failed (${error.javaClass.simpleName})")
            bound = false
            return false
        }
        bound = status == 200
        if (!bound) warn("Push not bound (status=$status)")
        return bound
    }

    /** One notification of the push characteristic. Called from the Bluetooth callback thread: quick, no blocking. */
    fun onPacket(packet: ByteArray) {
        val rx = clock()
        if (closed) return
        try {
            val frame = assembler.add(packet) ?: return
            val message = PushFrames.open(frame, link.pushKey, link.pushSession, lastCounter, link.carriesNav)
            lastCounter = message.counter
            counts[Stat.FRAMES.ordinal].incrementAndGet()
            val late = filter.observe(rx, message.tsMs)
            if (message.kind == "wake") {
                counts[Stat.WAKES.ordinal].incrementAndGet()
                onWake()
                return
            }
            val next = forwarder.offer(rx, Frame(rx, late, message.body!!, message.auth!!)) ?: return
            worker.execute { drain(next.second) }
        } catch (_: PushError) {
            if (counts[Stat.BAD.ordinal].incrementAndGet() == 1) warn("Push frame refused")
        } catch (_: java.util.concurrent.RejectedExecutionException) { /* closed */ }
    }

    private fun drain(first: Frame) {
        var frame: Frame? = first
        while (frame != null && !closed) {
            try { forward(frame) } catch (error: Exception) { counts[Stat.POST_FAILED.ordinal].incrementAndGet() }
            frame = forwarder.done()?.second
        }
    }

    private fun forward(frame: Frame) {
        val now = clock()
        val decision = filter.decide(frame.late, frame.rx, now)
        if (!decision.forward) { counts[Stat.DROPPED_LATE.ordinal].incrementAndGet(); return }
        val body = try { FreshnessFilter.withAge(frame.body, decision.addMs) } catch (_: PushError) { counts[Stat.BAD.ordinal].incrementAndGet(); return }
        if (!Allowlist.check("POST", "/v1/vehicle", body).ok) { counts[Stat.BAD.ordinal].incrementAndGet(); return }
        val status = post(body, frame.auth)
        counts[(if (status in 200..299) Stat.FORWARDED else Stat.REFUSED_BY_X10).ordinal].incrementAndGet()
    }

    override fun close() {
        closed = true
        (worker as? java.util.concurrent.ExecutorService)?.shutdown()
    }
}

/** The report goes to the X10's own route API and nowhere else: `POST /v1/vehicle` on loopback, a fresh connection, no redirect, short bounds. */
internal class LoopbackVehiclePoster(private val port: Int = 8765) {
    fun post(body: ByteArray, token: String): Int {
        val connection = URL("http://127.0.0.1:$port/v1/vehicle").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 1_000
            connection.readTimeout = 1_500
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Connection", "close")
            connection.outputStream.use { it.write(body) }
            return connection.responseCode
        } finally { connection.disconnect() }
    }
}
