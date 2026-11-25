package tr.ulak.byd.car

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * DashArc benzeri otomatik yolculuk kaydedici.
 * Vites P -> D/R geçişinde sürüş başlatır, D/R -> P geçişinde yolculuğu kaydeder.
 */
class TripRecorder(context: Context? = null) {

    companion object {
        private const val PREFS_NAME = "ulak_trips_v3"
        private const val KEY_TRIPS = "trips_json"
        private const val MAX_STORED_TRIPS = 50
    }

    private val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val trips = mutableListOf<JSONObject>()

    private var activeTripStartMs: Long? = null
    private var startOdometerKm: Double = 0.0
    private var startSoc: Int = 0
    private var startFuel: Int = 0
    private var maxSpeedKmh: Int = 0
    private var evSamples: Int = 0
    private var totalSamples: Int = 0
    private var integratedDistanceKm: Double = 0.0
    private var lastSampleMs: Long = 0L

    init {
        loadTrips()
    }

    private fun loadTrips() {
        val raw = prefs?.getString(KEY_TRIPS, null) ?: return
        try {
            val arr = JSONArray(raw)
            trips.clear()
            for (i in 0 until arr.length()) {
                trips.add(arr.getJSONObject(i))
            }
        } catch (_: Exception) { }
    }

    private fun persistTrips() {
        val arr = JSONArray()
        trips.take(MAX_STORED_TRIPS).forEach { arr.put(it) }
        prefs?.edit()?.putString(KEY_TRIPS, arr.toString())?.apply()
    }

    @Synchronized
    fun onTelemetryTick(snapshot: JSONObject): JSONObject? {
        val now = snapshot.optLong("timestamp", System.currentTimeMillis())
        val gear = snapshot.optString("gear", "P")
        val speed = snapshot.optInt("speed_kmh", 0)
        val odometer = snapshot.optDouble("odometer_km", 0.0)
        val soc = snapshot.optInt("soc_percent", 0)
        val fuel = snapshot.optInt("fuel_percent", 0)
        val driveMode = snapshot.optString("drive_mode", "EV")

        val isMovingGear = gear == "D" || gear == "R" || speed > 3

        if (activeTripStartMs == null && isMovingGear) {
            activeTripStartMs = now
            lastSampleMs = now
            startOdometerKm = odometer
            startSoc = soc
            startFuel = fuel
            maxSpeedKmh = speed
            evSamples = if (driveMode == "EV") 1 else 0
            totalSamples = 1
            integratedDistanceKm = 0.0
            return null
        }

        val startTime = activeTripStartMs ?: return null

        if (isMovingGear) {
            val dtHours = ((now - lastSampleMs).coerceAtLeast(0L)) / 3_600_000.0
            integratedDistanceKm += speed * dtHours
            lastSampleMs = now
            maxSpeedKmh = max(maxSpeedKmh, speed)
            totalSamples += 1
            if (driveMode == "EV") evSamples += 1
            return null
        }

        // Vites P oldu ve araç durdu -> Yolculuğu sonlandır
        val durationSec = ((now - startTime) / 1000L).coerceAtLeast(1L)
        val odoDelta = (odometer - startOdometerKm).coerceAtLeast(0.0)
        val distanceKm = if (odoDelta >= 0.1) odoDelta else integratedDistanceKm
        val evRatio = if (totalSamples > 0) ((evSamples * 100.0) / totalSamples).toInt() else 100

        activeTripStartMs = null

        // Çok kısa park manevralarını (ör. < 0.05 km ve < 10 sn) kaydetme
        if (distanceKm < 0.05 && durationSec < 10L) return null

        val completed = JSONObject().apply {
            put("id", now)
            put("start_ts", startTime)
            put("end_ts", now)
            put("duration_sec", durationSec)
            put("distance_km", Math.round(distanceKm * 10.0) / 10.0)
            put("max_speed_kmh", maxSpeedKmh)
            put("ev_ratio_pct", evRatio.coerceIn(0, 100))
            put("hev_ratio_pct", (100 - evRatio).coerceIn(0, 100))
            put("soc_delta", soc - startSoc)
            put("fuel_delta", fuel - startFuel)
        }
        trips.add(0, completed)
        if (trips.size > MAX_STORED_TRIPS) {
            trips.removeAt(trips.lastIndex)
        }
        persistTrips()
        return completed
    }

    @Synchronized
    fun isTripActive(): Boolean = activeTripStartMs != null

    @Synchronized
    fun getTripsJsonArray(): JSONArray {
        val arr = JSONArray()
        trips.forEach { arr.put(it) }
        return arr
    }
}
