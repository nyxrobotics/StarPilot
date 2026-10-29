package link.firestar.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The drive frames that the COMMA'S OWN bridge code seals (`ble_push.seal_push` and `push_packets`, made into `drive-frames-v4.json` by `galaxy_bluetooth/tests/gen_drive_frames.py` of the fork, with a fixed key, session and nonce) open in the
 * Companion's receiver: the packets reassemble to the frame, the frame opens when the bridge announced `pushDrive`, the object written again is the object the bridge sent, and a frame of another session or one that the bridge never announced is refused.
 * (The other tests of the drive frames build their frames with the Companion's own helpers; this one is the interoperability check between the two real implementations.)
 */
class DriveFramesFromTheCommaTest {
    private val vectors = TestVectors.file("drive-frames-v4.json")
    private val key = TestVectors.hex(vectors["key_hex"] as String)
    private val session = vectors["session"] as String

    @Test fun theFramesOfTheBridgeOpenAndTheirBodiesAreTheOnesTheBridgeSent() {
        var opened = 0
        for (case in vectors["cases"] as List<*>) {
            case as StrictJson.Obj
            val name = case["name"].toString()
            val frame = TestVectors.hex(case["frame_hex"] as String)
            if (case["expect"] == "refuse") {
                assertThrows("$name was accepted", PushError::class.java) { PushFrames.open(frame, key, session, 0, driveAllowed = true) }
                continue
            }
            val counter = TestVectors.int(case["counter"]).toLong()
            val message = PushFrames.open(frame, key, session, counter - 1, driveAllowed = true)
            opened++
            assertEquals(name, "drive", message.kind)
            assertEquals(name, counter, message.counter)
            assertEquals(name, (case["ts"] as StrictJson.Num).text.toLong(), message.tsMs)
            assertEquals(name, case["body"], String(message.body!!))
            assertEquals(name, case["auth"], message.auth)
            val assembler = PushFrames.Assembler()
            var whole: ByteArray? = null
            for (packet in case["packets_hex"] as List<*>) whole = assembler.add(TestVectors.hex(packet as String)) ?: whole
            assertArrayEquals(name, frame, whole)
        }
        assertEquals(5, opened)
    }

    @Test fun aDriveFrameFromABridgeThatNeverAnnouncedItIsRefused() {
        for (case in vectors["cases"] as List<*>) {
            case as StrictJson.Obj
            if (case["expect"] != "open") continue
            val frame = TestVectors.hex(case["frame_hex"] as String)
            assertThrows("${case["name"]} was accepted without the announcement", PushError::class.java) { PushFrames.open(frame, key, session, 0, driveAllowed = false) }
        }
    }

    @Test fun theCounterMustRise() {
        val case = (vectors["cases"] as List<*>).first() as StrictJson.Obj
        val frame = TestVectors.hex(case["frame_hex"] as String)
        val counter = TestVectors.int(case["counter"]).toLong()
        assertThrows(PushError::class.java) { PushFrames.open(frame, key, session, counter, driveAllowed = true) }
    }
}
