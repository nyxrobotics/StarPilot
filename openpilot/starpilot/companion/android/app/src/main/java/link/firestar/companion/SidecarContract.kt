package link.firestar.companion

import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.SingleXZInputStream
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater

/** The peer broke the contract of the Bluetooth link; the message is ours (never an echo of what the peer sent). */
internal class ContractError(message: String) : IllegalArgumentException(message)

/** The four request shapes that may cross the link from comma to the X10 route API. */
internal enum class Shape(val wire: String) { ROUTE("route"), STATUS("status"), HEALTH("health"), VEHICLE("vehicle") }

/**
 * The allow-list (docs/dev/bluetooth-link.md section 5.1): exactly four request shapes, one rule, enforced at the comma entrance, here before anything touches
 * `127.0.0.1:8765`, and by the route API itself. The JSON vectors of `tools/fixtures/btlab/contract/allowlist.json` (version 2, 273 cases) are the test of this port.
 *
 *     route    GET  /directions/v5/mapbox/driving-traffic/<coordinates>?<query>
 *     status   GET  /status
 *     health   GET  /health
 *     vehicle  POST /v1/vehicle   (one JSON object of at most 512 bytes; since contract version 2 it may carry the beacon's `nav` object)
 */
internal object Allowlist {
    const val ROUTE_PREFIX = "/directions/v5/mapbox/driving-traffic/"
    const val MAX_TARGET_BYTES = 8192
    const val MAX_SEGMENT_CHARS = 256
    const val MAX_VEHICLE_BODY = 512
    private const val MAX_WORD = 16
    const val NAV_KEY = "nav"
    private const val NAV_MAX_AGE_MS = 60_000L
    private const val NAV_MAX_DISTANCE_M = 100_000L
    private const val NAV_MAX_FRAME_AGE_MS = 5_000L
    private const val NAV_MAX_KEYS = 16
    private val NAV_SIGNALS = setOf("left", "right", "off", "both")
    private val NAV_TURN_STATES = setOf("off", "stop", "lat", "fast", "none", "far", "wait", "blind", "on")
    /** `mt`, `mm`: letters, space and hyphen, at most 24 characters. */
    private val NAV_LABEL = Regex("[A-Za-z][A-Za-z -]{0,23}")
    /** The name of a key of `nav` that this contract does not know: ignored by every receiver, carried only when it is this small and its value is flat. */
    private val NAV_OTHER_KEY = Regex("[a-z][a-z0-9_]{0,15}")
    private val NAV_ONLY_TRUE = setOf("seen", "tight", "resume")
    private val NAV_GROUP = listOf("mt", "mm", "md", "mage")
    private val WORD_KEYS = setOf("src", "hdg")
    /** The keys of the beacon contract (`BODY_KEYS` of the comma's `sidecar_beacon.py`); a report with any other key is not carried. */
    val VEHICLE_KEYS = setOf("v", "seq", "fix", "lat", "lon", "bearing", "speed", "acc", "age_ms", "src", "vego", "dr_s", "hdg", "hdg_sig", "hold", "prev", "snap_m")

    // One coordinate: an optionally signed decimal; an exponent only because Python's float text can print one for tiny values.
    private const val COORD = "[+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"
    private val SEGMENT = Regex("$COORD,$COORD(?:;$COORD,$COORD)+")

    class Verdict(val ok: Boolean, val shape: Shape?, val reason: String)

    private fun refuse(reason: String) = Verdict(false, null, reason)

