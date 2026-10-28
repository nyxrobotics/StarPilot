package link.firestar.companion.twin

import link.firestar.companion.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.net.Socket
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The Companion's Bluetooth link without Android, for the BLE-less twin of the PC (docs/dev/bluetooth-twin.md): the REAL lane (`BleLane`), exchange (`BleExchange`: framing,
 * sealing, packets), job worker (`SidecarRouteWorker` with `SidecarJobs`), push receiver (`VehiclePush`), reconnect schedule (`ReconnectBackoff`) and local HTTP clients
 * (`LoopbackFetcher`, `LoopbackVehiclePoster`), joined to the twin (Python) by a line protocol: every GATT operation is a request to the twin, which gives it the time of
 * the measured regime, may stall, cut or damage it, and hands it to the real bridge. What is NOT real here, and so not tested by the twin: Android's GATT stack and
 * `BleTransport` itself (its callbacks, the scan, the descriptor write); `TwinTransport` below repeats its logic (connect, handshake, the exchange that closes the link
 * when a request fails) without those classes.
 *
 * Started by the twin (`java -cp <test classpath> link.firestar.companion.twin.TwinMain <port>`); it exits when the twin closes the connection.
 */
object TwinMain {
    @JvmStatic fun main(args: Array<String>) {
        val socket = Socket("127.0.0.1", args[0].toInt())
        TwinClient(socket).run()
    }
}

/** One connection to the twin: requests of the harness (answered by `res`), commands of the twin (answered by `cmdres`), notifications. */
internal class TwinClient(private val socket: Socket) {
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
    private val writer = socket.getOutputStream()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JSONObject>>()
    private val ids = AtomicLong(1)
    private val commands = Executors.newCachedThreadPool { Thread(it, "twin-command").apply { isDaemon = true } }
    @Volatile var notifySink: ((ByteArray) -> Unit)? = null
    private val companion = TwinCompanion(this)

    @Synchronized fun send(message: JSONObject) {
        writer.write((message.toString() + "\n").toByteArray(Charsets.US_ASCII))
        writer.flush()
    }

    /** One GATT operation: blocks until the twin has given it its time. A failure of the link or of the bridge is an [IOException]. */
    fun call(op: String, build: JSONObject.() -> Unit = {}): JSONObject {
        val id = ids.getAndIncrement()
        val future = CompletableFuture<JSONObject>()
        pending[id] = future
        try {
            send(JSONObject().put("t", "rpc").put("id", id).put("op", op).apply(build))
            val answer = try { future.get(180, TimeUnit.SECONDS) } catch (e: java.util.concurrent.TimeoutException) { throw IOException("twin did not answer") }
            if (answer.has("err")) throw IOException("${answer.getString("err")}: ${answer.optString("msg")}")
            return answer
        } finally { pending.remove(id) }
    }

    fun run() {
        send(JSONObject().put("t", "hello"))
        while (true) {
            val line = reader.readLine() ?: break
            val message = JSONObject(line)
            when (message.getString("t")) {
                "res" -> pending[message.getLong("id")]?.complete(message)
                "notify" -> notifySink?.invoke(TwinCompanion.hex(message.getString("hex")))
                "cmd" -> commands.execute {
                    val id = message.getLong("id")
                    val answer = try { companion.command(message).put("ok", true) } catch (e: Throwable) { JSONObject().put("ok", false).put("error", "${e.javaClass.simpleName}: ${e.message}") }
                    send(answer.put("t", "cmdres").put("id", id))
                }
            }
        }
        companion.stop()
        System.exit(0)
    }
}

/** The link of one connection: the logic of `BleTransport` (connect and handshake, one exchange at a time, a failed exchange closes the link) over the twin's operations. */
/** Every chunk request this process sent: "sequence:bytes:first 4 bytes of the SHA-256" (evidence for the resume rows). */
internal val chunkLog = java.util.concurrent.CopyOnWriteArrayList<String>()
/** Every burst the Companion's meter saw: "wall ms at the end:body bytes:seconds" (evidence for the meter comparison with the bridge's `up_kb_s`). */
internal val burstLog = java.util.concurrent.CopyOnWriteArrayList<String>()

