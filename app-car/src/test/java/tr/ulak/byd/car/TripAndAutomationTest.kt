package tr.ulak.byd.car

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TripAndAutomationTest {

    @Test
    fun testTripRecorder_recordsTripFromDriveToPark() {
        val recorder = TripRecorder()
        val startTs = 1_700_000_000_000L

        // Araç D vitesinde harekete başlar
        recorder.onTelemetryTick(
            JSONObject()
                .put("timestamp", startTs)
                .put("gear", "D")
                .put("speed_kmh", 40)
                .put("odometer_km", 1000.0)
                .put("soc_percent", 80)
                .put("fuel_percent", 70)
                .put("drive_mode", "EV"),
        )
        assertTrue(recorder.isTripActive())

        // Sürüş devam eder
        recorder.onTelemetryTick(
            JSONObject()
                .put("timestamp", startTs + 120_000L)
                .put("gear", "D")
                .put("speed_kmh", 85)
                .put("odometer_km", 1005.4)
                .put("soc_percent", 76)
                .put("fuel_percent", 70)
                .put("drive_mode", "EV"),
        )

        // Park (P) konumuna geçilir -> Yolculuk kaydedilir
        val trip = recorder.onTelemetryTick(
            JSONObject()
                .put("timestamp", startTs + 180_000L)
                .put("gear", "P")
                .put("speed_kmh", 0)
                .put("odometer_km", 1007.2)
                .put("soc_percent", 74)
                .put("fuel_percent", 70)
                .put("drive_mode", "EV"),
        )

        assertNotNull(trip)
        assertFalse(recorder.isTripActive())
        assertEquals(7.2, trip.getDouble("distance_km"), 0.1)
        assertEquals(85, trip.getInt("max_speed_kmh"))
        assertEquals(-6, trip.getInt("soc_delta"))
        assertEquals(1, recorder.getTripsJsonArray().length())
    }

    @Test
    fun testAutomationEngine_low12vPausesSentry() {
        val bridge = DiLink3HardwareBridge()
        bridge.sentryActiveFallback = true
        val engine = AutomationEngine()

        val events = engine.evaluate(
            JSONObject()
                .put("timestamp", System.currentTimeMillis())
                .put("aux_12v_voltage", 11.9)
                .put("cabin_temp_c", 22.0)
                .put("gear", "P")
                .put("is_locked", true)
                .put("soc_percent", 80),
            bridge,
        )

        assertFalse(bridge.sentryActiveFallback)
        assertTrue(events.any { it.contains("12V Akü Düşük") && it.contains("11,90V") })
    }
}