    /** The verdict on one request; [target] is the request target as it would be written on the HTTP request line. */
    fun check(method: String?, target: String?, body: ByteArray = ByteArray(0)): Verdict {
        if (method != "GET" && method != "POST") return refuse("method")
        if (target == null || target.isEmpty()) return refuse("target type")
        if (target.length > MAX_TARGET_BYTES || target.toByteArray(Charsets.UTF_8).size > MAX_TARGET_BYTES) return refuse("target too long")
        if (target.any { it < '!' || it > '~' }) return refuse("character outside printable ASCII")
        if ('#' in target || '\\' in target) return refuse("fragment or backslash")
        if (!target.startsWith("/") || target.startsWith("//")) return refuse("not an origin-form path")
        val question = target.indexOf('?')
        val path = if (question < 0) target else target.substring(0, question)
        val hasQuery = question >= 0
        val query = if (hasQuery) target.substring(question + 1) else ""
        if (path.startsWith(ROUTE_PREFIX)) {
            if (method != "GET" || body.isNotEmpty()) return refuse("route is a bodiless GET")
            val segment = path.substring(ROUTE_PREFIX.length)
            if (segment.isEmpty() || segment.length > MAX_SEGMENT_CHARS || !SEGMENT.matches(segment)) return refuse("route segment")
            if (query.isEmpty()) return refuse("route needs a query")
            return Verdict(true, Shape.ROUTE, "")
        }
        if (path == "/status" || path == "/health") {
            if (method != "GET" || body.isNotEmpty() || hasQuery) return refuse("status and health are bodiless GETs without a query")
            return Verdict(true, if (path == "/status") Shape.STATUS else Shape.HEALTH, "")
        }
        if (path == "/v1/vehicle") {
            if (method != "POST" || hasQuery) return refuse("vehicle is a POST without a query")
            val reason = vehicleBodyProblem(body)
            return if (reason.isNotEmpty()) refuse(reason) else Verdict(true, Shape.VEHICLE, "")
        }
        return refuse("path")
    }

    /** "" for a body that the vehicle shape may carry, else the first reason: one JSON object of printable ASCII, at most 512 bytes, only contract keys, flat but for the `nav` object, finite numbers. */
    fun vehicleBodyProblem(body: ByteArray): String {
        if (body.size !in 1..MAX_VEHICLE_BODY) return "vehicle body size"
        if (body.any { it > 0x7E || it < 0x20 }) return "vehicle body is not printable ASCII"
        val value = try { StrictJson.parseObject(String(body, Charsets.US_ASCII)) } catch (_: StrictJson.Invalid) { return "vehicle body is not a clean JSON object" }
        if (value.duplicateKey) return "vehicle body is not a clean JSON object"
        if (value.members.isEmpty()) return "vehicle body is not an object"
        val version = value["v"]
        if (version !is StrictJson.Num || !version.isInteger || version.text != "1") return "vehicle body version"
        for ((key, item) in value.members) {
            if (key == NAV_KEY) {
                val reason = navProblem(item)
                if (reason.isNotEmpty()) return reason
                continue
            }
            if (key !in VEHICLE_KEYS) return "vehicle body key"
            if (key in WORD_KEYS) {
                if (item is String && item.length in 1..MAX_WORD && item.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }) continue
                return "vehicle body word"
            }
            when (item) {
                is Boolean -> continue
                is StrictJson.Num -> {
                    if (item.isInteger) continue
                    val number = item.toDoubleOrNull()
                    if (number == null || number.isNaN() || number.isInfinite()) return "vehicle body number"
                    continue
                }
                else -> return "vehicle body value"
            }
        }
        return ""
    }

    private fun integerIn(item: Any?, low: Long, high: Long): Boolean = item is StrictJson.Num && item.toLongOrNull()?.let { it in low..high } == true

    /**
     * "" for a `nav` object that the vehicle shape may carry, else the first reason (contract version 2; the reference is `nav_problem` of `tools/btlab/contract/allowlist.py`).
     * `v` is the integer 1 and `age_ms` an integer 0..60000 (both required); the known keys have exactly the types and bounds of the beacon's nav contract; `mt`, `mm`, `md` and
     * `mage` come together or not at all; a key the contract does not know is ignored (a later sender's key never costs the position) and carried only when its name is small and
     * its value is a boolean, a number or a short word; at most 16 keys; the whole body stays within the 512 bytes of the shape.
     */
    fun navProblem(nav: Any?): String {
        if (nav !is StrictJson.Obj || nav.members.isEmpty() || nav.members.size > NAV_MAX_KEYS) return "nav is not a small object"
        if (nav.duplicateKey) return "vehicle body is not a clean JSON object"
        val version = nav["v"]
        if (version !is StrictJson.Num || !version.isInteger || version.text != "1") return "nav version"
        if (!integerIn(nav["age_ms"], 0, NAV_MAX_AGE_MS)) return "nav age_ms"
        for ((key, item) in nav.members) {
            val ok = when (key) {
                "v", "age_ms" -> true
                "sig" -> item is String && item in NAV_SIGNALS
                "ta" -> item is String && item in NAV_TURN_STATES
                "lat", "ctl" -> item is Boolean
                "mt", "mm" -> item is String && NAV_LABEL.matches(item)
                "md" -> integerIn(item, 0, NAV_MAX_DISTANCE_M)
                "mage" -> integerIn(item, 0, NAV_MAX_FRAME_AGE_MS)
                in NAV_ONLY_TRUE -> item == true
                else -> {
                    if (!NAV_OTHER_KEY.matches(key)) return "nav key"
                    when (item) {
                        is Boolean -> true
                        is StrictJson.Num -> item.isInteger || item.toDoubleOrNull()?.let { it.isFinite() } == true
                        is String -> NAV_LABEL.matches(item)
                        else -> false
                    }
                }
            }
            if (!ok) return "nav value"
        }
        if (NAV_GROUP.map { nav.has(it) }.distinct().size != 1) return "nav manoeuvre group"
        return ""
    }
}