internal class TwinTransport(private val rpc: TwinClient, private val key: ByteArray, private val legacy: Boolean, private val ignorePushNav: Boolean, private val sabotage: String, private val onClosed: (TwinTransport) -> Unit) : RouteLink, Closeable {
    @Volatile var closed = false
    @Volatile private var session = ""
    private var counter = 0L
    private var packetSize = 20
    @Volatile var features: BridgeFeatures = BridgeFeatures.LEGACY
    /** The Companion's own measure of its uplink (`BleTransport.uplink`), fed by the burst of the packets sent without a response, as the real transport does. */
    val uplink = UplinkMeter()
    val chunksSent: MutableList<String> = chunkLog
    @Volatile var canWriteNoResponse = false
    @Volatile var hasPushCharacteristic = false
    @Volatile var pushSink: ((ByteArray) -> Unit)? = null
    val open get() = !closed && session.isNotEmpty()
    val connectedAtMs = System.nanoTime() / 1_000_000

    private val laneLink = object : LaneLink {
        override fun exchange(meta: JSONObject, body: ByteArray?, deadlineSeconds: Long) = this@TwinTransport.exchange(meta, body, deadlineSeconds)
        override fun control(action: String) = this@TwinTransport.control(action)
        override fun setHighPriority(on: Boolean) = this@TwinTransport.setHighPriority(on)
        override fun close() = this@TwinTransport.close()
    }
    val lane = BleLane(laneLink)
    val jobsV2: Boolean get() = features.jobsV2 && open
    /** `BleTransport.announcesMaps`: the bridge said `maps` (and speaks the jobs of version 2). */
    val announcesMaps: Boolean get() = features.announcesMaps
    val preferXz: Boolean get() = features.xz && !canWriteNoResponse

    private val io = object : GattIo {
        override fun write(packet: ByteArray, noResponse: Boolean) { rpc.call("write") { put("hex", TwinCompanion.hex(packet)); put("noResp", noResponse) } }
        override fun read(): ByteArray = TwinCompanion.hex(rpc.call("read_tx").getString("hex"))
        override fun pause(millis: Long) = Thread.sleep(millis)
    }

    /** Connects, reads the challenge and authenticates with the health call, as `BleTransport.connect` does; nothing of the push channel yet. */
    fun connect() {
        try {
            val link = rpc.call("connect")
            packetSize = ((link.optInt("mtu", 247)) - 3).coerceIn(20, 244)
            canWriteNoResponse = link.optBoolean("wwr")
            hasPushCharacteristic = link.optBoolean("push")
            val info = TwinCompanion.hex(rpc.call("read_info").getString("hex"))
            require(info.size == 17 && info[0].toInt() == 1)
            session = info.drop(1).joinToString("") { "%02x".format(it.toInt() and 255) }
            val health = JSONObject().put("method", "GET").put("path", "/_bridge/health")
            val response = try { exchange(health, null, 20) } catch (e: ResponseTimeout) { throw IOException("comma did not recognize this phone") }
            if (response.getInt("status") != 200) throw IOException("authentication failed")
            val read = if (legacy) BridgeFeatures.LEGACY else try { BridgeFeatures.parse(Base64.getDecoder().decode(response.optString("body", ""))) } catch (_: IllegalArgumentException) { BridgeFeatures.LEGACY }
            // a Companion of job C (1.1.39) does not know pushNav: it reads the other flags only
            features = if (ignorePushNav) BridgeFeatures(read.protocol, read.poll2, read.results2, read.push, read.xz, false) else read
        } catch (e: Exception) { close(); throw e }
    }

    @Synchronized fun exchange(meta: JSONObject, body: ByteArray?, deadlineSeconds: Long): JSONObject {
        check(!closed && session.isNotEmpty())
        val id = UUID.randomUUID().toString()
        val count = ++counter
        try {
            val (sentMeta, sentBody) = damaged(meta, body)
            val path = sentMeta.optString("path")
            if (sentBody != null && path.contains("/chunk/")) {
                val sequence = path.substringAfter("/chunk/").substringAfter('/').substringBefore('?')
                val sha = java.security.MessageDigest.getInstance("SHA-256").digest(sentBody).take(4).joinToString("") { "%02x".format(it.toInt() and 255) }
                chunksSent.add("$sequence:${sentBody.size}:$sha")
            }
            return BleExchange.run(io, key, session, count, id, sentMeta, sentBody, deadlineSeconds, packetSize, canWriteNoResponse, onBurst = { bodyBytes, seconds ->
                uplink.observe(bodyBytes, seconds)
                burstLog.add("${System.currentTimeMillis()}:$bodyBytes:${"%.6f".format(java.util.Locale.ROOT, seconds)}")
            })
        } catch (e: Exception) { close(); throw e }
    }

