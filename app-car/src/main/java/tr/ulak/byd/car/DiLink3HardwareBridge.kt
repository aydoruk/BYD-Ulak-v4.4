package tr.ulak.byd.car

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class CommandResult(
    val success: Boolean,
    val hardwareExecuted: Boolean,
    val message: String,
    val data: JSONObject? = null,
)

/**
 * BYD Seal U DM-i (2025) - DiLink 3.0 (Android 10) Gelişmiş Donanım Köprüsü
 *
 * 8 adet BydAuto donanım modülünü Reflection ile bağlar, hangi modüllerin gerçekte
 * yanıt verdiğini raporlar (hardware_live) ve donanım izin vermediğinde güvenli fallback uygular.
 */
class DiLink3HardwareBridge(private val context: Context? = null) {

    companion object {
        private const val TAG = "UlakDiLink3Bridge"
    }

    private var statisticDevice: Any? = null
    private var bodyworkDevice: Any? = null
    private var acDevice: Any? = null
    private var energyDevice: Any? = null
    private var gearboxDevice: Any? = null
    private var speedDevice: Any? = null
    private var tyreDevice: Any? = null
    private var chargingDevice: Any? = null

    private val moduleStatus = linkedMapOf<String, Boolean>()

    // Fallback / önbellek durum değişkenleri
    @Volatile var isLockedFallback: Boolean = true
    @Volatile var acActiveFallback: Boolean = false
    @Volatile var targetTempFallback: Double = 21.5
    @Volatile var frontDefrostFallback: Boolean = false
    @Volatile var rearDefrostFallback: Boolean = false
    @Volatile var sentryActiveFallback: Boolean = true
    @Volatile var antiTheftActiveFallback: Boolean = false
    @Volatile var serviceModeFallback: Boolean = false
    @Volatile var driverSeatVentFallback: Int = 0
    @Volatile var driverSeatHeatFallback: Int = 0
    @Volatile var passengerSeatVentFallback: Int = 0
    @Volatile var passengerSeatHeatFallback: Int = 0
    @Volatile var windowsOpenPercentFallback: Int = 0
    @Volatile var sunroofOpenPercentFallback: Int = 0
    @Volatile var mirrorsFoldedFallback: Boolean = true
    @Volatile var trunkOpenFallback: Boolean = false
    @Volatile var driveModeFallback: String = "EV"
    @Volatile var activeCameraIndex: Int = 0
    @Volatile var latitudeFallback: Double = 41.0082
    @Volatile var longitudeFallback: Double = 28.9784

    init {
        initDiLink3Devices()
    }

    private fun initDiLink3Devices() {
        statisticDevice = bindModule("statistic", "android.hardware.bydauto.statistic.BydAutoStatisticDevice")
        bodyworkDevice = bindModule("bodywork", "android.hardware.bydauto.bodywork.BydAutoBodyworkDevice")
        acDevice = bindModule("ac", "android.hardware.bydauto.ac.BydAutoAcDevice")
        energyDevice = bindModule("energy", "android.hardware.bydauto.energy.BydAutoEnergyDevice")
        gearboxDevice = bindModule("gearbox", "android.hardware.bydauto.gearbox.BydAutoGearboxDevice")
        speedDevice = bindModule("speed", "android.hardware.bydauto.speed.BydAutoSpeedDevice")
        tyreDevice = bindModule("tyre", "android.hardware.bydauto.tyre.BydAutoTyreDevice")
        chargingDevice = bindModule("charging", "android.hardware.bydauto.charging.BydAutoChargingDevice")

        val connectedCount = moduleStatus.values.count { it }
        Log.i(TAG, "ULAK DiLink 3.0 VHAL başlatıldı: $connectedCount/${moduleStatus.size} donanım modülü aktif.")
    }

    private fun bindModule(key: String, className: String): Any? {
        val ctx = context
        if (ctx == null) {
            moduleStatus[key] = false
            return null
        }
        return try {
            val clazz = Class.forName(className)
            val getInstance = clazz.getMethod("getInstance", Context::class.java)
            val instance = getInstance.invoke(null, ctx)
            moduleStatus[key] = instance != null
            instance
        } catch (_: Exception) {
            moduleStatus[key] = false
            Log.w(TAG, "Modül bulunamadı ($className) - Fallback aktif")
            null
        }
    }

