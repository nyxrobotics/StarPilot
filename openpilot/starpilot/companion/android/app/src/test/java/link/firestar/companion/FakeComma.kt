package link.firestar.companion

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * comma's job broker as the contract describes it (`tools/btlab/contract/jobs.py` `JobBroker`), at the level of the lane: it answers `poll2`, the v2 chunk call
 * and `fail`, checks every chunk with the contract's own rules and decodes the finished result. Used by the job tests, never by the app.
 */
/** The bridge's reading of a `push-enable` body (`parse_announcement` of `tools/btlab/contract/mapjobs.py`): (nav, maps), or (false, false) for anything that is not an announcement. */
internal object Announcements {
    fun parse(body: ByteArray?): Pair<Boolean, Boolean> {
        if (body == null || body.isEmpty() || body.size > 64 || body.any { it < 0 }) return false to false
        val value = try { StrictJson.parseObject(String(body, Charsets.US_ASCII)) } catch (_: StrictJson.Invalid) { return false to false }
        if (value.duplicateKey || value.members.isEmpty() || value.members.any { (k, v) -> (k != "nav" && k != "maps") || v != true }) return false to false
        return value.has("nav") to value.has("maps")
    }
}

internal class FakeComma {
    class Job(val id: String, val kind: Shape, val target: String) {
        var next = 0
        var status: Int? = null
        var encoding: String? = null
        val data = ByteArrayOutputStream()
        var finished = false
        var failed = false
        var result: Pair<Int, ByteArray>? = null
        var chunks = ArrayList<Pair<Int, Int>>() // (sequence, bytes) as committed
    }

    val jobs = LinkedHashMap<String, Job>()
    val calls = CopyOnWriteArrayList<String>()
    val rejected = CopyOnWriteArrayList<String>()
    val expired = HashSet<String>()
    var claimedByAnotherPhone = false
    /** The newest `push-enable` of this phone: (nav, maps) as the bridge read it. Like the real broker, a map job is shown to a phone that announced `maps` only. */
    var announced: Pair<Boolean, Boolean> = false to false
    var pushEnables = 0
    var nextPollMs = 10_000
    private var counter = 0

    @Synchronized fun submit(kind: Shape, target: String): Job {
        val id = (++counter).toString(16).padStart(32, '0')
        return Job(id, kind, target).also { jobs[id] = it }
    }

    private fun reply(status: Int, json: JSONObject? = null) =
        JSONObject().put("status", status).put("body", Base64.getEncoder().encodeToString((json?.toString() ?: "").toByteArray()))

    /** One request that reached comma, with comma's answer: what a real broker must say to the same request (tools/btcompanion/bridge_replay.py replays these). */
    class Recorded(val method: String, val path: String, val body: ByteArray, val status: Int, val next: Int?)
    val transcript = CopyOnWriteArrayList<Recorded>()

    @Synchronized fun handle(meta: JSONObject, body: ByteArray?): JSONObject {
        val answer = handleInner(meta, body)
        val path = meta.getString("path")
        if (path.startsWith("/_bridge/sidecar/chunk/") || path.startsWith("/_bridge/sidecar/fail/")) {
            val next = try { StrictJson.parseObject(java.util.Base64.getDecoder().decode(answer.optString("body", ""))).let { (it["next"] as? StrictJson.Num)?.text?.toInt() } } catch (_: Exception) { null }
            transcript += Recorded(meta.getString("method"), path, body ?: ByteArray(0), answer.getInt("status"), next)
        }
        return answer
    }

