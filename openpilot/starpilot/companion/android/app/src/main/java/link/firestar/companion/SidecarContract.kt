package link.firestar.companion

import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.SingleXZInputStream
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater

/** The peer broke the contract of the Bluetooth link; the message is ours (never an echo of what the peer sent). */
internal class ContractError(message: String) : IllegalArgumentException(message)

/**
 * The request shapes that may cross the link from comma to the X10's route API: the four of contract version 2 and the five map shapes of version 3
 * (docs/dev/bluetooth-map-contract.md). [jobClass] is the lane class of the chunks of a job of that kind and [deadlineSeconds] the comma's deadline of the kind
 * (`KIND_CLASS` and `DEADLINE_S` of `tools/btlab/contract/mapjobs.py`; the vectors `classes` and `deadlines_s` of `lane-map.json` hold this table). A vehicle report is a POST,
 * not a job.
 */
internal enum class Shape(val wire: String, val jobClass: LaneClass?, val deadlineSeconds: Int) {
    ROUTE("route", LaneClass.BULK, 90), STATUS("status", LaneClass.SMALL, 15), HEALTH("health", LaneClass.SMALL, 15), VEHICLE("vehicle", null, 0),
    ROADTILES("roadtiles", LaneClass.MAP, 120), STILE("stile", LaneClass.MAP, 30),
    GEOCODE("geocode", LaneClass.SMALL, 15), SUGGEST("suggest", LaneClass.SMALL, 15), RETRIEVE("retrieve", LaneClass.SMALL, 15);

    /** One of the five shapes of contract version 3: only a peer that announced `maps` may be asked for them. */
    val isMap: Boolean get() = ordinal >= ROADTILES.ordinal
    /** The answer of a search (`geocode`, `suggest`, `retrieve`): JSON of at most 64 KiB, uploaded in chunks of the one-second cap like the tiles. */
    val isSearch: Boolean get() = this == GEOCODE || this == SUGGEST || this == RETRIEVE

    companion object {
        fun ofWire(name: Any?): Shape? = values().firstOrNull { it.wire == name }
    }
}

/**
 * The allow-list (docs/dev/bluetooth-link.md section 5.1 and 16): the four request shapes of contract version 2 and, since version 3, five bodiless map shapes; one rule, enforced at
 * the comma entrance, here before anything touches `127.0.0.1:8765`, and by the route API itself. The JSON vectors of `tools/fixtures/btlab/contract/allowlist-v3.json` (version 3,
 * 779 cases: the 273 of `allowlist.json` first, then 506 for the five map shapes) are the test of this port; [check] with `version = 2` judges as a peer of version 2 would.
 *
 *     route      GET  /directions/v5/mapbox/driving-traffic/<coordinates>?<query>
 *     status     GET  /status
 *     health     GET  /health
 *     vehicle    POST /v1/vehicle   (one JSON object of at most 512 bytes; since contract version 2 it may carry the beacon's `nav` object)
 *     roadtiles  GET  /sidecar/v1/roadtiles?z=14&t=<x>.<y>[,<x>.<y>...]&access_token=<tok>
 *     stile      GET  /styles/v1/mapbox/{light-v11,dark-v11}/tiles/512/{z}/{x}/{y}?access_token=<tok>
 *     geocode    GET  /search/geocode/v6/forward?q=...&access_token=...
 *     suggest    GET  /search/searchbox/v1/suggest?q=...&access_token=...&session_token=<uuid4>
 *     retrieve   GET  /search/searchbox/v1/retrieve/<id>?access_token=<tok>&session_token=<uuid4>
 *
 * Values of the map shapes are written in canonical percent-encoding (unreserved characters literal, every other byte `%XX` with upper-case hex, no escape of an unreserved
 * character, no `+`); the reference is `tools/btlab/contract/allowlist.py` and this port keeps its order of checks.
 */
internal object Allowlist {
    const val ROUTE_PREFIX = "/directions/v5/mapbox/driving-traffic/"
    const val MAX_TARGET_BYTES = 8192
    const val MAX_SEGMENT_CHARS = 256
    const val MAX_VEHICLE_BODY = 512
    private const val MAX_WORD = 16
    /** The contract version of the judge: 2 = the four shapes, 3 = also the five map shapes. */
    const val CONTRACT_VERSION = 3
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

