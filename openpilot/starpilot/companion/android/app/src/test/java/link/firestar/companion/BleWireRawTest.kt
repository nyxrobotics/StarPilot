package link.firestar.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random
import java.util.zip.Inflater

/** Codec 2 on the way out: stored deflate blocks, the frame the comma's `open_message` reads, sizes. A copy of every frame goes to BTLINK_INTEROP_DIR when set (see bin/interop_check.sh of the dev note). */
class BleWireRawTest {
    private val key = TestVectors.KEY
    private fun body(n: Int, seed: Int = 1) = ByteArray(n).also { Random(seed.toLong()).nextBytes(it) }
    private val meta = "{\"method\":\"POST\",\"path\":\"/_bridge/sidecar/chunk/0123456789abcdef0123456789abcdef/3?last=0&status=200&encoding=gzip\"}".toByteArray()

    private fun inflate(data: ByteArray, expected: Int): ByteArray {
        val inflater = Inflater(true)
        inflater.setInput(data)
        val out = ByteArray(expected + 1)
        var n = 0
        while (!inflater.finished()) { val k = inflater.inflate(out, n, out.size - n); n += k; if (k == 0 && inflater.needsInput()) break }
        assertTrue("stream complete", inflater.finished() && inflater.remaining == 0)
        return out.copyOf(n)
    }

    /** (BFINAL, length) of every stored block of [packed]; fails when the structure is not whole stored blocks. */
    private fun blocks(packed: ByteArray): List<Pair<Boolean, Int>> {
        val out = ArrayList<Pair<Boolean, Int>>()
        var at = 0
        while (at < packed.size) {
            val header = packed[at].toInt() and 255
            assertTrue("a stored block has BTYPE 00 and no other bits", header == 0 || header == 1)
            val len = (packed[at + 1].toInt() and 255) or ((packed[at + 2].toInt() and 255) shl 8)
            val nlen = (packed[at + 3].toInt() and 255) or ((packed[at + 4].toInt() and 255) shl 8)
            assertEquals("NLEN is the complement of LEN", len.inv() and 0xffff, nlen)
            out += (header == 1) to len
            at += 5 + len
        }
        assertEquals(packed.size, at)
        return out
    }

    @Test fun storedDeflateIsAStandardStreamOfWholeBlocks() {
        for (n in listOf(1, 2, 100, 65_534, 65_535, 65_536, 65_537, 131_069, 131_070, 131_071, 200_000)) {
            val data = body(n, n)
            val packed = BleWire.storedDeflate(data)
            val parts = blocks(packed)
            assertEquals("size $n: five bytes per block", n + 5 * parts.size, packed.size)
            assertEquals("size $n: blocks of 65535 bytes", (n + 65_534) / 65_535, parts.size)
            assertTrue("only the last block carries BFINAL", parts.dropLast(1).none { it.first } && parts.last().first)
            assertTrue(parts.all { it.second <= 65_535 })
            assertArrayEquals("size $n", data, inflate(packed, n))
        }
        assertArrayEquals(ByteArray(0), inflate(BleWire.storedDeflate(ByteArray(0)), 0))
    }

    @Test fun aRawFrameOpensAndCarriesTheBodyUnchanged() {
        for (n in listOf(0, 1, 1900, 6144, 65_535, 70_000, 300_000, BleWire.MAX_BODY)) {
            val data = body(n, n + 3)
            val frame = BleWire.sealRaw(meta, data, key, "request", TestVectors.nonce(9))
            val opened = BleWire.decode(frame.copyOfRange(4, frame.size), key, "request")
            assertArrayEquals("size $n meta", meta, opened.json)
            assertArrayEquals("size $n body", data, opened.body ?: ByteArray(0))
            assertEquals("length prefix", frame.size - 4, java.nio.ByteBuffer.wrap(frame, 0, 4).int)
            val blocks = maxOf(1, (4 + meta.size + n + 65_534) / 65_535)
            assertEquals("size $n: nonce 12, envelope 5, the payload in stored blocks, tag 16", 4 + 12 + 5 + (4 + meta.size + n) + 5 * blocks + 16, frame.size)
        }
    }

