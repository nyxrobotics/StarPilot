package link.firestar.companion

/**
 * When the Companion tries to connect again after a drop (docs/dev/bluetooth-link.md section 7.4): the first attempt at once, then after 1, 2, 4 and 8 seconds,
 * then every 10 seconds, and the series starts again after a success. The measured way back was 5.9 to 13.3 s (median 7.0 s) in steps of about 3 s, the timer of
 * the old guidance loop; the shorter steps take that timer out of the time. Times are milliseconds of a monotonic clock.
 */
internal class ReconnectBackoff(private val stepsMs: LongArray = longArrayOf(1_000, 2_000, 4_000, 8_000, 10_000)) {
    private var failures = 0
    private var lastAttemptMs = 0L
    private var attempted = false

    /** How long to wait after the last attempt before the next one. */
    fun delayMs(): Long = if (failures == 0) 0 else stepsMs[minOf(failures - 1, stepsMs.size - 1)]

    @Synchronized fun ready(nowMs: Long): Boolean = !attempted || nowMs - lastAttemptMs >= delayMs()

    @Synchronized fun attempt(nowMs: Long) { attempted = true; lastAttemptMs = nowMs }

    @Synchronized fun failed() { failures++ }

    @Synchronized fun succeeded() { failures = 0 }
}
