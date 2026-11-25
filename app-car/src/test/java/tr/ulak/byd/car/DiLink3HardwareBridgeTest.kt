package tr.ulak.byd.car

import org.json.JSONObject
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiLink3HardwareBridgeTest {

    private lateinit var bridge: DiLink3HardwareBridge

    @BeforeTest
    fun setUp() {
        bridge = DiLink3HardwareBridge()
    }

    @Test
    fun testReadTelemetrySnapshot_containsAllV3Keys() {
        val snapshot = bridge.readTelemetrySnapshot()

        assertEquals("ULAK", snapshot.getString("app"))
        assertEquals("3.0.0", snapshot.getString("version"))
        assertEquals("BYD Seal U DM-i (2025)", snapshot.getString("vehicle"))
        assertFalse(snapshot.getBoolean("hardware_live"))
        assertTrue(snapshot.has("soc_percent"))
        assertTrue(snapshot.has("ev_range_km"))
        assertTrue(snapshot.has("fuel_percent"))
        assertTrue(snapshot.has("hev_range_km"))
        assertTrue(snapshot.has("combined_range_km"))
        assertTrue(snapshot.has("tpms_kpa"))
        assertTrue(snapshot.has("diagnostics"))
        assertTrue(snapshot.has("anti_theft_active"))
        assertTrue(snapshot.has("service_mode"))
    }

    @Test
    fun testExecuteRemoteCommand_setAcAndDefrost() {
        val cmd = JSONObject()
            .put("command", "SET_AC")
            .put("enable", true)
            .put("temp", 22.0)
            .put("front_defrost", true)

        val result = bridge.executeRemoteCommand(cmd)

        assertTrue(result.success)
        assertTrue(bridge.acActiveFallback)
        assertEquals(22.0, bridge.targetTempFallback, 0.01)
        assertTrue(bridge.frontDefrostFallback)
    }

    @Test
    fun testExecuteRemoteCommand_bodyworkWindowsSunroofTrunkMirrors() {
        assertTrue(bridge.executeRemoteCommand(JSONObject().put("command", "SET_WINDOWS").put("percent", 20)).success)
        assertEquals(20, bridge.windowsOpenPercentFallback)

        assertTrue(bridge.executeRemoteCommand(JSONObject().put("command", "SET_SUNROOF").put("percent", 50)).success)
        assertEquals(50, bridge.sunroofOpenPercentFallback)

        assertTrue(bridge.executeRemoteCommand(JSONObject().put("command", "SET_TRUNK").put("open", true)).success)
        assertTrue(bridge.trunkOpenFallback)

        assertTrue(bridge.executeRemoteCommand(JSONObject().put("command", "SET_MIRRORS").put("folded", false)).success)
        assertFalse(bridge.mirrorsFoldedFallback)
    }

    @Test
    fun testExecuteRemoteCommand_antiTheftAndServiceMode() {
        val theftRes = bridge.executeRemoteCommand(JSONObject().put("command", "SET_ANTI_THEFT").put("enable", true))
        assertTrue(theftRes.success)
        assertTrue(bridge.antiTheftActiveFallback)
        assertTrue(bridge.sentryActiveFallback)

        val srvRes = bridge.executeRemoteCommand(JSONObject().put("command", "SET_SERVICE_MODE").put("enable", true))
        assertTrue(srvRes.success)
        assertTrue(bridge.serviceModeFallback)
        assertFalse(bridge.sentryActiveFallback)
        assertFalse(bridge.antiTheftActiveFallback)
    }

    @Test
    fun testExecuteRemoteCommand_passengerSeatAndDriveMode() {
        val seatRes = bridge.executeRemoteCommand(
            JSONObject().put("command", "SET_SEAT").put("zone", "passenger").put("vent", 2).put("heat", 1),
        )
        assertTrue(seatRes.success)
        assertEquals(2, bridge.passengerSeatVentFallback)
        assertEquals(1, bridge.passengerSeatHeatFallback)

        val modeRes = bridge.executeRemoteCommand(JSONObject().put("command", "SET_DRIVE_MODE").put("mode", "SPORT"))
        assertTrue(modeRes.success)
        assertEquals("SPORT", bridge.driveModeFallback)
    }

    @Test
    fun testExecuteRemoteCommand_unknownActionFails() {
        val result = bridge.executeRemoteCommand(JSONObject().put("command", "UNKNOWN_ACTION"))
        assertFalse(result.success)
    }
}