    fun isHardwareLive(): Boolean = moduleStatus.values.any { it }

    fun getModuleDiagnostics(): JSONObject {
        return JSONObject().apply {
            put("hardware_live", isHardwareLive())
            put("connected_count", moduleStatus.values.count { it })
            put("total_modules", moduleStatus.size)
            val modulesObj = JSONObject()
            moduleStatus.forEach { (k, v) -> modulesObj.put(k, v) }
            put("modules", modulesObj)
        }
    }

    private fun invokeDoubleAny(instance: Any?, methodNames: List<String>, fallback: Double): Double {
        if (instance == null) return fallback
        for (name in methodNames) {
            try {
                val method = instance.javaClass.getMethod(name)
                val raw = (method.invoke(instance) as? Number)?.toDouble()
                if (raw != null && !raw.isNaN() && raw >= 0.0) return raw
            } catch (_: Exception) { }
        }
        return fallback
    }

    private fun invokeIntAny(instance: Any?, methodNames: List<String>, fallback: Int): Int {
        if (instance == null) return fallback
        for (name in methodNames) {
            try {
                val method = instance.javaClass.getMethod(name)
                val raw = (method.invoke(instance) as? Number)?.toInt()
                if (raw != null && raw >= 0) return raw
            } catch (_: Exception) { }
        }
        return fallback
    }

    private fun invokeIntWithArg(instance: Any?, methodNames: List<String>, arg: Int, fallback: Int): Int {
        if (instance == null) return fallback
        for (name in methodNames) {
            try {
                val method = instance.javaClass.getMethod(name, Int::class.javaPrimitiveType)
                val raw = (method.invoke(instance, arg) as? Number)?.toInt()
                if (raw != null && raw >= 0) return raw
            } catch (_: Exception) { }
        }
        return fallback
    }

    private fun invokeSetter(instance: Any?, methodNames: List<String>, vararg args: Int): Boolean {
        if (instance == null) return false
        val paramTypes = Array<Class<*>>(args.size) { Int::class.javaPrimitiveType!! }
        val boxedArgs = args.map { it as Any }.toTypedArray()
        for (name in methodNames) {
            try {
                val method = instance.javaClass.getMethod(name, *paramTypes)
                method.invoke(instance, *boxedArgs)
                return true
            } catch (_: Exception) { }
        }
        return false
    }

    fun updateLocation(lat: Double, lon: Double) {
        latitudeFallback = lat
        longitudeFallback = lon
    }

