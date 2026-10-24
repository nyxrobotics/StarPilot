package link.firestar.companion

import org.junit.Assert.*
import org.junit.Test

class NavigationGuidanceTest {
    @Test fun acceptsOnlyCurrentShortGuidance() {
        val current = NavigationGuidancePolicy.current("guiding", " Tokyo Station ", " Turn right ", 120.0, true)
        assertEquals("Tokyo Station", current?.destination)
        assertEquals("Turn right", current?.instruction)
        assertEquals("120 m", current?.distanceLabel())
        assertNull(NavigationGuidancePolicy.current("waitingForLocation", "Tokyo Station", "Turn right", 120.0, true))
        assertNull(NavigationGuidancePolicy.current("guiding", "Tokyo Station", "Turn right", Double.NaN, true))
        assertNull(NavigationGuidancePolicy.current("guiding", "Tokyo Station", "Turn\nright", 120.0, true))
    }

    @Test fun formatsDistancesForBothUnits() {
        assertEquals("1.2 km", NavigationGuidancePolicy.current("arrived", "Home", "Arrive", 1_200.0, true)?.distanceLabel())
        assertEquals("1.0 mi", NavigationGuidancePolicy.current("guiding", "Home", "Continue", 1_609.344, false)?.distanceLabel())
    }

    @Test fun parsesOnlySmallGuidanceReplies() {
        val body = """{"status":"guiding","destination":"駅","isMetric":true,"instruction":{"text":"右折","distanceMeters":150}}"""
        assertEquals("右折", NavigationGuidancePolicy.parse(body.toByteArray())?.instruction)
        assertNull(NavigationGuidancePolicy.parse(body.replace("guiding", "waitingForLocation").toByteArray()))
        assertNull(NavigationGuidancePolicy.parse(ByteArray(4_097)))
        assertNull(NavigationGuidancePolicy.parse("not json".toByteArray()))
    }

    @Test fun mapRepliesCarryBoundedCoordinatesAndKeepRouteAcrossUnchangedPolls() {
        val first = NavigationGuidancePolicy.parseMapSample("""{"status":"guiding","destination":"駅","isMetric":true,
            "instruction":{"text":"右折","distanceMeters":150},"destinationCoordinates":[139.7,35.6],
            "routeKey":"0123456789abcdef","route":[[139.7,35.6],[139.8,35.7]]}""".toByteArray())!!
        assertEquals("右折", first.guidance?.instruction)
        assertArrayEquals(doubleArrayOf(139.7, 35.6), first.destinationCoordinates, 0.0)
        assertArrayEquals(doubleArrayOf(139.7, 35.6, 139.8, 35.7), first.routeCoordinates, 0.0)
        val cache = NavigationRouteCache()
        cache.apply(first)
        assertEquals("/api/navigation/guidance?map=1&routeKey=0123456789abcdef", cache.queryPath())

        val unchanged = NavigationGuidancePolicy.parseMapSample("""{"status":"guiding","destination":"駅","isMetric":true,
            "instruction":{"text":"直進","distanceMeters":120},"destinationCoordinates":[139.7,35.6],
            "routeKey":"0123456789abcdef","routeUnchanged":true}""".toByteArray())!!
        assertNull(unchanged.routeCoordinates)
        cache.apply(unchanged)
        assertArrayEquals(doubleArrayOf(139.7, 35.6, 139.8, 35.7), cache.coordinates, 0.0)

        val cleared = NavigationGuidancePolicy.parseMapSample("""{"status":"noDestination","destination":null,
            "isMetric":true,"instruction":null,"destinationCoordinates":null,
            "routeKey":"fedcba9876543210","route":[]}""".toByteArray())!!
        cache.apply(cleared)
        assertEquals(0, cache.coordinates.size)
        assertNull(cleared.destinationCoordinates)
    }

    @Test fun mapRepliesRejectMalformedOrOversizedGeometry() {
        val bad = """{"routeKey":"0123456789abcdef","route":[[139.7,35.6],[-181,35.7]]}"""
        assertNull(NavigationGuidancePolicy.parseMapSample(bad.toByteArray()))
        assertNull(NavigationGuidancePolicy.parseMapSample(ByteArray(32_769)))
        val tooMany = """{"routeKey":"0123456789abcdef","route":[${List(513) { "[139,35]" }.joinToString(",") }]}"""
        assertNull(NavigationGuidancePolicy.parseMapSample(tooMany.toByteArray()))
    }
}
