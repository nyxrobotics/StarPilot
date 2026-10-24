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
}