    /**
     * A Companion that does something wrong with the LAST chunk of a job, to see that the comma refuses it (rows S-*: the comma's own rules of section 5.2 are what is tested):
     * a wrong digest, a wrong size, another status or encoding than the earlier chunks declared, one byte more, one byte less. Nothing is damaged without [sabotage].
     */
    private fun damaged(meta: JSONObject, body: ByteArray?): Pair<JSONObject, ByteArray?> {
        val path = meta.optString("path")
        if (sabotage.isEmpty() || body == null || !path.contains("/chunk/") || !path.contains("last=1")) return meta to body
        val copy = JSONObject(meta.toString())
        return when (sabotage) {
            "digest" -> copy.put("path", path.replace(Regex("sha256=([0-9a-f])"), { m -> "sha256=" + (if (m.groupValues[1] == "0") "1" else "0") })) to body
            "size" -> copy.put("path", path.replace(Regex("size=([0-9]+)"), { m -> "size=" + (m.groupValues[1].toLong() + 1) })) to body
            "status" -> copy.put("path", path.replace("status=200", "status=204")) to body
            "encoding" -> copy.put("path", path.replace("encoding=gzip", "encoding=identity").replace("encoding=xz", "encoding=identity")) to body
            "trailing" -> copy to (body + byteArrayOf(0))
            "truncate" -> copy to body.copyOf(maxOf(0, body.size - 1))
            else -> throw IllegalArgumentException("unknown sabotage")
        }
    }

    @Synchronized fun control(action: String): JSONObject {
        check(!closed)
        try {
            when (action) {
                "subscribe" -> rpc.call("subscribe")
                else -> throw IOException("unknown link operation")
            }
            return JSONObject().put("status", 200)
        } catch (e: Exception) { close(); throw e }
    }

    /** Not synchronized: a request for the priority never waits for the exchange that runs. */
    fun setHighPriority(high: Boolean) {
        if (closed) return
        try { rpc.call("priority") { put("high", high) } } catch (_: Exception) { /* a refused change is no failure of the link */ }
    }

    override fun request(value: JSONObject, deadlineSeconds: Long): JSONObject = lane.call(LaneClass.INTERACTIVE, deadlineSeconds, value)
    override fun request(value: JSONObject, deadlineSeconds: Long, cls: LaneClass): JSONObject = lane.call(cls, deadlineSeconds, value)
    override fun routePriority(high: Boolean) { if (high) lane.wantHigh() }

    val pushLink = object : PushLink {
        override val pushKey: ByteArray get() = key
        override val pushSession: String get() = session
        override fun subscribe() { lane.control(LaneClass.SMALL, 12, "subscribe") }
        override val carriesNav: Boolean get() = features.pushNav
        override fun pushEnable(): Int = lane.call(LaneClass.SMALL, 10, PushEnable.request(features), label = "push-enable").optInt("status")
    }

    override fun close() {
        if (closed) return
        closed = true
        pushSink = null
        lane.close()
        try { rpc.send(JSONObject().put("t", "closed").put("why", "request failed or companion closed")) } catch (_: Exception) {}
        onClosed(this)
    }
}

/** What the Companion does on the X10 around one link: reconnect schedule, the push channel and the first job poll after a handshake, the job worker, the guidance poll. */
internal class TwinCompanion(private val rpc: TwinClient) {
    private var key = ByteArray(0)
    private var legacy = false
    private var routePort = 0
    private var vehiclePort = 0
    private var token = ""
    @Volatile private var current: TwinTransport? = null
    private var worker: SidecarRouteWorker? = null
    private var push: VehiclePush? = null
    @Volatile private var running = false
    private var loop: Thread? = null
    private var guidance: Thread? = null
    private val backoff = ReconnectBackoff()
    private val connects = AtomicInteger()
    private val reconnectLog = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
    private val warnings = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val guidanceStats = longArrayOf(0, 0, 0, 0) // polls, failures, max ms, last ms
    private val laneMax = ConcurrentHashMap<String, LongArray>() // class -> (count, max wait ms, max service ms)
    private val linkExecutor = Executors.newSingleThreadExecutor { Thread(it, "twin-link").apply { isDaemon = true } }
    private var lastCloseAtMs = 0L
    @Volatile private var pushBoundEpochMs = 0L
    @Volatile private var ignorePushNav = false
    @Volatile private var sabotage = ""

