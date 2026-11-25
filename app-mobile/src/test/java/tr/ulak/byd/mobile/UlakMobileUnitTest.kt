package tr.ulak.byd.mobile

import org.json.JSONArray
import org.json.JSONObject
import tr.ulak.byd.mobile.model.TelemetryState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UlakMobileUnitTest {

    @Test
    fun testTelemetryStateFromJson_parsesV4Fields() {
        val raw = JSONObject(
            """
            {
                "vehicle": "BYD Seal U DM-i (2025)",
                "hardware_live": true,
                "soc_percent": 78,
                "ev_range_km": 86,
                "fuel_percent": 65,
                "hev_range_km": 640,
                "combined_range_km": 726,
                "voice_ready": true,
                "voice_wake_word": "Hey Ulak",
                "pak_status": {
                    "enabled": true,
                    "unlock_rssi": -64,
                    "lock_rssi": -82,
                    "best_rssi": -59,
                    "estimated_distance_m": 1.0,
                    "registered_targets": ["Galaxy S26 Ultra"]
                },
                "webrtc_status": {
                    "signaling_state": "STREAMING_VIA_RENDER",
                    "turn_enabled": true,
                    "cloud_video_streaming": true,
                    "ice_server_count": 3
                },
                "tpms_kpa": { "fl": 252, "fr": 250, "rl": 248, "rr": 249 }
            }
            """.trimIndent(),
        )

        val state = TelemetryState.fromJson(raw, TelemetryState(), viaLan = false, viaCloud = true)

        assertTrue(state.connectedViaCloud)
        assertTrue(state.voiceReady)
        assertTrue(state.pak.enabled)
        assertEquals(-64, state.pak.unlockRssi)
        assertEquals(1.0, state.pak.estimatedDistanceM, 0.01)
        assertTrue(state.webrtc.turnEnabled)
        assertTrue(state.webrtc.cloudVideoStreaming)
        assertEquals("2,52 bar", state.tpms.toBarString(state.tpms.flKpa))
    }

    @Test
    fun testParseTripsJsonArray() {
        val arr = JSONArray().put(
            JSONObject()
                .put("id", 1L)
                .put("start_ts", 1000L)
                .put("end_ts", 2000L)
                .put("duration_sec", 900L)
                .put("distance_km", 18.5)
                .put("max_speed_kmh", 112)
                .put("ev_ratio_pct", 82)
                .put("hev_ratio_pct", 18)
                .put("soc_delta", -14)
                .put("fuel_delta", -1),
        )

        val trips = TelemetryState.parseTrips(arr)
        assertEquals(1, trips.size)
        assertEquals(18.5, trips[0].distanceKm, 0.01)
    }
}
