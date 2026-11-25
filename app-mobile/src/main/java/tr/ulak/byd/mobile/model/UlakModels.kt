package tr.ulak.byd.mobile.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

val TR_LOCALE: Locale = Locale.forLanguageTag("tr-TR")

data class TirePressures(
    val flKpa: Int = 250,
    val frKpa: Int = 250,
    val rlKpa: Int = 250,
    val rrKpa: Int = 248,
) {
    fun toBarString(kpa: Int, locale: Locale = TR_LOCALE): String = String.format(locale, "%.2f bar", kpa / 100.0)
}

data class AutomationRuleItem(
    val id: String,
    val title: String,
    val description: String,
    val enabled: Boolean,
    val lastTriggeredMs: Long = 0L,
)

data class CarEventItem(
    val type: String,
    val message: String,
    val timestamp: Long,
)

data class TripRecordItem(
    val id: Long,
    val startTs: Long,
    val endTs: Long,
    val durationSec: Long,
    val distanceKm: Double,
    val maxSpeedKmh: Int,
    val evRatioPct: Int,
    val hevRatioPct: Int,
    val socDelta: Int,
    val fuelDelta: Int,
)

data class PhoneAsKeyState(
    val enabled: Boolean = false,
    val unlockRssi: Int = -65,
    val lockRssi: Int = -82,
    val awayTimeoutSec: Int = 12,
    val registeredTargets: List<String> = listOf("Galaxy S26 Ultra"),
    val bestRssi: Int = -127,
    val estimatedDistanceM: Double = -1.0,
)

data class WebRtcState(
    val signalingState: String = "IDLE",
    val turnEnabled: Boolean = false,
    val cloudVideoStreaming: Boolean = false,
    val iceServerCount: Int = 1,
)

data class VoiceMessageItem(
    val isUser: Boolean,
    val text: String,
    val actions: List<String> = emptyList(),
    val usedGemini: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
)

data class CommandAck(
    val ok: Boolean,
    val hardwareExecuted: Boolean,
    val message: String,
    val raw: JSONObject? = null,
)

data class ConnectionSettings(
    val carIp: String = "192.168.43.1",
    val authToken: String = "ULAK2025",
    val vehicleId: String = "BYD-SEAL-U-DMI-2025",
    val cloudRelayUrl: String = "https://server-kk7c.onrender.com",
    val geminiApiKey: String = "",
)