    private fun handleInner(meta: JSONObject, body: ByteArray?): JSONObject {
        val method = meta.getString("method")
        val path = meta.getString("path")
        if (method == "POST" && path == "/_bridge/push/enable") {
            calls += "push-enable"
            pushEnables++
            announced = Announcements.parse(if (meta.has("body")) java.util.Base64.getDecoder().decode(meta.getString("body")) else null)
            return reply(200, JSONObject().put("bound", true))
        }
        if (meta.has("body")) rejected += "a body member in a raw request"
        when {
            method == "GET" && path == "/_bridge/sidecar/poll2" -> {
                calls += "poll2"
                // small jobs first, then the route, then the map jobs (the order of the lane's priorities); a map job only for a phone that announced `maps`
                val open = jobs.values.filter { !it.finished && !it.failed && it.id !in expired && (!it.kind.isMap || announced.second) }
                    .sortedBy { when (it.kind.jobClass) { LaneClass.SMALL -> 0; LaneClass.BULK -> 1; else -> 2 } }
                val rows = org.json.JSONArray()
                for (job in open) rows.put(JSONObject().put("id", job.id).put("kind", job.kind.wire).put("target", job.target).put("next_sequence", job.next)
                    .put("claimed", claimedByAnotherPhone).put("lease_ms", 15_000))
                return reply(200, JSONObject().put("v", 2).put("jobs", rows).put("next_poll_ms", nextPollMs))
            }
            method == "POST" && path.startsWith("/_bridge/sidecar/fail/") -> {
                val job = jobs[path.substringAfterLast('/')] ?: return reply(404)
                calls += "fail:${job.kind.wire}"
                job.failed = true
                return reply(200, JSONObject().put("failed", true))
            }
            method == "POST" && path.startsWith("/_bridge/sidecar/chunk/") -> return chunk(path, body ?: ByteArray(0))
        }
        rejected += "unknown call $method"
        return reply(400)
    }

    private fun chunk(path: String, data: ByteArray): JSONObject {
        val target = try { JobContract.parseChunkTarget(path) } catch (_: ContractError) { rejected += "chunk path"; return reply(400) }
        val job = jobs[target.jobId]
        if (job == null || job.finished || job.failed || job.id in expired) return reply(404)
        if (job.kind.isMap && !announced.second) { rejected += "this phone does not carry maps"; return reply(400) }
        if (data.size > JobContract.MAX_CHUNK_BYTES) { rejected += "chunk too large"; return reply(400) }
        if (target.sequence < job.next) return reply(200, JSONObject().put("next", job.next))
        if (target.sequence != job.next) { rejected += "chunk out of order"; return reply(400) }
        if (job.status == null) { job.status = target.status; job.encoding = target.encoding }
        else if (job.status != target.status || job.encoding != target.encoding) { job.failed = true; rejected += "status changed"; return reply(400) }
        job.data.write(data)
        job.chunks += target.sequence to data.size
        job.next++
        calls += "chunk:${job.kind.wire}:${target.sequence}${if (target.last) ":last" else ""}"
        if (target.last) {
            try {
                val decoded = JobContract.decodeResult(job.kind.wire, job.status!!, job.encoding!!, job.data.toByteArray(), target.size!!, target.sha256!!)
                job.result = job.status!! to decoded
                job.finished = true
            } catch (_: ContractError) { job.failed = true; rejected += "invalid result"; return reply(400) }
        }
        return reply(200, JSONObject().put("next", job.next))
    }
}

/** A link to [FakeComma] that can lose the link around a chunk: before comma has seen it, or after comma committed it (the acknowledgement is lost). */
internal class CommaLink(val comma: FakeComma) : LaneLink {
    var failBeforeChunk: Int? = null
    var failAfterChunk: Int? = null
    var chunkDelayMs = 0L
    val priorities = CopyOnWriteArrayList<Boolean>()
    val bodies = CopyOnWriteArrayList<Int>()
    @Volatile var closed = false

    override fun exchange(meta: JSONObject, body: ByteArray?, deadlineSeconds: Long): JSONObject {
        val path = meta.getString("path")
        val chunk = if (path.startsWith("/_bridge/sidecar/chunk/")) path.substringAfterLast('/').substringBefore('?').toInt() else null
        if (chunk != null) {
            body?.let { bodies += it.size }
            if (chunkDelayMs > 0) Thread.sleep(chunkDelayMs)
            if (failBeforeChunk == chunk) { failBeforeChunk = null; throw IOException("link lost before the chunk reached comma") }
        }
        val answer = comma.handle(meta, body)
        if (chunk != null && failAfterChunk == chunk) { failAfterChunk = null; throw IOException("link lost: comma committed the chunk, its acknowledgement did not arrive") }
        return answer
    }
    override fun control(action: String) = JSONObject().put("status", 200)
    override fun setHighPriority(on: Boolean) { priorities += on }
    override fun close() { closed = true }
}