    fun readTelemetrySnapshot(
        cameraClients: Int = 0,
        lastMotionTimestamp: Long = 0L,
        cloudConnected: Boolean = false,
    ): JSONObject {
        val rawSoc = invokeDoubleAny(statisticDevice, listOf("getElecPercentageValue", "getElecPercentage"), 0.84)
        val socPercent = (if (rawSoc <= 1.0) (rawSoc * 100.0).toInt() else rawSoc.toInt()).coerceIn(0, 100)
        val evRangeKm = invokeIntAny(statisticDevice, listOf("getEVMileageValue", "getEvMileageValue"), (socPercent * 1.08).toInt())
        val fuelPercent = invokeIntAny(statisticDevice, listOf("getFuelPercentageValue", "getFuelPercentage"), 72).coerceIn(0, 100)
        val fuelRangeKm = invokeIntAny(statisticDevice, listOf("getFuelMileageValue"), (fuelPercent * 9.8).toInt())
        val totalOdometerKm = invokeDoubleAny(statisticDevice, listOf("getTotalMileageValue", "getTotalMileage"), 14280.0)
        val batteryVoltage12V = invokeDoubleAny(bodyworkDevice, listOf("getBatteryVoltage", "getBatteryVoltageLevel"), 13.8)
        // BYD DiLink 3.0 SDK içinde getTemprature yazım hatası bilinçli olarak ilk sırada denenir
        val cabinTemp = invokeDoubleAny(acDevice, listOf("getTemprature", "getTemperature", "getCabinTemperature"), 22.5)
        val currentSpeed = invokeDoubleAny(speedDevice, listOf("getCurrentSpeed"), 0.0)
        val operationMode = invokeIntAny(energyDevice, listOf("getOperationMode"), if (driveModeFallback == "EV") 0 else 1)
        val gearState = invokeIntAny(gearboxDevice, listOf("getGearboxAutoModeType"), 1)
        val chargingState = invokeIntAny(chargingDevice, listOf("getChargingState", "getChargerState"), 0)

        // 4 Tekerlek TPMS Lastik Basıncı (bar cinsinden ~2.5 bar / 250 kPa)
        val tpmsFL = invokeIntWithArg(tyreDevice, listOf("getTyrePressureValue"), 1, 250)
        val tpmsFR = invokeIntWithArg(tyreDevice, listOf("getTyrePressureValue"), 2, 250)
        val tpmsRL = invokeIntWithArg(tyreDevice, listOf("getTyrePressureValue"), 3, 250)
        val tpmsRR = invokeIntWithArg(tyreDevice, listOf("getTyrePressureValue"), 4, 248)

        val resolvedDriveMode = if (energyDevice != null) {
            if (operationMode == 0) "EV" else "HEV"
        } else {
            driveModeFallback
        }

        val resolvedGear = when (gearState) {
            1 -> "P"
            2 -> "R"
            3 -> "N"
            4 -> "D"
            else -> "P"
        }

        return JSONObject().apply {
            put("app", "ULAK")
            put("version", "3.0.0")
            put("vehicle", "BYD Seal U DM-i (2025)")
            put("system", "DiLink 3.0 (Android 10)")
            put("timestamp", System.currentTimeMillis())
            put("hardware_live", isHardwareLive())
            put("cloud_connected", cloudConnected)
            put("soc_percent", socPercent)
            put("ev_range_km", evRangeKm)
            put("fuel_percent", fuelPercent)
            put("hev_range_km", fuelRangeKm)
            put("combined_range_km", evRangeKm + fuelRangeKm)
            put("odometer_km", totalOdometerKm)
            put("aux_12v_voltage", batteryVoltage12V)
            put("cabin_temp_c", cabinTemp)
            put("target_temp_c", targetTempFallback)
            put("ac_active", acActiveFallback)
            put("front_defrost", frontDefrostFallback)
            put("rear_defrost", rearDefrostFallback)
            put("is_locked", isLockedFallback)
            put("sentry_active", sentryActiveFallback)
            put("anti_theft_active", antiTheftActiveFallback)
            put("service_mode", serviceModeFallback)
            put("driver_vent", driverSeatVentFallback)
            put("driver_heat", driverSeatHeatFallback)
            put("passenger_vent", passengerSeatVentFallback)
            put("passenger_heat", passengerSeatHeatFallback)
            put("windows_open_pct", windowsOpenPercentFallback)
            put("sunroof_open_pct", sunroofOpenPercentFallback)
            put("mirrors_folded", mirrorsFoldedFallback)
            put("trunk_open", trunkOpenFallback)
            put("charging_active", chargingState == 1)
            put("speed_kmh", currentSpeed.toInt())
            put("gear", resolvedGear)
            put("drive_mode", resolvedDriveMode)
            put("active_camera", activeCameraIndex)
            put("camera_clients", cameraClients)
            put("last_motion_ts", lastMotionTimestamp)
            put("lat", latitudeFallback)
            put("lon", longitudeFallback)
            put(
                "tpms_kpa",
                JSONObject()
                    .put("fl", tpmsFL)
                    .put("fr", tpmsFR)
                    .put("rl", tpmsRL)
                    .put("rr", tpmsRR),
            )
            put("diagnostics", getModuleDiagnostics())
        }
    }