internal class ChunkTarget(val jobId: String, val sequence: Int, val last: Boolean, val status: Int, val encoding: String, val size: Int?, val sha256: String?)

/**
 * What the Companion sends to comma for a job's answer and what comma checks (docs/dev/bluetooth-link.md section 5.2). The vectors of `results.json` (34) and
 * `chunk-targets.json` (25) are the test of this port; [decodeResult] is also run on the Companion's own result before the first chunk goes out, so an encoder
 * fault ends the job here instead of being found by comma.
 */
internal object JobContract {
    val ALLOWED_STATUS = setOf(200, 204, 400, 401, 403, 404, 422, 429, 503, 504)
    val ENCODINGS = listOf("identity", "gzip", "xz")
    const val MAX_ROUTE_BYTES = 8 * 1024 * 1024
    const val MAX_SMALL_BYTES = 4096
    const val MAX_CHUNK_BYTES = 64 * 1024
    /** Decoder memory bound for `xz` in KiB: 64 MiB. A 1 MiB dictionary needs about 2 MiB, so this is generous and still bounded. */
    const val XZ_MEMLIMIT_KIB = 64 * 1024
    private val TARGET = Regex("/_bridge/sidecar/chunk/([0-9a-f]{32})/([0-9]{1,6})\\?(.*)")
    private val STATUS_DIGITS = Regex("[0-9]{3}")
    private val SIZE_DIGITS = Regex("[0-9]{1,8}")
    private val HEX64 = Regex("[0-9a-f]{64}")
    private val ID = Regex("[0-9a-f]{32}")

    fun isJobId(value: String) = ID.matches(value)

    /** The path of one chunk. [size] and [sha256] are written on the last chunk only, and then both. */
    fun chunkPath(jobId: String, sequence: Int, last: Boolean, status: Int, encoding: String, size: Int?, sha256: String?): String {
        val query = StringBuilder("last=${if (last) 1 else 0}&status=$status&encoding=$encoding")
        if (last) {
            if (size == null || sha256 == null) throw ContractError("the last chunk declares size and sha256")
            query.append("&size=$size&sha256=$sha256")
        } else if (size != null || sha256 != null) throw ContractError("only the last chunk declares size and sha256")
        val path = "/_bridge/sidecar/chunk/$jobId/$sequence?$query"
        parseChunkTarget(path) // the sender holds itself to the rule of the receiver
        return path
    }