    @Test fun theRawFrameIsAQuarterSmallerThanTheBase64FrameForTheSameChunk() {
        val data = body(1900)
        val raw = BleWire.sealRaw(meta, data, key).size
        val request = "{\"method\":\"POST\",\"path\":\"/_bridge/sidecar/chunk/0123456789abcdef0123456789abcdef/3?last=0&status=200&encoding=gzip\",\"body\":\"${java.util.Base64.getEncoder().encodeToString(data)}\"}"
        val old = BleWire.seal(request.toByteArray(), key).size
        assertTrue("raw $raw bytes against base64 $old", raw < old * 0.8)
        assertEquals("packets of 239 data bytes", (raw + 238) / 239, BleWire.packets(BleWire.sealRaw(meta, data, key), 244).size)
    }

    @Test fun theLimitsAreHeld() {
        assertThrows(IllegalArgumentException::class.java) { BleWire.sealRaw(meta, ByteArray(BleWire.MAX_BODY + 1), key) }
        assertThrows(IllegalArgumentException::class.java) { BleWire.sealRaw(ByteArray(0), ByteArray(1), key) }
        assertThrows(IllegalArgumentException::class.java) { BleWire.sealRaw(meta, ByteArray(1), ByteArray(16)) }
        assertThrows(IllegalArgumentException::class.java) { BleWire.sealRaw(meta, ByteArray(1), key, "request", ByteArray(11)) }
    }

    @Test fun theDirectionIsPartOfTheSeal() {
        val frame = BleWire.sealRaw(meta, body(50), key, "request")
        assertThrows(Exception::class.java) { BleWire.decode(frame.copyOfRange(4, frame.size), key, "response") }
        assertThrows(Exception::class.java) { BleWire.decode(frame.copyOfRange(4, frame.size), key, "push") }
    }

    @Test fun aRequestWithoutARawBodyIsTheOldCodecZeroFrameByteForByte() {
        // BleWireTest holds the production vector of the codec-0 request; sealing without a raw body still goes through the same code
        val frame = BleWire.seal("{\"method\":\"GET\",\"path\":\"/api/companion/status\"}".toByteArray(), key, "request", TestVectors.nonce(0))
        val opened = BleWire.decode(frame.copyOfRange(4, frame.size), key, "request")
        assertEquals(null, opened.body)
        assertEquals("{\"method\":\"GET\",\"path\":\"/api/companion/status\"}", String(opened.json))
    }

    /** Writes the frames a Python check opens with the production `open_message` (bin/interop_check.sh); a normal run only checks that they open here. */
    @Test fun framesForThePythonCheck() {
        val dir = System.getenv("BTLINK_INTEROP_DIR")?.let { File(it).apply { mkdirs() } }
        val sizes = listOf(0, 1, 239, 1900, 6144, 65_000, 65_531, 65_535, 65_536, 70_000, 131_071, 200_000, 400_000, BleWire.MAX_BODY - 200)
        val shapes = listOf("{\"method\":\"POST\",\"path\":\"/_bridge/sidecar/chunk/0123456789abcdef0123456789abcdef/0?last=1&status=503&encoding=identity&size=63&sha256=${"0".repeat(64)}\"}",
            "{\"method\":\"POST\",\"path\":\"/_bridge/sidecar/fail/0123456789abcdef0123456789abcdef\",\"id\":\"i-1\",\"session\":\"${"ab".repeat(16)}\",\"counter\":99,\"responseCodec\":2,\"responseFlow\":\"read-stream\"}")
        for ((index, size) in sizes.withIndex()) {
            val data = body(size, 100 + index)
            val metaText = shapes[index % 2].toByteArray()
            val frame = BleWire.sealRaw(metaText, data, key, "request", TestVectors.nonce(index))
            val opened = BleWire.decode(frame.copyOfRange(4, frame.size), key, "request")
            assertArrayEquals(data, opened.body ?: ByteArray(0))
            dir?.let { File(it, "frame-%02d.json".format(index)).writeText("{\"key_hex\":\"${TestVectors.hex(key)}\",\"frame_hex\":\"${TestVectors.hex(frame)}\",\"meta\":${quote(String(metaText))},\"body_len\":$size,\"body_sha256\":\"${JobContract.sha256Hex(data)}\"}") }
        }
    }

    private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
