package link.firestar.companion

import org.json.JSONArray
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

/** Route is null only when comma confirms that [routeKey] still names the cached line. */
internal data class NavigationMapSample(
    val guidance: NavigationGuidance?,
    val destinationCoordinates: DoubleArray?,
    val routeKey: String?,
    val routeCoordinates: DoubleArray?,
)

/** Keep the line across BLE's small unchanged replies and include it in every notification. */
internal class NavigationRouteCache {
    var routeKey: String? = null
        private set
    var coordinates = doubleArrayOf()
        private set

    fun apply(sample: NavigationMapSample) {
        when {
            sample.routeKey == null -> {
                routeKey = null
                coordinates = doubleArrayOf()
            }
            sample.routeCoordinates != null -> {
                routeKey = sample.routeKey
                coordinates = sample.routeCoordinates
            }
            routeKey != sample.routeKey -> {
                routeKey = null
                coordinates = doubleArrayOf()
            }
        }
    }

    fun queryPath(): String = "/api/navigation/guidance?map=1" + (routeKey?.takeIf { it.isNotEmpty() }?.let { "&routeKey=$it" } ?: "")
}

internal object NavigationGuidancePolicy {
    private const val MAX_MAP_REPLY_BYTES = 32_768
    private const val MAX_ROUTE_POINTS = 512
    private val ROUTE_KEY = Regex("[0-9a-f]{16}")

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
            guidanceOf(JSONObject(String(body, Charsets.UTF_8)))
        } catch (_: Exception) { null }
    }

    /** Parse only the opted-in bounded map reply; legacy comma replies have no route key. */
    fun parseMapSample(body: ByteArray): NavigationMapSample? {
        if (body.size > MAX_MAP_REPLY_BYTES) return null
        return try {
            val status = JSONObject(String(body, Charsets.UTF_8))
            val key = if (status.has("routeKey")) status.optString("routeKey", "").takeIf { it.isEmpty() || ROUTE_KEY.matches(it) }
                ?: return null else null
            val unchanged = status.opt("routeUnchanged") == true
            if (unchanged && key == null) return null
            val route = when {
                status.has("route") -> routeCoordinates(status.optJSONArray("route") ?: return null) ?: return null
                unchanged -> null
                key != null -> return null
                else -> null
            }
            val destination = when (val value = status.opt("destinationCoordinates")) {
                is JSONArray -> coordinatePair(value)
                else -> null
            }
            NavigationMapSample(guidanceOf(status), destination, key, route)
        } catch (_: Exception) { null }
    }

    private fun guidanceOf(status: JSONObject): NavigationGuidance? {
        val instruction = status.optJSONObject("instruction")
        return current(status.optString("status", ""), status.optString("destination", ""),
            instruction?.optString("text", ""), instruction?.optDouble("distanceMeters", Double.NaN),
            status.opt("isMetric") as? Boolean)
    }

    private fun coordinatePair(value: JSONArray): DoubleArray? {
        if (value.length() != 2) return null
        val longitude = (value.opt(0) as? Number)?.toDouble() ?: return null
        val latitude = (value.opt(1) as? Number)?.toDouble() ?: return null
        if (!longitude.isFinite() || !latitude.isFinite() || longitude !in -180.0..180.0 || latitude !in -90.0..90.0) return null
        return doubleArrayOf(longitude, latitude)
    }

    private fun routeCoordinates(value: JSONArray): DoubleArray? {
        if (value.length() > MAX_ROUTE_POINTS) return null
        val route = DoubleArray(value.length() * 2)
        for (index in 0 until value.length()) {
            val point = coordinatePair(value.optJSONArray(index) ?: return null) ?: return null
            route[index * 2] = point[0]
            route[index * 2 + 1] = point[1]
        }
        return route
    }
}