    /** Strict parser of the chunk path: exact key set and order, no repeated key, bounded numbers. */
    fun parseChunkTarget(target: String?): ChunkTarget {
        if (target == null || target.length > 400) throw ContractError("chunk path")
        val match = TARGET.matchEntire(target) ?: throw ContractError("chunk path")
        val jobId = match.groupValues[1]
        val sequence = match.groupValues[2].toInt()
        val pairs = match.groupValues[3].split("&").map { it.split("=", limit = 2) }
        if (pairs.any { it.size != 2 }) throw ContractError("chunk query")
        val keys = pairs.map { it[0] }
        val values = pairs.associate { it[0] to it[1] }
        if (keys != listOf("last", "status", "encoding") && keys != listOf("last", "status", "encoding", "size", "sha256")) throw ContractError("chunk query keys")
        if (values["last"] !in setOf("0", "1") || !STATUS_DIGITS.matches(values.getValue("status")) || values["encoding"] !in ENCODINGS) throw ContractError("chunk query values")
        val status = values.getValue("status").toInt()
        if (status !in ALLOWED_STATUS) throw ContractError("status not carried")
        val last = values["last"] == "1"
        if (last != (keys.size == 5)) throw ContractError("size and sha256 belong to the last chunk")
        var size: Int? = null
        var digest: String? = null
        if (last) {
            if (!SIZE_DIGITS.matches(values.getValue("size")) || !HEX64.matches(values.getValue("sha256"))) throw ContractError("size or sha256")
            size = values.getValue("size").toInt()
            digest = values.getValue("sha256")
        }
        return ChunkTarget(jobId, sequence, last, status, values.getValue("encoding"), size, digest)
    }

    fun sha256Hex(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }

    /** The decoded body of a finished job, or [ContractError]. Every check of the contract in one place. */
    fun decodeResult(kind: String, status: Int, encoding: String, payload: ByteArray, size: Int, sha256: String): ByteArray {
        if (status !in ALLOWED_STATUS) throw ContractError("status not carried")
        val limit = if (kind == "route" && status == 200) MAX_ROUTE_BYTES else MAX_SMALL_BYTES
        if (kind != "route" && encoding != "identity") throw ContractError("small answers are not compressed")
        if (status == 204 && (payload.isNotEmpty() || size != 0)) throw ContractError("a 204 has no body")
        val body = when (encoding) {
            "identity" -> { if (payload.size > limit) throw ContractError("body too large"); payload }
            "gzip" -> gunzipExact(payload, limit)
            "xz" -> unxzExact(payload, limit)
            else -> throw ContractError("encoding")
        }
        if (body.size != size) throw ContractError("decoded size differs from the declared size")
        if (sha256Hex(body) != sha256) throw ContractError("digest differs")
        return body
    }

    /** One complete gzip member and nothing after it, CRC and length verified, at most [limit] bytes decoded (a bomb is refused, not expanded). */
    private fun gunzipExact(data: ByteArray, limit: Int): ByteArray {
        if (data.size < 18 || data[0] != 0x1f.toByte() || data[1] != 0x8b.toByte() || data[2].toInt() != 8) throw ContractError("invalid gzip")
        val flags = data[3].toInt() and 255
        if (flags and 0xE0 != 0) throw ContractError("invalid gzip")
        var pos = 10
        fun need(n: Int) { if (pos + n > data.size) throw ContractError("invalid gzip") }
        if (flags and 4 != 0) { need(2); val extra = (data[pos].toInt() and 255) or ((data[pos + 1].toInt() and 255) shl 8); pos += 2; need(extra); pos += extra }
        if (flags and 8 != 0) { while (true) { need(1); if (data[pos++].toInt() == 0) break } }
        if (flags and 16 != 0) { while (true) { need(1); if (data[pos++].toInt() == 0) break } }
        if (flags and 2 != 0) {
            need(2)
            val crc = CRC32().apply { update(data, 0, pos) }.value.toInt() and 0xffff
            if (crc != ((data[pos].toInt() and 255) or ((data[pos + 1].toInt() and 255) shl 8))) throw ContractError("invalid gzip")
            pos += 2
        }
        val inflater = Inflater(true)
        try {
            inflater.setInput(data, pos, data.size - pos)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (!inflater.finished()) {
                val n = try { inflater.inflate(buffer) } catch (_: DataFormatException) { throw ContractError("invalid gzip") }
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw ContractError("invalid gzip")
                if (out.size() + n > limit) throw ContractError("gzip stream is not one complete bounded stream")
                out.write(buffer, 0, n)
            }
            if (inflater.remaining != 8) throw ContractError("gzip stream is not one complete bounded stream")
            val tail = data.size - 8
            val crc = (data[tail].toLong() and 255) or ((data[tail + 1].toLong() and 255) shl 8) or ((data[tail + 2].toLong() and 255) shl 16) or ((data[tail + 3].toLong() and 255) shl 24)
            val length = (data[tail + 4].toLong() and 255) or ((data[tail + 5].toLong() and 255) shl 8) or ((data[tail + 6].toLong() and 255) shl 16) or ((data[tail + 7].toLong() and 255) shl 24)
            val result = out.toByteArray()
            if (CRC32().apply { update(result) }.value != crc || (result.size.toLong() and 0xffffffffL) != length) throw ContractError("invalid gzip")
            return result
        } finally { inflater.end() }
    }

