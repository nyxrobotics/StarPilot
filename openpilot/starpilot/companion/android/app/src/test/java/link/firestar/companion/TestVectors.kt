package link.firestar.companion

/** The shared vector files of the Bluetooth link (`app/src/test/resources/btlink/`, see README.md there) and a few helpers for the tests that read them. */
internal object TestVectors {
    fun text(name: String): String = javaClass.getResource("/btlink/$name")!!.readText(Charsets.UTF_8)
    fun file(name: String): StrictJson.Obj = StrictJson.parseObject(text(name), 8 shl 20)
    @Suppress("UNCHECKED_CAST") fun cases(name: String): List<StrictJson.Obj> = file(name)["cases"] as List<StrictJson.Obj>
    fun hex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun string(item: Any?): String = item as String
    fun int(item: Any?): Int = (item as StrictJson.Num).text.toInt()
    /** Compact JSON text of string members, in order, as Python's `json.dumps(..., separators=(",", ":"))` writes them for the plain strings used here. */
    fun compact(members: List<Pair<String, String>>): String =
        members.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }
    val KEY = ByteArray(32) { it.toByte() }
    fun nonce(n: Int) = ByteArray(12) { ((n + it) and 255).toByte() }
}