    /** The verdict on one request; [target] is the request target as it would be written on the HTTP request line. [version] is the contract version of the judge (2 refuses the five map shapes). */
    fun check(method: String?, target: String?, body: ByteArray = ByteArray(0), version: Int = CONTRACT_VERSION): Verdict {
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
        if (version >= 3) mapVerdict(method, path, hasQuery, query, body)?.let { return it }
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

    // ---- contract version 3: the five map shapes (docs/dev/bluetooth-map-contract.md 2.2; the reference is `_map_verdict` of allowlist.py) -------------------------------------

    private const val ROADTILES_PATH = "/sidecar/v1/roadtiles"
    private const val ROADTILES_ZOOM = "14"
    private const val ROADTILES_MAX_PAIRS = 16
    private const val ROADTILES_AXIS = 16_384
    private const val STILE_MAX_ZOOM = 18
    private const val STILE_PREFIX = "/styles/v1/mapbox/"
    private const val GEOCODE_PATH = "/search/geocode/v6/forward"
    private const val SUGGEST_PATH = "/search/searchbox/v1/suggest"
    private const val RETRIEVE_PREFIX = "/search/searchbox/v1/retrieve/"
    private const val RETRIEVE_ID_CHARS = 256
    private const val Q_MAX_CHARS = 256
    private const val TOKEN_MAX_CHARS = 128
    private val SUGGEST_TYPES = listOf("poi", "address", "street", "place", "neighborhood", "locality")
    private val GEOCODE_KEYS = listOf("q", "access_token", "limit", "autocomplete", "permanent", "proximity")
    private val SUGGEST_KEYS = listOf("q", "access_token", "session_token", "types", "limit", "proximity")
    private const val DECIMAL = "(?:0|[1-9][0-9]*)"
    private val PAIR_INT = Regex("($DECIMAL)\\.($DECIMAL)")
    private val STILE = Regex("/styles/v1/mapbox/(light-v11|dark-v11)/tiles/512/($DECIMAL)/($DECIMAL)/($DECIMAL)")
    private val UUID4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val RETRIEVE_ID = Regex("[A-Za-z0-9_-]+")
    private val LIMIT = Regex("(?:[1-9]|10)")
    private val COORD_TEXT = Regex("-?(?:0|[1-9][0-9]{0,2})(?:\\.[0-9]{1,10})?")

    /** `A-Za-z0-9-._~`: the characters that stand for themselves in canonical form. */
    private fun unreserved(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~'
    /** Upper-case hex only: the form a canonical encoder writes. */
    private fun upperHex(c: Char): Int = when (c) { in '0'..'9' -> c - '0'; in 'A'..'F' -> c - 'A' + 10; else -> -1 }

    /**
     * The bytes of a value written in canonical percent-encoding: the characters of [literal] (and the unreserved ones) stand for themselves, every other byte is `%XX` with upper-case
     * hex, and an escape of an unreserved character (`%41`) is refused (no encoder writes it, so it is only an attempt to hide a character). Null when the value is not in that form.
     */
    private fun decodePercent(value: String, literal: String): ByteArray? {
        val out = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            if (ch == '%') {
                if (index + 2 >= value.length) return null
                val high = upperHex(value[index + 1]); val low = upperHex(value[index + 2])
                if (high < 0 || low < 0) return null
                val byte = high * 16 + low
                if (unreserved(byte.toChar())) return null
                out.write(byte)
                index += 3
            } else if (unreserved(ch) || ch in literal) {
                out.write(ch.code)
                index++
            } else return null
        }
        return out.toByteArray()
    }

    private fun tokenOk(value: String): Boolean {
        val raw = decodePercent(value, "") ?: return false
        return raw.size in 1..TOKEN_MAX_CHARS && raw.all { it in 0x21..0x7E }
    }

    /** `q`: 1 to 256 code points of strictly valid UTF-8 written as canonical percent-encoding, no ASCII control character (`%00` among them). */
    private fun qOk(value: String): Boolean {
        val raw = decodePercent(value, ",") ?: return false
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
        } catch (_: CharacterCodingException) { return false }
        val points = text.codePointCount(0, text.length)
        return points in 1..Q_MAX_CHARS && text.codePoints().allMatch { it >= 0x20 && it != 0x7F }
    }

    /** Splits at a literal `,` and at `%2C` (upper-case hex): the two ways a comma is written. */
    private fun splitComma(value: String): List<String> {
        val parts = ArrayList<String>()
        var start = 0
        var index = 0
        while (index < value.length) {
            if (value[index] == ',') { parts += value.substring(start, index); index++; start = index }
            else if (value.startsWith("%2C", index)) { parts += value.substring(start, index); index += 3; start = index }
            else index++
        }
        parts += value.substring(start)
        return parts
    }

    private fun proximityOk(value: String): Boolean {
        val parts = splitComma(value)
        if (parts.size != 2 || !parts.all { COORD_TEXT.matches(it) }) return false
        return Math.abs(parts[0].toDouble()) <= 180 && Math.abs(parts[1].toDouble()) <= 90
    }

    private fun typesOk(value: String): Boolean {
        val words = splitComma(value)
        return words.size in 1..SUGGEST_TYPES.size && words.toSet().size == words.size && words.all { it in SUGGEST_TYPES }
    }

