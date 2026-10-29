package link.firestar.companion

/**
 * The push frame of the kind `drive` (contract version 4, docs/dev/comma-drive-status.md): `{"t":"drive","c":counter,"s":"<session>","ts":<ms>,"b":{drive object},"a":"<token>"}`, sealed
 * like a vehicle frame. The bridge sends it only to a phone that announced `"drive":true` in `push-enable` (and the Companion announces it only to a bridge whose health answer said
 * `pushDrive`), so a drive frame from a bridge that did not announce it is refused here. The object is written again as compact ASCII JSON and must pass [DriveContract.bodyProblem]
 * (the allow-list of the shape `drive`); only that body is ever forwarded, and only to `POST /v1/drive` of the X10's own route API. Nothing goes back to the comma because of it.
 */
internal object DriveFrames {
    const val KIND = "drive"
    const val PATH = DriveContract.PATH

    /** The opened frame (its session, counter and timestamp are checked by [PushFrames.open]); a [PushError] of this kind is counted as a refused drive frame, apart from the position's. */
    fun message(value: StrictJson.Obj, counter: Long, ts: Long, allowed: Boolean): PushMessage {
        if (!allowed) throw PushError("drive frame from a bridge that did not announce it", KIND)
        val report = value["b"] as? StrictJson.Obj
        val auth = value["a"] as? String
        if (report == null || auth == null || !PushFrames.AUTH.matches(auth)) throw PushError("drive frame fields", KIND)
        val body = compact(report) ?: throw PushError("report is not a drive body", KIND)
        if (DriveContract.bodyProblem(body).isNotEmpty()) throw PushError("report is not a drive body", KIND)
        return PushMessage(KIND, counter, ts, body, auth)
    }

    /**
     * The drive object as compact ASCII JSON, member by member as received; null when a member is not a number, a boolean or a plain word or text (the characters of
     * [DriveContract.TEXT_CHARS], which never need an escape), when a key repeats or is not a lower-case name. [age] is the text that takes the value of `age_ms`.
     * Which keys and values are allowed is the business of [DriveContract.bodyProblem], not of this writer.
     */
    internal fun compact(report: StrictJson.Obj, age: String? = null): ByteArray? {
        if (report.duplicateKey) return null
        val out = StringBuilder("{")
        var first = true
        for ((key, item) in report.members) {
            if (key.isEmpty() || key.any { it !in 'a'..'z' && it !in '0'..'9' && it != '_' }) return null
            if (!first) out.append(',')
            first = false
            out.append('"').append(key).append("\":")
            when {
                age != null && key == "age_ms" -> out.append(age)
                item is Boolean -> out.append(item.toString())
                item is StrictJson.Num -> out.append(item.text)
                DriveContract.textOk(item) -> out.append('"').append(item as String).append('"')
                else -> return null
            }
        }
        out.append('}')
        return out.toString().toByteArray(Charsets.US_ASCII)
    }

    /**
     * The body with its one age raised by [addMs] and saturating at 60000 (`aged` of `drive.py`); nothing else is aged (the drive object has no other age). A body whose age does not
     * change comes back as it came, byte for byte. An age cannot shrink: a negative [addMs] is refused.
     */
    fun withAge(body: ByteArray, addMs: Long): ByteArray {
        if (addMs < 0) throw PushError("age cannot shrink", KIND)
        val report = try { StrictJson.parseObject(String(body, Charsets.US_ASCII), DriveContract.MAX_BODY * 2) } catch (_: StrictJson.Invalid) { throw PushError("report unreadable", KIND) }
        val old = (report["age_ms"] as? StrictJson.Num)?.toLongOrNull()?.takeIf { it in 0..DriveContract.MAX_AGE_MS } ?: throw PushError("age_ms", KIND)
        val aged = if (addMs >= DriveContract.MAX_AGE_MS - old) DriveContract.MAX_AGE_MS else old + addMs
        if (report["age_ms"].toString() == aged.toString()) return body
        return compact(report, aged.toString()) ?: throw PushError("report unreadable", KIND)
    }
}
