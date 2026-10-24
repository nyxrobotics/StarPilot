package link.firestar.companion

import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/** The only route data the companion puts into an Android Auto navigation notification. */
internal data class NavigationGuidance(val destination: String, val instruction: String, val distanceMeters: Double, val isMetric: Boolean) {
    fun distanceLabel(): String {
        if (!isMetric) {
            val feet = distanceMeters * 3.280839895
            return if (distanceMeters >= 1_609.344) String.format(Locale.US, "%.1f mi", distanceMeters / 1_609.344)
                else "${feet.roundToInt()} ft"
        }
        return if (distanceMeters >= 1_000.0) String.format(Locale.US, "%.1f km", distanceMeters / 1_000.0)
            else "${distanceMeters.roundToInt()} m"
    }
}

internal object NavigationGuidancePolicy {
    fun current(status: String?, destination: String?, instruction: String?, distanceMeters: Double?, isMetric: Boolean?): NavigationGuidance? {
        if (status !in setOf("guiding", "arrived")) return null
        val name = destination?.trim()?.takeIf { it.isNotEmpty() && it.length <= 160 && it.none(Char::isISOControl) } ?: return null
        val text = instruction?.trim()?.takeIf { it.isNotEmpty() && it.length <= 256 && it.none(Char::isISOControl) } ?: return null
        val distance = distanceMeters?.takeIf { it.isFinite() && it in 0.0..1_000_000.0 } ?: return null
        return NavigationGuidance(name, text, distance, isMetric ?: true)
    }

    fun parse(body: ByteArray): NavigationGuidance? {
        if (body.size > 4_096) return null
        return try {
            val status = JSONObject(String(body, Charsets.UTF_8))
            val instruction = status.optJSONObject("instruction")
            current(status.optString("status", ""), status.optString("destination", ""),
                instruction?.optString("text", ""), instruction?.optDouble("distanceMeters", Double.NaN),
                status.opt("isMetric") as? Boolean)
        } catch (_: Exception) { null }
    }
}