    private fun searchValueOk(key: String, value: String): Boolean = when (key) {
        "q" -> qOk(value)
        "access_token" -> tokenOk(value)
        "session_token" -> UUID4.matches(value)
        "limit" -> LIMIT.matches(value)
        "autocomplete", "permanent" -> value == "true" || value == "false"
        "proximity" -> proximityOk(value)
        "types" -> typesOk(value)
        else -> false
    }

    /** `k=v&k=v` as pairs in order, or null when a part has no `=`, an empty key or the query is empty. */
    private fun queryPairs(query: String): List<Pair<String, String>>? {
        if (query.isEmpty()) return null
        val pairs = ArrayList<Pair<String, String>>()
        for (part in query.split("&")) {
            val eq = part.indexOf('=')
            if (eq <= 0) return null
            pairs += part.substring(0, eq) to part.substring(eq + 1)
        }
        return pairs
    }

    private fun roadtilesProblem(pairs: List<Pair<String, String>>?): String {
        if (pairs == null || pairs.map { it.first } != listOf("z", "t", "access_token")) return "roadtiles keys"
        val (z, t, token) = pairs.map { it.second }
        if (z != ROADTILES_ZOOM) return "roadtiles zoom"
        val seen = HashSet<Long>()
        val items = t.split(",")
        if (items.size !in 1..ROADTILES_MAX_PAIRS) return "roadtiles pair count"
        for (item in items) {
            val match = PAIR_INT.matchEntire(item)
            if (match == null || match.groupValues[1].length > 5 || match.groupValues[2].length > 5) return "roadtiles pair"
            val x = match.groupValues[1].toInt(); val y = match.groupValues[2].toInt()
            if (x >= ROADTILES_AXIS || y >= ROADTILES_AXIS) return "roadtiles pair out of the pyramid"
            if (!seen.add(x.toLong() * ROADTILES_AXIS + y)) return "roadtiles pair twice"
        }
        return if (tokenOk(token)) "" else "roadtiles token"
    }

    private fun stileProblem(path: String, pairs: List<Pair<String, String>>?): String {
        val match = STILE.matchEntire(path) ?: return "stile path"
        if (match.groupValues[2].length > 2 || match.groupValues[3].length > 6 || match.groupValues[4].length > 6) return "stile address"
        val z = match.groupValues[2].toInt(); val x = match.groupValues[3].toInt(); val y = match.groupValues[4].toInt()
        if (z > STILE_MAX_ZOOM || x >= 1 shl z || y >= 1 shl z) return "stile address"
        if (pairs == null || pairs.map { it.first } != listOf("access_token")) return "stile query"
        return if (tokenOk(pairs[0].second)) "" else "stile token"
    }

    private fun searchProblem(pairs: List<Pair<String, String>>?, allowed: List<String>, required: List<String>): String {
        if (pairs == null) return "search query"
        val keys = pairs.map { it.first }
        if (keys.toSet().size != keys.size || keys.any { it !in allowed } || required.any { it !in keys }) return "search keys"
        return if (pairs.all { searchValueOk(it.first, it.second) }) "" else "search value"
    }

    private fun retrieveProblem(path: String, pairs: List<Pair<String, String>>?): String {
        val id = path.substring(RETRIEVE_PREFIX.length)
        if (id.length !in 1..RETRIEVE_ID_CHARS || !RETRIEVE_ID.matches(id)) return "retrieve id"
        if (pairs == null || pairs.map { it.first } != listOf("access_token", "session_token")) return "retrieve query"
        return if (tokenOk(pairs[0].second) && UUID4.matches(pairs[1].second)) "" else "retrieve value"
    }

    /** The verdict for a path that belongs to one of the five map shapes, null for a path that belongs to none. Every map shape is a bodiless GET with a query. */
    private fun mapVerdict(method: String, path: String, hasQuery: Boolean, query: String, body: ByteArray): Verdict? {
        val shape = when {
            path == ROADTILES_PATH -> Shape.ROADTILES
            path.startsWith(STILE_PREFIX) -> Shape.STILE
            path == GEOCODE_PATH -> Shape.GEOCODE
            path == SUGGEST_PATH -> Shape.SUGGEST
            path.startsWith(RETRIEVE_PREFIX) -> Shape.RETRIEVE
            else -> return null
        }
        if (method != "GET" || body.isNotEmpty()) return refuse("${shape.wire} is a bodiless GET")
        if (!hasQuery) return refuse("${shape.wire} needs a query")
        val pairs = queryPairs(query)
        val reason = when (shape) {
            Shape.ROADTILES -> roadtilesProblem(pairs)
            Shape.STILE -> stileProblem(path, pairs)
            Shape.GEOCODE -> searchProblem(pairs, GEOCODE_KEYS, listOf("q", "access_token"))
            Shape.SUGGEST -> searchProblem(pairs, SUGGEST_KEYS, listOf("q", "access_token", "session_token"))
            else -> retrieveProblem(path, pairs)
        }
        return if (reason.isNotEmpty()) refuse(reason) else Verdict(true, shape, "")
    }
}

