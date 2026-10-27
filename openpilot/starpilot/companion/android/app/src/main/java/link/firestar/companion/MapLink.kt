package link.firestar.companion

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Whether the tile client of the comma may make a job over Bluetooth, read from the bridge's health answer (`admit` of `tools/btlab/contract/mapjobs.py`, spec 115 section 8.2): the flags
 * `maps` and `wwr` are the literal `true` and `up_kb_s` is a number of 10 or more (**full**: batches of up to 16 tiles); while `up_kb_s` is absent the client may make one probe job of at most
 * two tiles; every other answer is **closed**. The comma decides this (P3); the Companion reads the same flags with the same rule so that its own state says what the comma will do, and it
 * never uses the answer to refuse a job that the comma has made (the comma's rule is the one that counts, the lane protects the route in any case).
 */
internal class MapAdmission(val mode: Mode, val reason: String) {
    enum class Mode { FULL, PROBE, CLOSED }

    val maxTiles: Int get() = when (mode) { Mode.FULL -> 16; Mode.PROBE -> PROBE_MAX_TILES; Mode.CLOSED -> 0 }

    companion object {
        const val MIN_UP_KB_S = 10.0
        const val PROBE_MAX_TILES = 2
        /** The reason of a closed answer for a slow link: the comma probes such a link again at growing intervals. */
        const val SLOW_LINK = "link too slow for tiles"

        fun of(features: BridgeFeatures): MapAdmission {
            if (!features.maps) return MapAdmission(Mode.CLOSED, "maps flag")
            if (!features.wwr) return MapAdmission(Mode.CLOSED, "wwr flag")
            if (features.upKbSInvalid) return MapAdmission(Mode.CLOSED, "up_kb_s is not a number")
            val rate = features.upKbS ?: return MapAdmission(Mode.PROBE, "up_kb_s not measured yet")
            return if (rate < MIN_UP_KB_S) MapAdmission(Mode.CLOSED, SLOW_LINK) else MapAdmission(Mode.FULL, "")
        }
    }
}

/**
 * The Companion's own measure of its uplink, in the rule of the bridge's `up_kb_s` (`UplinkMeter` of mapjobs.py): the rate of every chunk upload of at least 4,096 body bytes, the median of the
 * last five, in KB (1,000 bytes) per second, rounded to one decimal; null before the first such chunk. The sample is the body over the time of the burst of the data packets, from the start of
 * the first write to the end of the last write without response (`BleExchange`), which is what the bridge reads between the arrival of its first and its last data packet. **The bridge's figure
 * decides the admission of the comma's tile client; this one is the Companion's view of the same link** (status and tests), and it never gates a job.
 */
internal class UplinkMeter {
    private val samples = ArrayDeque<Double>()

    @Synchronized fun observe(bodyBytes: Int, seconds: Double) {
        if (bodyBytes < MIN_BODY || seconds <= 0.0) return
        samples.addLast(bodyBytes / seconds / 1000.0)
        while (samples.size > WINDOW) samples.removeFirst()
    }

    /** The median of the last five samples, one decimal, or null. */
    @get:Synchronized val upKbS: Double? get() {
        if (samples.isEmpty()) return null
        val sorted = samples.sorted()
        val middle = sorted.size / 2
        val median = if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
        return BigDecimal(median).setScale(1, RoundingMode.HALF_EVEN).toDouble()
    }

    companion object {
        const val WINDOW = 5
        /** A chunk below this says more about the cost of a request than about the link: it is not a sample. */
        const val MIN_BODY = 4096
    }
}