    fun executeRemoteCommand(cmdJson: JSONObject): CommandResult {
        val action = cmdJson.optString("command", "").uppercase()
        return try {
            when (action) {
                "SET_AC" -> {
                    val enable = cmdJson.optBoolean("enable", false)
                    val temp = cmdJson.optDouble("temp", targetTempFallback).coerceIn(16.0, 32.0)
                    val frontDefrost = cmdJson.optBoolean("front_defrost", frontDefrostFallback)
                    val rearDefrost = cmdJson.optBoolean("rear_defrost", rearDefrostFallback)
                    acActiveFallback = enable
                    targetTempFallback = temp
                    frontDefrostFallback = frontDefrost
                    rearDefrostFallback = rearDefrost
                    val hwOk = setClimateControl(enable, temp.toInt(), frontDefrost, rearDefrost)
                    CommandResult(true, hwOk, if (enable) "Klima ${temp}°C olarak ayarlandı" else "Klima kapatıldı")
                }

                "SET_LOCK" -> {
                    val locked = cmdJson.optBoolean("locked", true)
                    isLockedFallback = locked
                    val hwOk = invokeSetter(
                        bodyworkDevice,
                        listOf("setDoorLock", "setAllDoorLockState", "lockAllDoors"),
                        if (locked) 1 else 0,
                    )
                    CommandResult(true, hwOk, if (locked) "Kapılar kilitlendi" else "Kapı kilitleri açıldı")
                }

                "SET_SENTRY" -> {
                    val enable = cmdJson.optBoolean("enable", true)
                    sentryActiveFallback = enable
                    CommandResult(true, true, if (enable) "Nöbetçi Modu devrede" else "Nöbetçi Modu durduruldu")
                }

                "SET_ANTI_THEFT" -> {
                    val enable = cmdJson.optBoolean("enable", false)
                    antiTheftActiveFallback = enable
                    if (enable) {
                        sentryActiveFallback = true
                        invokeSetter(bodyworkDevice, listOf("setAlarmState", "triggerHornAndLight"), 1)
                    } else {
                        invokeSetter(bodyworkDevice, listOf("setAlarmState", "triggerHornAndLight"), 0)
                    }
                    CommandResult(true, bodyworkDevice != null, if (enable) "Anti-Hırsızlık alarmı aktif!" else "Anti-Hırsızlık kapatıldı")
                }

                "SET_SERVICE_MODE" -> {
                    val enable = cmdJson.optBoolean("enable", false)
                    serviceModeFallback = enable
                    if (enable) {
                        sentryActiveFallback = false
                        antiTheftActiveFallback = false
                    }
                    CommandResult(true, true, if (enable) "Servis Modu aktif (Bildirim/Nöbetçi duraklatıldı)" else "Servis Modu kapatıldı")
                }

                "SET_SEAT" -> {
                    val zone = cmdJson.optString("zone", "driver").lowercase()
                    val vent = cmdJson.optInt("vent", -1)
                    val heat = cmdJson.optInt("heat", -1)
                    if (zone == "passenger") {
                        if (vent >= 0) passengerSeatVentFallback = vent.coerceIn(0, 3)
                        if (heat >= 0) passengerSeatHeatFallback = heat.coerceIn(0, 3)
                    } else {
                        if (vent >= 0) driverSeatVentFallback = vent.coerceIn(0, 3)
                        if (heat >= 0) driverSeatHeatFallback = heat.coerceIn(0, 3)
                    }
                    val zoneId = if (zone == "passenger") 1 else 0
                    val hwHeat = if (heat >= 0) invokeSetter(acDevice, listOf("setSeatHeatLevel", "setSeatHeating"), zoneId, heat.coerceIn(0, 3)) else false
                    val hwVent = if (vent >= 0) invokeSetter(acDevice, listOf("setSeatVentilationLevel", "setSeatVent"), zoneId, vent.coerceIn(0, 3)) else false
                    CommandResult(true, hwHeat || hwVent, "Koltuk konfor ayarları güncellendi")
                }

                "SET_WINDOWS" -> {
                    val percent = cmdJson.optInt("percent", 0).coerceIn(0, 100)
                    windowsOpenPercentFallback = percent
                    val hwOk = invokeSetter(bodyworkDevice, listOf("setAllWindowOpenPercent", "setWindowOpenPercent"), percent)
                    CommandResult(true, hwOk, if (percent == 0) "Tüm camlar kapatıldı" else "Camlar %$percent açıldı")
                }

                "SET_SUNROOF" -> {
                    val percent = cmdJson.optInt("percent", 0).coerceIn(0, 100)
                    sunroofOpenPercentFallback = percent
                    val hwOk = invokeSetter(bodyworkDevice, listOf("setSunroofPosition", "setSkylightOpenPercent"), percent)
                    CommandResult(true, hwOk, if (percent == 0) "Sunroof kapatıldı" else "Sunroof %$percent açıldı")
                }

                "SET_TRUNK" -> {
                    val open = cmdJson.optBoolean("open", !trunkOpenFallback)
                    trunkOpenFallback = open
                    val hwOk = invokeSetter(bodyworkDevice, listOf("setTrunkState", "openTrunk"), if (open) 1 else 0)
                    CommandResult(true, hwOk, if (open) "Bagaj açma komutu gönderildi" else "Bagaj kapatma komutu gönderildi")
                }

                "SET_MIRRORS" -> {
                    val folded = cmdJson.optBoolean("folded", !mirrorsFoldedFallback)
                    mirrorsFoldedFallback = folded
                    val hwOk = invokeSetter(bodyworkDevice, listOf("setMirrorFoldState", "foldRearViewMirror"), if (folded) 1 else 0)
                    CommandResult(true, hwOk, if (folded) "Aynalar katlandı" else "Aynalar açıldı")
                }

                "SET_DRIVE_MODE" -> {
                    val mode = cmdJson.optString("mode", "EV").uppercase()
                    driveModeFallback = mode
                    val modeInt = when (mode) {
                        "EV" -> 0
                        "HEV" -> 1
                        "ECO" -> 2
                        "SPORT" -> 3
                        else -> 0
                    }
                    val hwOk = invokeSetter(energyDevice, listOf("setOperationMode", "setDrivingMode"), modeInt)
                    CommandResult(true, hwOk, "Sürüş modu: $mode")
                }

                "FIND_CAR" -> {
                    val hwOk = invokeSetter(bodyworkDevice, listOf("flashLightsAndHonk", "findCar"), 1)
                    CommandResult(true, hwOk, "Araç bulma (Far/Korna) sinyali gönderildi")
                }

                "SWITCH_CAMERA" -> {
                    val camIndex = cmdJson.optInt("camera_index", 0).coerceIn(0, 3)
                    activeCameraIndex = camIndex
                    CommandResult(true, true, "Aktif kamera #$camIndex olarak değiştirildi")
                }

                "GET_DIAGNOSTICS" -> {
                    CommandResult(true, isHardwareLive(), "VHAL tanılama hazır", getModuleDiagnostics())
                }

                else -> CommandResult(false, false, "Bilinmeyen komut: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Komut yürütme hatası ($action): ${e.message}")
            CommandResult(false, false, "Hata: ${e.message}")
        }
    }

    fun setClimateControl(
        enable: Boolean,
        targetTempC: Int,
        frontDefrost: Boolean = false,
        rearDefrost: Boolean = false,
    ): Boolean {
        val dev = acDevice ?: return false
        var executed = false
        try {
            if (enable) {
                executed = invokeSetter(dev, listOf("start", "startAc", "startFrontAc"), 1) || executed
                executed = invokeSetter(dev, listOf("setAcTemperature", "setTemperature"), 0, targetTempC) || executed
                invokeSetter(dev, listOf("setAcDefrostState", "setFrontDefrost"), if (frontDefrost) 1 else 0)
                invokeSetter(dev, listOf("setRearDefrostState", "setRearDefrost"), if (rearDefrost) 1 else 0)
            } else {
                executed = invokeSetter(dev, listOf("stop", "stopAc"), 1) || executed
            }
        } catch (e: Exception) {
            Log.e(TAG, "DiLink 3.0 Klima komutu hatası: ${e.message}")
        }
        return executed
    }

    fun connectedModuleNames(): JSONArray {
        val arr = JSONArray()
        moduleStatus.filterValues { it }.keys.forEach { arr.put(it) }
        return arr
    }
}