    /** One complete xz stream and nothing after it, at most [limit] bytes decoded, decoder memory bounded by [XZ_MEMLIMIT_KIB]. */
    private fun unxzExact(data: ByteArray, limit: Int): ByteArray {
        val source = ByteArrayInputStream(data)
        try {
            SingleXZInputStream(source, XZ_MEMLIMIT_KIB).use { decoder ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val n = decoder.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > limit) throw ContractError("xz stream is not one complete bounded stream")
                    out.write(buffer, 0, n)
                }
                if (source.available() != 0) throw ContractError("xz stream is not one complete bounded stream")
                return out.toByteArray()
            }
        } catch (error: ContractError) { throw error } catch (_: IOException) { throw ContractError("invalid xz") }
    }

    /** What goes on the air for one job: the encoded payload and what comma checks it against. */
    class Encoded(val status: Int, val encoding: String, val payload: ByteArray, val size: Int, val sha256: String)

    /**
     * Encodes the X10's answer for the link. A 200 route is `gzip` (or `xz` when [preferXz]: the fallback when write without response is not available,
     * where bytes cost more than the encoder's time), or `identity` when that is not larger; everything else is `identity` and at most 4 KiB. The result is
     * decoded again with [decodeResult] before it is returned, so a fault of the encoder ends the job here.
     */
    fun encode(kind: String, status: Int, body: ByteArray, preferXz: Boolean): Encoded {
        if (status !in ALLOWED_STATUS) throw ContractError("status not carried")
        val limit = if (kind == "route" && status == 200) MAX_ROUTE_BYTES else MAX_SMALL_BYTES
        if (body.size > limit) throw ContractError("body too large")
        if (status == 204 && body.isNotEmpty()) throw ContractError("a 204 has no body")
        var encoding = "identity"
        var payload = body
        if (kind == "route" && status == 200 && body.isNotEmpty()) {
            val gz = gzip(body)
            if (gz.size < payload.size) { payload = gz; encoding = "gzip" }
            if (preferXz && body.size >= XZ_THRESHOLD_BYTES) {
                val xz = xz(body)
                if (xz.size < payload.size) { payload = xz; encoding = "xz" }
            }
        }
        val sha = sha256Hex(body)
        decodeResult(kind, status, encoding, payload, body.size, sha)
        return Encoded(status, encoding, payload, body.size, sha)
    }

    /** Below this size `xz` is not worth its encoder time even on a slow link. */
    const val XZ_THRESHOLD_BYTES = 8 * 1024

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun xz(data: ByteArray): ByteArray {
        val options = LZMA2Options().apply { setPreset(6); dictSize = 1 shl 20 }
        val out = ByteArrayOutputStream()
        XZOutputStream(out, options).use { it.write(data) }
        return out.toByteArray()
    }
}
