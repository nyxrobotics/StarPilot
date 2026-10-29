package link.firestar.companion

/**
 * The drive status (contract version 4): what the comma's driving assist is doing, told to the X10 (docs/dev/comma-drive-status.md). One flat JSON object, printable ASCII, at most
 * 512 bytes, KNOWN keys only, no repeated key, no NaN or Infinity:
 *
 *     v seq age_ms st rdy lat lon ov al ak a1 a2 lc ld lp crv stp
 *
 * `v` is the integer 1, `seq` an integer 0..2^31-1 and `age_ms` an integer 0..60000 (all three required); the words (`st`, `ov`, `ak`, `lc`, `ld`, `lp`), the booleans (`rdy`, `lat`,
 * `lon`), `al` (0..3), the texts (`a1`, `a2`: 1..40 characters of [TEXT_CHARS]) and the only-true flags (`crv`, `stp`) are typed and bounded as the reference is, and the pairs hold:
 * `ld` needs a `lc` other than `off`, `lp` needs `lc` `pre`, `ov` needs `st` `ovr`, `ak`, `a1` and `a2` need an `al` of 1 or more. The reference is `problem` of
 * `tools/btlab/contract/drive.py`; `drive-v4.json` holds this port to it. Nothing here is ever sent to the comma: the Companion only passes the status on to the X10.
 */
internal object DriveContract {
    const val PATH = "/v1/drive"
    const val MAX_BODY = 512
    const val MAX_AGE_MS = 60_000L
    const val MAX_SEQ = 2_147_483_647L
    const val MAX_TEXT = 40
    /** The keys of the object, in the order they are written. */
    val KEYS = listOf("v", "seq", "age_ms", "st", "rdy", "lat", "lon", "ov", "al", "ak", "a1", "a2", "lc", "ld", "lp", "crv", "stp")
    private val WORDS = mapOf(
        "st" to setOf("dis", "pre", "en", "soft", "ovr"),
        "ov" to setOf("steer", "gas"),
        "ak" to setOf("take", "drv", "fcw", "lane", "other"),
        "lc" to setOf("off", "pre", "go", "fin"),
        "ld" to setOf("l", "r"),
        "lp" to setOf("man", "auto", "na", "blk"),
    )
    private val FLAGS = setOf("rdy", "lat", "lon")
    private val ONLY_TRUE = setOf("crv", "stp")
    /** The characters of an alert text: the comma's own English alert text, nothing that needs an escape in JSON. */
    const val TEXT_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 .,:;!?'%/()+-"

    fun textOk(item: Any?): Boolean = item is String && item.length in 1..MAX_TEXT && item.all { it in TEXT_CHARS }

    private fun integerIn(item: Any?, low: Long, high: Long): Boolean = item is StrictJson.Num && item.toLongOrNull()?.let { it in low..high } == true

    /** "" for a body that the shape `drive` may carry, else the first reason (ours: nothing the peer sent is echoed). */
    fun bodyProblem(body: ByteArray): String {
        if (body.size !in 1..MAX_BODY) return "drive body size"
        if (body.any { it > 0x7E || it < 0x20 }) return "drive body is not printable ASCII"
        val value = try { StrictJson.parseObject(String(body, Charsets.US_ASCII)) } catch (_: StrictJson.Invalid) { return "drive body is not a clean JSON object" }
        if (value.duplicateKey) return "drive body is not a clean JSON object"
        if (value.members.isEmpty()) return "drive body is not an object"
        val version = value["v"]
        if (version !is StrictJson.Num || !version.isInteger || version.text != "1") return "drive body version"
        if (!integerIn(value["seq"], 0, MAX_SEQ) || !integerIn(value["age_ms"], 0, MAX_AGE_MS)) return "drive body seq or age_ms"
        for ((key, item) in value.members) {
            if (key == "v" || key == "seq" || key == "age_ms") continue
            if (key !in KEYS) return "drive body key"
            val ok = when {
                key in WORDS -> item is String && item in WORDS.getValue(key)
                key in FLAGS -> item is Boolean
                key == "al" -> integerIn(item, 0, 3)
                key == "a1" || key == "a2" -> textOk(item)
                else -> item == true // `crv`, `stp`: only the literal true
            }
            if (!ok) return "drive body value"
        }
        if (value.has("ld") && (value["lc"] ?: "off") == "off") return "drive body pairs"
        if (value.has("lp") && value["lc"] != "pre") return "drive body pairs"
        if (value.has("ov") && value["st"] != "ovr") return "drive body pairs"
        if ((value.has("ak") || value.has("a1") || value.has("a2")) && !integerIn(value["al"], 1, 3)) return "drive body pairs"
        return ""
    }
}
