package link.firestar.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream

/** The result encoder and its checks beyond the vectors: round trips, the encoding choice, every damaged stream, the chunk path builder. */
class JobContractTest {
    private val rng = Random(17)
    private fun json(n: Int, seed: Long = 1): ByteArray {
        val r = Random(seed)
        val s = StringBuilder("{\"c\":[")
        while (s.length < n) s.append('[').append(139 + r.nextDouble()).append(',').append(35 + r.nextDouble()).append("],")
        return s.append("0]}").toString().toByteArray()
    }
    private fun gzipOf(data: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(data) } }.toByteArray()
    private val sha = JobContract::sha256Hex

    @Test fun sha256IsTheStandardOne() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha("abc".toByteArray()))
    }

    @Test fun everyEncodedRouteDecodesToTheBytesTheX10Served() {
        for (size in listOf(1, 2, 100, 1000, 8191, 8192, 8193, 100_000, 700_000)) for (xz in listOf(false, true)) {
            val body = json(size, size.toLong()).copyOf(size)
            val encoded = JobContract.encode("route", 200, body, xz)
            assertEquals(body.size, encoded.size); assertEquals(sha(body), encoded.sha256)
            assertArrayEquals("size $size xz=$xz via ${encoded.encoding}", body, JobContract.decodeResult("route", 200, encoded.encoding, encoded.payload, encoded.size, encoded.sha256))
        }
    }

    @Test fun theEncodingFollowsTheSizeAndTheFallbackFlag() {
        assertEquals("a tiny answer is not made larger by gzip", "identity", JobContract.encode("route", 200, "{}".toByteArray(), false).encoding)
        assertEquals("gzip", JobContract.encode("route", 200, json(50_000), false).encoding)
        assertEquals("the fallback uses xz where it pays", "xz", JobContract.encode("route", 200, json(50_000), true).encoding)
        assertEquals("below 8 KiB xz is not worth its time", "gzip", JobContract.encode("route", 200, json(5000), true).encoding)
        assertEquals("identity", JobContract.encode("route", 503, "{\"error\":\"RouteAwaitingApproval\"}".toByteArray(), true).encoding)
        assertEquals("identity", JobContract.encode("status", 200, json(3000), true).encoding)
    }

    @Test fun theEncoderRefusesWhatTheContractRefuses() {
        assertThrows(ContractError::class.java) { JobContract.encode("route", 500, ByteArray(1), false) }
        assertThrows(ContractError::class.java) { JobContract.encode("route", 302, ByteArray(0), false) }
        assertThrows(ContractError::class.java) { JobContract.encode("route", 503, ByteArray(4097), false) }
        assertThrows(ContractError::class.java) { JobContract.encode("status", 200, ByteArray(4097), false) }
        assertThrows(ContractError::class.java) { JobContract.encode("route", 200, ByteArray(JobContract.MAX_ROUTE_BYTES + 1), false) }
        assertThrows(ContractError::class.java) { JobContract.encode("route", 204, ByteArray(1), false) }
        assertEquals(0, JobContract.encode("route", 204, ByteArray(0), false).size)
        assertEquals(4096, JobContract.encode("health", 200, ByteArray(4096) { 65 }, false).size)
    }

    @Test fun everyTruncationOfAGzipStreamIsRefusedAndNothingElseEscapes() {
        val body = json(400)
        val stream = gzipOf(body)
        for (n in 0 until stream.size) assertThrows("prefix $n", ContractError::class.java) { JobContract.decodeResult("route", 200, "gzip", stream.copyOf(n), body.size, sha(body)) }
        assertThrows(ContractError::class.java) { JobContract.decodeResult("route", 200, "gzip", stream + 0, body.size, sha(body)) }
        assertThrows("a second member", ContractError::class.java) { JobContract.decodeResult("route", 200, "gzip", stream + stream, body.size, sha(body)) }
    }

    @Test fun noBitFlipOfAGzipOrXzStreamEverGivesADifferentBody() {
        val body = json(600, 5)
        for (encoding in listOf("gzip", "xz")) {
            val stream = JobContract.encode("route", 200, if (encoding == "xz") json(9000, 5) else body, encoding == "xz").also { assertEquals(encoding, it.encoding) }
            val expectBody = if (encoding == "xz") json(9000, 5) else body
            var accepted = 0
            for (i in stream.payload.indices) for (bit in 0 until 8) {
                val damaged = stream.payload.copyOf().also { it[i] = (it[i].toInt() xor (1 shl bit)).toByte() }
                try {
                    val out = JobContract.decodeResult("route", 200, encoding, damaged, stream.size, stream.sha256)
                    assertArrayEquals("$encoding byte $i bit $bit decoded to a different body", expectBody, out); accepted++
                } catch (_: ContractError) { /* refused */ }
            }
            assertTrue("most flips are refused ($accepted of ${stream.payload.size * 8} passed)", accepted < stream.payload.size * 8 / 4)
        }
    }

    @Test fun gzipHeadersOfOtherWritersAreReadLikeZlibReadsThem() {
        val body = json(300, 3)
        val plain = gzipOf(body)
        val deflated = plain.copyOfRange(10, plain.size - 8)
        val trailer = plain.copyOfRange(plain.size - 8, plain.size)
        fun withHeader(flags: Int, extra: ByteArray): ByteArray = byteArrayOf(0x1f, 0x8b.toByte(), 8, flags.toByte(), 0, 0, 0, 0, 0, 3) + extra + deflated + trailer
        val named = withHeader(8, "route.json\u0000".toByteArray())
        assertArrayEquals("FNAME", body, JobContract.decodeResult("route", 200, "gzip", named, body.size, sha(body)))
        val commented = withHeader(16, "c\u0000".toByteArray())
        assertArrayEquals("FCOMMENT", body, JobContract.decodeResult("route", 200, "gzip", commented, body.size, sha(body)))
        val extra = withHeader(4, byteArrayOf(3, 0, 1, 2, 3))
        assertArrayEquals("FEXTRA", body, JobContract.decodeResult("route", 200, "gzip", extra, body.size, sha(body)))
        val headerOnly = byteArrayOf(0x1f, 0x8b.toByte(), 8, 2, 0, 0, 0, 0, 0, 3)
        val crc = (CRC32().apply { update(headerOnly) }.value and 0xffff).toInt()
        val hcrc = headerOnly + byteArrayOf((crc and 255).toByte(), (crc shr 8).toByte()) + deflated + trailer
        assertArrayEquals("FHCRC", body, JobContract.decodeResult("route", 200, "gzip", hcrc, body.size, sha(body)))
        hcrc[10] = (hcrc[10].toInt() xor 1).toByte()
        assertThrows("a wrong header CRC", ContractError::class.java) { JobContract.decodeResult("route", 200, "gzip", hcrc, body.size, sha(body)) }
        assertThrows("a reserved flag", ContractError::class.java) { JobContract.decodeResult("route", 200, "gzip", withHeader(0x20, ByteArray(0)), body.size, sha(body)) }
        assertThrows("not gzip", ContractError::class.java) { JobContract.decodeResult("route", 200, "gzip", body, body.size, sha(body)) }
    }

    @Test fun anXzStreamIsOneStreamNothingAfterIt() {
        val body = json(20_000, 8)
        val xz = JobContract.encode("route", 200, body, true).payload
        assertThrows(ContractError::class.java) { JobContract.decodeResult("route", 200, "xz", xz + 0, body.size, sha(body)) }
        assertThrows("two streams", ContractError::class.java) { JobContract.decodeResult("route", 200, "xz", xz + xz, body.size, sha(body)) }
        for (n in 0 until xz.size step 7) assertThrows("prefix $n", ContractError::class.java) { JobContract.decodeResult("route", 200, "xz", xz.copyOf(n), body.size, sha(body)) }
    }

    // ---- the chunk path ---------------------------------------------------------------------------------------------------------------

    private val job = "0123456789abcdef0123456789abcdef"
    private val digest = "0".repeat(64)

    @Test fun theBuilderMakesWhatTheParserAccepts() {
        val middle = JobContract.chunkPath(job, 4, false, 200, "xz", null, null)
        assertEquals("/_bridge/sidecar/chunk/$job/4?last=0&status=200&encoding=xz", middle)
        val last = JobContract.chunkPath(job, 5, true, 503, "identity", 63, digest)
        assertEquals("/_bridge/sidecar/chunk/$job/5?last=1&status=503&encoding=identity&size=63&sha256=$digest", last)
        val parsed = JobContract.parseChunkTarget(last)
        assertEquals(5, parsed.sequence); assertTrue(parsed.last); assertEquals(503, parsed.status); assertEquals(63, parsed.size); assertEquals(digest, parsed.sha256)
    }

    @Test fun theBuilderHoldsItselfToTheRulesOfTheReceiver() {
        assertThrows(ContractError::class.java) { JobContract.chunkPath(job, 1, true, 200, "gzip", null, null) }
        assertThrows(ContractError::class.java) { JobContract.chunkPath(job, 1, false, 200, "gzip", 5, null) }
        assertThrows(ContractError::class.java) { JobContract.chunkPath(job, 1, false, 200, "gzip", null, digest) }
        assertThrows(ContractError::class.java) { JobContract.chunkPath(job.uppercase(), 1, false, 200, "gzip", null, null) }
        assertThrows(ContractError::class.java) { JobContract.chunkPath(job.dropLast(1), 1, false, 200, "gzip", null, null) }
        assertThrows(ContractError::class.java) { JobContract.chunkPath(job, 1_000_000, false, 200, "gzip", null, null) }
        assertThrows(ContractError::class.java) { JobContract.chunkPath(job, -1, false, 200, "gzip", null, null) }
        assertThrows(ContractError::class.java) { JobContract.chunkPath(job, 1, false, 302, "gzip", null, null) }
        assertThrows(ContractError::class.java) { JobContract.chunkPath(job, 1, false, 200, "br", null, null) }
        assertThrows(ContractError::class.java) { JobContract.chunkPath(job, 1, true, 200, "gzip", 123456789, digest) }
        assertThrows(ContractError::class.java) { JobContract.chunkPath(job, 1, true, 200, "gzip", 5, digest.uppercase().replace('0', 'A')) }
    }

    @Test fun propertyAnyPathTheBuilderMakesParsesToTheSameFieldsAndTheOtherInputsAreRefused() {
        val encodings = listOf("identity", "gzip", "xz", "br", "", "GZIP")
        val statuses = listOf(200, 204, 400, 401, 403, 404, 422, 429, 503, 504, 0, 99, 100, 302, 500, 999, 1000, -1)
        repeat(6000) {
            val id = if (rng.nextInt(8) == 0) "zz" + job.drop(2) else job
            val sequence = listOf(0, 1, 7, 999_999, 1_000_000, -3, rng.nextInt(2_000_000))[rng.nextInt(7)]
            val last = rng.nextBoolean()
            val status = statuses[rng.nextInt(statuses.size)]
            val encoding = encodings[rng.nextInt(encodings.size)]
            val size = if (rng.nextBoolean()) rng.nextInt(200_000_000) else null
            val sha = if (rng.nextBoolean()) digest else digest.dropLast(1)
            val valid = id == job && sequence in 0..999_999 && status in JobContract.ALLOWED_STATUS && encoding in JobContract.ENCODINGS && (if (last) size != null && size in 0..99_999_999 && sha == digest else size == null)
            try {
                val path = JobContract.chunkPath(id, sequence, last, status, encoding, if (last) size else if (size != null && rng.nextBoolean()) size else null, if (last) sha else null)
                val parsed = JobContract.parseChunkTarget(path)
                assertEquals(sequence, parsed.sequence); assertEquals(last, parsed.last); assertEquals(status, parsed.status); assertEquals(encoding, parsed.encoding)
            } catch (_: ContractError) { /* refused: the receiver would refuse it too */ }
            if (valid && !last) JobContract.chunkPath(id, sequence, false, status, encoding, null, null)
        }
    }

    @Test fun parserRefusesWhatItDoesNotUnderstandWithoutOtherExceptions() {
        val seeds = listOf(JobContract.chunkPath(job, 3, true, 200, "gzip", 10, digest), JobContract.chunkPath(job, 3, false, 200, "gzip", null, null))
        repeat(20_000) {
            val chars = seeds[rng.nextInt(2)].toCharArray()
            repeat(1 + rng.nextInt(3)) { chars[rng.nextInt(chars.size)] = "/?&=0123456789abcdefxz\u0000é\n ".random(rng) }
            try { JobContract.parseChunkTarget(String(chars)) } catch (_: ContractError) { /* refused */ }
        }
    }

    private fun String.random(r: Random) = this[r.nextInt(length)]
}