    fun command(message: JSONObject): JSONObject = when (message.getString("cmd")) {
        "start" -> start(message.getJSONObject("cfg"))
        "connect" -> connect()
        "stats" -> stats(message.optBoolean("bursts", false))
        "guidance" -> guidance(message.optBoolean("on", true), message.optLong("periodMs", 3000))
        "close_link" -> { current?.close(); JSONObject() }
        "stop" -> { stop(); JSONObject() }
        else -> throw IllegalArgumentException("unknown command")
    }

    private fun start(cfg: JSONObject): JSONObject {
        stop()
        key = hex(cfg.getString("keyHex"))
        legacy = cfg.optBoolean("legacy")
        ignorePushNav = cfg.optBoolean("ignorePushNav")
        sabotage = cfg.optString("sabotage", "")
        routePort = cfg.getInt("routePort")
        vehiclePort = cfg.getInt("vehiclePort")
        token = cfg.optString("token", "X10-route-link-example-key-2026")
        warnings.clear(); laneMax.clear(); reconnectLog.clear(); connects.set(0); chunkLog.clear(); burstLog.clear()
        worker = SidecarRouteWorker(
            transport = { current?.takeIf { it.open } },
            autoStart = true,
            jobsLane = { current?.takeIf { it.jobsV2 }?.lane },
            preferXz = { current?.preferXz == true },
            fetcher = LoopbackFetcher(routePort),
            mapsAnnounced = { current?.takeIf { it.open }?.announcesMaps == true },
        ).apply {
            routeFetcher = { target -> fetch(target, routePort) }
            warning = { warnings += it }
        }
        running = true
        return JSONObject()
    }

    /** The first connection, now; then the guidance service's reconnect loop takes over. */
    private fun connect(): JSONObject {
        val started = System.nanoTime()
        connectOnce()
        if (loop == null) {
            loop = Thread({ reconnectLoop() }, "twin-reconnect").apply { isDaemon = true; start() }
        }
        return JSONObject().put("connectMs", (System.nanoTime() - started) / 1_000_000)
    }

    private fun connectOnce() {
        val transport = TwinTransport(rpc, key, legacy, ignorePushNav, sabotage) { closed -> if (current === closed) { lastCloseAtMs = System.nanoTime() / 1_000_000 } }
        transport.lane.observer = { e ->
            val row = laneMax.computeIfAbsent(e.cls.name) { LongArray(3) }
            synchronized(row) {
                row[0]++
                row[1] = maxOf(row[1], (e.waitSeconds * 1000).toLong()); row[2] = maxOf(row[2], (e.serviceSeconds * 1000).toLong())
            }
        }
        transport.connect()
        val previous = current
        current = transport
        previous?.close()
        rpc.notifySink = { packet -> transport.pushSink?.invoke(packet) }
        connects.incrementAndGet()
        reconnectLog += JSONObject().put("atMs", System.nanoTime() / 1_000_000).put("downMs", if (lastCloseAtMs == 0L) 0 else System.nanoTime() / 1_000_000 - lastCloseAtMs)
        linkExecutor.execute { startSidecarLink(transport) }
    }

    /** After every handshake (NavigationGuidanceService.startSidecarLink): a first job poll, and the push channel when the bridge has it. */
    private fun startSidecarLink(transport: TwinTransport) {
        if (!running || !transport.open) return
        if (transport.jobsV2) worker?.wake()
        if (!transport.features.push || !transport.hasPushCharacteristic) return
        val receiver = VehiclePush(transport.pushLink, post = LoopbackVehiclePoster(vehiclePort)::post, clock = { System.nanoTime() / 1_000_000 }, onWake = { worker?.wake() }, warn = { warnings += it })
        synchronized(this) { push?.close(); push = receiver }
        transport.pushSink = receiver::onPacket
        try { receiver.enable(); if (receiver.bound) pushBoundEpochMs = System.currentTimeMillis() } catch (e: Exception) {
            warnings += "push channel unavailable (${e.javaClass.simpleName})"
            transport.pushSink = null
        }
    }