internal class ChunkTarget(val jobId: String, val sequence: Int, val last: Boolean, val status: Int, val encoding: String, val size: Int?, val sha256: String?)

/**
 * What the Companion sends to comma for a job's answer and what comma checks (docs/dev/bluetooth-link.md section 5.2). The vectors of `results.json` (34),
 * `results-v3.json` (121, the five map kinds: [decodeMapResult]) and `chunk-targets.json` (25) are the test of this port; [decodeResult] is also run on the Companion's own result
 * before the first chunk goes out, so an encoder fault ends the job here instead of being found by comma.
 *
 * The answers of the five map kinds are carried as the X10 gave them (`identity`: a bundle holds zlib streams, a PNG is compressed, the JSON of a search is at most 64 KiB), with the
 * status of the X10's map routes (200, 400, 401, 404, 422, 429, 503; not 204, 403 or 504), at most 4 KiB when it is not a 200 and at most the limit of the kind when it is.
 */
internal object JobContract {
    val ALLOWED_STATUS = setOf(200, 204, 400, 401, 403, 404, 422, 429, 503, 504)
    val ENCODINGS = listOf("identity", "gzip", "xz")
    const val MAX_ROUTE_BYTES = 8 * 1024 * 1024
    const val MAX_SMALL_BYTES = 4096
    const val MAX_CHUNK_BYTES = 64 * 1024
    /** What the X10's map routes answer (`MAP_STATUSES` of mapjobs.py); 204, 403 and 504 are carried for the older kinds only. */
    val MAP_STATUSES = setOf(200, 400, 401, 404, 422, 429, 503)
    /** The body of a map answer that is not a 200: at most 4 KiB. */
    const val MAX_MAP_ERROR_BYTES = 4096
    const val MAX_BUNDLE_BYTES = 524_288
    const val MAX_PNG_BYTES = 2 * 1024 * 1024
    const val MAX_SEARCH_BYTES = 64 * 1024
    /** Decoder memory bound for `xz` in KiB: 64 MiB. A 1 MiB dictionary needs about 2 MiB, so this is generous and still bounded. */
    const val XZ_MEMLIMIT_KIB = 64 * 1024
    private val TARGET = Regex("/_bridge/sidecar/chunk/([0-9a-f]{32})/([0-9]{1,6})\\?(.*)")
    private val STATUS_DIGITS = Regex("[0-9]{3}")
    private val SIZE_DIGITS = Regex("[0-9]{1,8}")
    private val HEX64 = Regex("[0-9a-f]{64}")
    private val ID = Regex("[0-9a-f]{32}")

    fun isJobId(value: String) = ID.matches(value)

    /** The largest body of [kind] that the contract carries with [status]: a map kind by its own limit (a bundle, a PNG, a search answer) or 4 KiB for a status other than 200. */
    fun mapLimit(kind: Shape, status: Int): Int = if (status != 200) MAX_MAP_ERROR_BYTES else when (kind) {
        Shape.ROADTILES -> MAX_BUNDLE_BYTES
        Shape.STILE -> MAX_PNG_BYTES
        else -> MAX_SEARCH_BYTES
    }

    /** The body of a finished job of one of the five map kinds, or [ContractError] (`decode_map_result` of mapjobs.py): `identity` only, a carried status, a limit, the declared size, the digest. */
    fun decodeMapResult(kind: String, status: Int, encoding: String, payload: ByteArray, size: Int, sha256: String): ByteArray {
        val shape = Shape.ofWire(kind)?.takeIf { it.isMap } ?: throw ContractError("kind")
        if (status !in MAP_STATUSES) throw ContractError("status not carried")
        if (encoding != "identity") throw ContractError("map answers are not compressed")
        if (payload.size > mapLimit(shape, status)) throw ContractError("body too large")
        if (payload.size != size) throw ContractError("decoded size differs from the declared size")
        if (sha256Hex(payload) != sha256) throw ContractError("digest differs")
        return payload
    }

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
        if (Shape.ofWire(kind)?.isMap == true) return decodeMapResult(kind, status, encoding, payload, size, sha256)
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
        Shape.ofWire(kind)?.takeIf { it.isMap }?.let { shape ->
            // a map answer is carried as it is: no gzip, no xz (a bundle holds zlib streams, a PNG is compressed)
            if (status !in MAP_STATUSES) throw ContractError("status not carried")
            if (body.size > mapLimit(shape, status)) throw ContractError("body too large")
            val sha = sha256Hex(body)
            decodeMapResult(kind, status, "identity", body, body.size, sha)
            return Encoded(status, "identity", body, body.size, sha)
        }
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