data class TelemetryState(
    val connectedViaLan: Boolean = false,
    val connectedViaCloud: Boolean = false,
    val hardwareLive: Boolean = false,
    val lastUpdateMs: Long = 0L,
    val vehicleName: String = "BYD Seal U DM-i (2025)",
    val socPercent: Int = 84,
    val evRangeKm: Int = 91,
    val fuelPercent: Int = 72,
    val hevRangeKm: Int = 706,
    val combinedRangeKm: Int = 797,
    val odometerKm: Double = 14280.0,
    val aux12vVoltage: Float = 13.8f,
    val cabinTempC: Float = 22.5f,
    val targetTempC: Float = 21.5f,
    val acActive: Boolean = false,
    val frontDefrost: Boolean = false,
    val rearDefrost: Boolean = false,
    val isLocked: Boolean = true,
    val sentryActive: Boolean = true,
    val sentryStatus: String = "STANDBY",
    val antiTheftActive: Boolean = false,
    val serviceMode: Boolean = false,
    val driverVent: Int = 0,
    val driverHeat: Int = 0,
    val passengerVent: Int = 0,
    val passengerHeat: Int = 0,
    val windowsOpenPct: Int = 0,
    val sunroofOpenPct: Int = 0,
    val mirrorsFolded: Boolean = true,
    val trunkOpen: Boolean = false,
    val chargingActive: Boolean = false,
    val speedKmh: Int = 0,
    val gear: String = "P",
    val driveMode: String = "EV",
    val activeCameraIndex: Int = 0,
    val cameraHwActive: Boolean = false,
    val tripActive: Boolean = false,
    val voiceReady: Boolean = false,
    val voiceWakeWord: String = "Hey Ulak",
    val pak: PhoneAsKeyState = PhoneAsKeyState(),
    val webrtc: WebRtcState = WebRtcState(),
    val lat: Double = 41.0082,
    val lon: Double = 28.9784,
    val tpms: TirePressures = TirePressures(),
    val moduleDiagnostics: Map<String, Boolean> = emptyMap(),
    val automations: List<AutomationRuleItem> = defaultAutomations(),
    val recentEvents: List<CarEventItem> = emptyList(),
) {
    val isOnline: Boolean
        get() = connectedViaLan || connectedViaCloud

    companion object {
        fun defaultAutomations(): List<AutomationRuleItem> = listOf(
            AutomationRuleItem("LOW_12V_PROTECT", "12V Akü Koruma Kalkanı", "12V akü < 12.1V ise Nöbetçiyi durdurur.", true),
            AutomationRuleItem("CABIN_OVERHEAT_VENT", "Kabin Aşırı Isınma Koruması", "Parkta kabin > 38°C ise serinletme başlatır.", true),
            AutomationRuleItem("AUTO_LOCK_SENTRY", "Kilitte Otomatik Nöbetçi", "Araç kilitlenince Nöbetçiyi etkinleştirir.", false),
            AutomationRuleItem("CHARGE_COMPLETE_ALERT", "Tam Şarj Bildirimi", "Batarya %100 olduğunda uyarı gönderir.", true),
        )

        fun fromJson(json: JSONObject, current: TelemetryState, viaLan: Boolean, viaCloud: Boolean): TelemetryState {
            val tpmsObj = json.optJSONObject("tpms_kpa")
            val tpms = if (tpmsObj != null) {
                TirePressures(
                    flKpa = tpmsObj.optInt("fl", current.tpms.flKpa),
                    frKpa = tpmsObj.optInt("fr", current.tpms.frKpa),
                    rlKpa = tpmsObj.optInt("rl", current.tpms.rlKpa),
                    rrKpa = tpmsObj.optInt("rr", current.tpms.rrKpa),
                )
            } else {
                current.tpms
            }

            val pakObj = json.optJSONObject("pak_status")
            val pak = if (pakObj != null) {
                val targets = mutableListOf<String>()
                val tArr = pakObj.optJSONArray("registered_targets")
                if (tArr != null) {
                    for (i in 0 until tArr.length()) {
                        targets.add(tArr.optString(i))
                    }
                }
                PhoneAsKeyState(
                    enabled = pakObj.optBoolean("enabled", current.pak.enabled),
                    unlockRssi = pakObj.optInt("unlock_rssi", current.pak.unlockRssi),
                    lockRssi = pakObj.optInt("lock_rssi", current.pak.lockRssi),
                    awayTimeoutSec = pakObj.optInt("away_timeout_sec", current.pak.awayTimeoutSec),
                    registeredTargets = if (targets.isNotEmpty()) targets else current.pak.registeredTargets,
                    bestRssi = pakObj.optInt("best_rssi", current.pak.bestRssi),
                    estimatedDistanceM = pakObj.optDouble("estimated_distance_m", current.pak.estimatedDistanceM),
                )
            } else {
                current.pak
            }

            val wrtcObj = json.optJSONObject("webrtc_status")
            val webrtc = if (wrtcObj != null) {
                WebRtcState(
                    signalingState = wrtcObj.optString("signaling_state", current.webrtc.signalingState),
                    turnEnabled = wrtcObj.optBoolean("turn_enabled", current.webrtc.turnEnabled),
                    cloudVideoStreaming = wrtcObj.optBoolean("cloud_video_streaming", current.webrtc.cloudVideoStreaming),
                    iceServerCount = wrtcObj.optInt("ice_server_count", current.webrtc.iceServerCount),
                )
            } else {
                current.webrtc
            }

            val diagMap = linkedMapOf<String, Boolean>()
            val modulesObj = json.optJSONObject("diagnostics")?.optJSONObject("modules")
            modulesObj?.keys()?.forEach { key ->
                diagMap[key] = modulesObj.optBoolean(key, false)
            }

            val automations = parseAutomations(json.optJSONArray("automations")) ?: current.automations
            val events = parseEvents(json.optJSONArray("events")) ?: current.recentEvents

            val soc = json.optInt("soc_percent", current.socPercent)
            val fuel = json.optInt("fuel_percent", current.fuelPercent)
            val evRange = json.optInt("ev_range_km", current.evRangeKm)
            val hevRange = json.optInt("hev_range_km", current.hevRangeKm)

            return current.copy(
                connectedViaLan = viaLan || current.connectedViaLan,
                connectedViaCloud = viaCloud || json.optBoolean("cloud_connected", current.connectedViaCloud),
                hardwareLive = json.optBoolean("hardware_live", current.hardwareLive),
                lastUpdateMs = System.currentTimeMillis(),
                vehicleName = json.optString("vehicle", current.vehicleName),
                socPercent = soc,
                evRangeKm = evRange,
                fuelPercent = fuel,
                hevRangeKm = hevRange,
                combinedRangeKm = json.optInt("combined_range_km", evRange + hevRange),
                odometerKm = json.optDouble("odometer_km", current.odometerKm),
                aux12vVoltage = json.optDouble("aux_12v_voltage", current.aux12vVoltage.toDouble()).toFloat(),
                cabinTempC = json.optDouble("cabin_temp_c", current.cabinTempC.toDouble()).toFloat(),
                targetTempC = json.optDouble("target_temp_c", current.targetTempC.toDouble()).toFloat(),
                acActive = json.optBoolean("ac_active", current.acActive),
                frontDefrost = json.optBoolean("front_defrost", current.frontDefrost),
                rearDefrost = json.optBoolean("rear_defrost", current.rearDefrost),
                isLocked = json.optBoolean("is_locked", current.isLocked),
                sentryActive = json.optBoolean("sentry_active", current.sentryActive),
                sentryStatus = json.optString("sentry_status", current.sentryStatus),
                antiTheftActive = json.optBoolean("anti_theft_active", current.antiTheftActive),
                serviceMode = json.optBoolean("service_mode", current.serviceMode),
                driverVent = json.optInt("driver_vent", current.driverVent),
                driverHeat = json.optInt("driver_heat", current.driverHeat),
                passengerVent = json.optInt("passenger_vent", current.passengerVent),
                passengerHeat = json.optInt("passenger_heat", current.passengerHeat),
                windowsOpenPct = json.optInt("windows_open_pct", current.windowsOpenPct),
                sunroofOpenPct = json.optInt("sunroof_open_pct", current.sunroofOpenPct),
                mirrorsFolded = json.optBoolean("mirrors_folded", current.mirrorsFolded),
                trunkOpen = json.optBoolean("trunk_open", current.trunkOpen),
                chargingActive = json.optBoolean("charging_active", current.chargingActive),
                speedKmh = json.optInt("speed_kmh", current.speedKmh),
                gear = json.optString("gear", current.gear),
                driveMode = json.optString("drive_mode", current.driveMode),
                activeCameraIndex = json.optInt("active_camera", current.activeCameraIndex),
                cameraHwActive = json.optBoolean("camera_hw_active", current.cameraHwActive),
                tripActive = json.optBoolean("trip_active", current.tripActive),
                voiceReady = json.optBoolean("voice_ready", current.voiceReady),
                voiceWakeWord = json.optString("voice_wake_word", current.voiceWakeWord),
                pak = pak,
                webrtc = webrtc,
                lat = json.optDouble("lat", current.lat),
                lon = json.optDouble("lon", current.lon),
                tpms = tpms,
                moduleDiagnostics = if (diagMap.isNotEmpty()) diagMap else current.moduleDiagnostics,
                automations = automations,
                recentEvents = events,
            )
        }

        fun parseAutomations(arr: JSONArray?): List<AutomationRuleItem>? {
            if (arr == null) return null
            val list = mutableListOf<AutomationRuleItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(
                    AutomationRuleItem(
                        id = obj.optString("id"),
                        title = obj.optString("title"),
                        description = obj.optString("description"),
                        enabled = obj.optBoolean("enabled", false),
                        lastTriggeredMs = obj.optLong("last_triggered_ms", 0L),
                    ),
                )
            }
            return list
        }

        fun parseEvents(arr: JSONArray?): List<CarEventItem>? {
            if (arr == null) return null
            val list = mutableListOf<CarEventItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(
                    CarEventItem(
                        type = obj.optString("type"),
                        message = obj.optString("message"),
                        timestamp = obj.optLong("timestamp", 0L),
                    ),
                )
            }
            return list
        }

        fun parseTrips(arr: JSONArray?): List<TripRecordItem> {
            if (arr == null) return emptyList()
            val list = mutableListOf<TripRecordItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(
                    TripRecordItem(
                        id = obj.optLong("id", i.toLong()),
                        startTs = obj.optLong("start_ts", 0L),
                        endTs = obj.optLong("end_ts", 0L),
                        durationSec = obj.optLong("duration_sec", 0L),
                        distanceKm = obj.optDouble("distance_km", 0.0),
                        maxSpeedKmh = obj.optInt("max_speed_kmh", 0),
                        evRatioPct = obj.optInt("ev_ratio_pct", 100),
                        hevRatioPct = obj.optInt("hev_ratio_pct", 0),
                        socDelta = obj.optInt("soc_delta", 0),
                        fuelDelta = obj.optInt("fuel_delta", 0),
                    ),
                )
            }
            return list
        }
    }
}