    /** NavigationGuidanceService's tick: once a second while the link is down, the reconnect schedule decides when to try. */
    private fun reconnectLoop() {
        while (running) {
            try {
                Thread.sleep(1000)
                if (current?.open != true) {
                    val now = System.nanoTime() / 1_000_000
                    if (backoff.ready(now)) {
                        backoff.attempt(now)
                        try { connectOnce(); backoff.succeeded() } catch (_: Exception) { backoff.failed() }
                    }
                }
            } catch (_: InterruptedException) { return }
        }
    }

    private fun guidance(on: Boolean, periodMs: Long): JSONObject {
        guidance?.interrupt(); guidance = null
        if (on) {
            guidance = Thread({
                while (running) {
                    try {
                        Thread.sleep(periodMs)
                        val link = current?.takeIf { it.open }
                        if (link == null) { Thread.sleep(200); continue }
                        val started = System.nanoTime()
                        try {
                            val request = JSONObject().put("method", "GET").put("path", "/api/navigation").put("headers", JSONObject().put("Authorization", "Bearer $token"))
                            val answer = link.request(request, 20)
                            val ms = (System.nanoTime() - started) / 1_000_000
                            synchronized(guidanceStats) { guidanceStats[0]++; guidanceStats[3] = ms; guidanceStats[2] = maxOf(guidanceStats[2], ms); if (answer.optInt("status") != 200) guidanceStats[1]++ }
                        } catch (_: IOException) { synchronized(guidanceStats) { guidanceStats[1]++ } }
                    } catch (_: InterruptedException) { return@Thread }
                }
            }, "twin-guidance").apply { isDaemon = true; start() }
        }
        return JSONObject()
    }

    private fun stats(withBursts: Boolean = false): JSONObject {
        val out = JSONObject()
        val link = current
        out.put("chunksSent", JSONArray(chunkLog.toList())).put("pushBoundEpochMs", pushBoundEpochMs).put("nowEpochMs", System.currentTimeMillis()).put("open", link?.open == true).put("connects", connects.get()).put("features", link?.features?.let { JSONObject().put("jobsV2", it.jobsV2).put("push", it.push).put("pushNav", it.pushNav).put("xz", it.xz).put("maps", it.maps).put("wwr", it.wwr).put("upKbS", it.upKbS ?: JSONObject.NULL).put("announcesMaps", it.announcesMaps) } ?: JSONObject.NULL)
            .put("uplinkKbS", link?.uplink?.upKbS ?: JSONObject.NULL).apply { if (withBursts) put("bursts", JSONArray(burstLog.toList())) }
        out.put("canWriteNoResponse", link?.canWriteNoResponse ?: false)
        val jobs = try { val f = SidecarRouteWorker::class.java.getDeclaredField("jobs"); f.isAccessible = true; f.get(worker) as? SidecarJobs } catch (_: Exception) { null }
        out.put("jobs", JSONObject().put("done", jobs?.jobsDone ?: 0).put("failed", jobs?.jobsFailed ?: 0).put("refused", jobs?.jobsRefused ?: 0).put("lost", jobs?.jobsLost ?: 0).put("held", jobs?.heldTransfers ?: 0))
        val p = push
        out.put("push", if (p == null) JSONObject.NULL else JSONObject().put("frames", p.framesOpened).put("wakes", p.wakes).put("forwarded", p.forwarded).put("refusedByX10", p.refusedByX10)
            .put("postFailed", p.postFailed).put("droppedLate", p.droppedLate).put("droppedBad", p.droppedBad).put("droppedStale", p.droppedStale).put("bound", p.bound))
        synchronized(guidanceStats) { out.put("guidance", JSONObject().put("polls", guidanceStats[0]).put("failures", guidanceStats[1]).put("maxMs", guidanceStats[2]).put("lastMs", guidanceStats[3])) }
        val lane = JSONObject()
        for ((cls, row) in laneMax) synchronized(row) { lane.put(cls, JSONObject().put("count", row[0]).put("maxWaitMs", row[1]).put("maxServiceMs", row[2])) }
        out.put("lane", lane)
        out.put("reconnects", JSONArray(reconnectLog.map { it }))
        out.put("warnings", JSONArray(warnings.toList().takeLast(30)))
        return out
    }

    fun stop() {
        running = false
        guidance?.interrupt(); guidance = null
        loop?.interrupt(); loop = null
        synchronized(this) { push?.close(); push = null }
        worker?.close(); worker = null
        current?.close(); current = null
        rpc.notifySink = null
    }

    companion object {
        fun hex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
