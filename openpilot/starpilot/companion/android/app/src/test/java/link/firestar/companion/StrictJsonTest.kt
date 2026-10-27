package link.firestar.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class StrictJsonTest {
    private fun ok(text: String) = StrictJson.parse(text)
    private fun bad(text: String) = assertThrows("accepted: ${text.take(60).replace("\n", "\\n")}", StrictJson.Invalid::class.java) { StrictJson.parse(text) }

    @Test fun validDocuments() {
        for (text in listOf("{}", "[]", "null", "true", "false", "0", "-0", "1E+5", "0.5e-3", "123456789012345678901234567890", "\"\"", "\"\\u0041\\u00e9\\ud800\"", " {\"a\" : [1 , {\"b\":null}] } ",
            "{\"a\":1,\"a\":2}", "\t\r\n[ ]\n", "\"\\\"\\\\\\/\\b\\f\\n\\r\\t\"", "[[[[[[[[[[[[[[[[]]]]]]]]]]]]]]]]")) ok(text)
    }

    @Test fun invalidDocuments() {
        for (text in listOf("", " ", "{", "}", "{}x", "{} {}", "[1,]", "[,1]", "{\"a\":1,}", "{'a':1}", "{a:1}", "{\"a\" 1}", "{\"a\":}", "{1:2}", "01", "1.", ".5", "+1", "-", "--1", "1e", "1e+", "0x1", "1_0",
            "NaN", "Infinity", "-Infinity", "nul", "trux", "True", "NULL", "\"abc", "\"\\x\"", "\"\\u12\"", "\"\\u12G4\"", "\"\n\"", "\"\t\"", "\"\u0001\"", "/* c */ 1", "// c\n1", "[1 2]", "[1;2]", "{\"a\":1;}",
            "\u00a01", "\ufeff{}", "[" + "[".repeat(40) + "]".repeat(40) + "]", "\"\\u00\"", "\"\\", "\"\\\"", "{\"a\":\"b\":\"c\"}", "[}", "{]")) bad(text)
    }

    @Test fun objectsKeepOrderRawTextAndRepeatedKeys() {
        val o = StrictJson.parseObject("{\"b\":1,\"a\":{\"x\":[1,2],\"y\":\"z\"},\"b\":2}")
        assertEquals(listOf("b", "a"), o.members.keys.toList())
        assertTrue(o.duplicateKey)
        assertEquals("2", (o["b"] as StrictJson.Num).text)
        assertEquals("{\"x\":[1,2],\"y\":\"z\"}", (o["a"] as StrictJson.Obj).raw)
        assertFalse((o["a"] as StrictJson.Obj).duplicateKey)
        assertNull(o["missing"]); assertTrue(StrictJson.parseObject("{\"n\":null}")["n"] === StrictJson.Null)
        assertThrows(StrictJson.Invalid::class.java) { StrictJson.parseObject("[]") }
    }

    @Test fun numbersAreKeptAsTextAndTypedByTheCaller() {
        val n = { text: String -> StrictJson.parse(text) as StrictJson.Num }
        assertTrue(n("5").isInteger); assertEquals(5L, n("5").toLongOrNull())
        assertFalse(n("5.0").isInteger); assertNull(n("5.0").toLongOrNull())
        assertFalse(n("5e0").isInteger)
        assertNull("too large for a long", n("123456789012345678901234567890").toLongOrNull())
        assertTrue(n("1e999").toDoubleOrNull()!!.isInfinite())
        assertEquals("-0", n("-0").text)
    }

    @Test fun stringsDecodeEscapes() {
        assertEquals("A\u00e9\"\\/\n", StrictJson.parse("\"\\u0041\\u00e9\\\"\\\\\\/\\n\""))
        assertEquals("\ud800", StrictJson.parse("\"\\ud800\""))
    }

    @Test fun inputsOverTheLimitAreRefusedBeforeParsing() {
        assertThrows(StrictJson.Invalid::class.java) { StrictJson.parse("[" + "1,".repeat(100) + "1]", maxChars = 50) }
        assertThrows(StrictJson.Invalid::class.java) { StrictJson.parseObject(byteArrayOf(0x7b, 0xc3.toByte(), 0x28, 0x7d)) } // not UTF-8
        assertEquals(0, StrictJson.parseObject("{}".toByteArray()).members.size)
    }

    @Test fun fuzzOnlyInvalidEscapesAndNeverALoopOrAnotherException() {
        val rng = Random(2)
        val seeds = listOf("{\"v\":1,\"seq\":5,\"fix\":true,\"lat\":35.681201,\"src\":\"gnss\",\"b\":[1,{\"c\":null}],\"s\":\"\\u0041\"}", "[true,false,null,-1.5e+3,\"x\"]")
        var parsed = 0
        repeat(40_000) {
            val chars = seeds[rng.nextInt(seeds.size)].toCharArray()
            repeat(1 + rng.nextInt(4)) {
                val i = rng.nextInt(chars.size)
                when (rng.nextInt(3)) { 0 -> chars[i] = rng.nextInt(128).toChar(); 1 -> chars[i] = "{}[]\",:-.e0 ".random(rng); else -> chars[i] = ' ' }
            }
            val text = String(chars)
            try { StrictJson.parse(text); parsed++ } catch (_: StrictJson.Invalid) { /* refused */ }
        }
        assertTrue("some mutations stay valid ($parsed)", parsed > 0)
        repeat(20_000) {
            val bytes = ByteArray(rng.nextInt(80)).also { rng.nextBytes(it) }
            try { StrictJson.parseObject(bytes) } catch (_: StrictJson.Invalid) { /* refused */ }
        }
    }

    private fun String.random(rng: Random) = this[rng.nextInt(length)]

    @Test fun deepNestingIsRefusedNotAStackOverflow() {
        bad("[".repeat(100_000) + "]".repeat(100_000))
        bad("{\"a\":".repeat(100_000) + "1" + "}".repeat(100_000))
    }
}
