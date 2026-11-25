package tr.ulak.byd.car

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

enum class PakAction { NONE, UNLOCK, LOCK }

data class TrackedPhoneRssi(
    val identifier: String,
    val rssi: Int,
    val lastSeenMs: Long,
    val consecutiveNearCount: Int,
)

/**
 * Saf mantık katmanı (birim testleriyle doğrulanabilir):
 * - En fazla 2 kayıtlı telefon takip edilir.
 * - Herhangi bir kayıtlı telefon unlockRssi (>= -65 dBm, ~2-3 m) üzerine üst üste 2 kez çıkarsa -> UNLOCK
 * - Tüm kayıtlı telefonlar lockRssi (<= -82 dBm) altına düşerse veya awayTimeoutSec boyunca görülmezse -> LOCK
 * - Sadece araç P vitesinde, 0 km/s hızda ve Servis Modu kapalıyken çalışır.
 */
class PhoneAsKeyStateMachine(
    private val unlockRssi: () -> Int,
    private val lockRssi: () -> Int,
    private val awayTimeoutMs: () -> Long,
) {
    companion object {
        private const val ACTION_COOLDOWN_MS = 8_000L
        private const val NEAR_CONFIRM_HITS = 2
    }

    private val seenPhones = ConcurrentHashMap<String, TrackedPhoneRssi>()
    @Volatile private var lastActionMs: Long = 0L
    @Volatile private var lastPresenceNear: Boolean = false

    fun recordSighting(identifier: String, rssi: Int, nowMs: Long = System.currentTimeMillis()) {
        val key = identifier.trim().uppercase()
        val prev = seenPhones[key]
        val isNear = rssi >= unlockRssi()
        val hits = if (isNear) ((prev?.consecutiveNearCount ?: 0) + 1).coerceAtMost(10) else 0
        seenPhones[key] = TrackedPhoneRssi(
            identifier = identifier.trim(),
            rssi = rssi,
            lastSeenMs = nowMs,
            consecutiveNearCount = hits,
        )
    }

    fun evaluate(
        enabled: Boolean,
        serviceMode: Boolean,
        gear: String,
        speedKmh: Int,
        currentlyLocked: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): PakAction {
        if (!enabled || serviceMode || gear != "P" || speedKmh > 0) return PakAction.NONE
        if (seenPhones.isEmpty()) return PakAction.NONE
        if (lastActionMs > 0L && (nowMs - lastActionMs) < ACTION_COOLDOWN_MS) return PakAction.NONE

        val timeout = awayTimeoutMs()
        val anyConfirmedNear = seenPhones.values.any { phone ->
            (nowMs - phone.lastSeenMs) <= timeout &&
                phone.rssi >= unlockRssi() &&
                phone.consecutiveNearCount >= NEAR_CONFIRM_HITS
        }

        val allAway = seenPhones.values.all { phone ->
            (nowMs - phone.lastSeenMs) > timeout || phone.rssi <= lockRssi()
        }

        if (anyConfirmedNear) {
            lastPresenceNear = true
            if (currentlyLocked) {
                lastActionMs = nowMs
                return PakAction.UNLOCK
            }
        } else if (allAway && lastPresenceNear) {
            lastPresenceNear = false
            if (!currentlyLocked) {
                lastActionMs = nowMs
                return PakAction.LOCK
            }
        }
        return PakAction.NONE
    }

    fun estimateDistanceMeters(rssi: Int): Double {
        val ratio = (-59.0 - rssi) / (10.0 * 2.2)
        return Math.round(10.0.pow(ratio) * 10.0) / 10.0
    }

    fun getStatusJson(enabled: Boolean, registeredTargets: List<String>, nowMs: Long = System.currentTimeMillis()): JSONObject {
        val phonesArr = JSONArray()
        var bestRssi = -127
        seenPhones.values.forEach { p ->
            val ageSec = ((nowMs - p.lastSeenMs) / 1000L).coerceAtLeast(0L)
            if (ageSec <= 20 && p.rssi > bestRssi) bestRssi = p.rssi
            phonesArr.put(
                JSONObject()
                    .put("id", p.identifier)
                    .put("rssi", p.rssi)
                    .put("distance_m", estimateDistanceMeters(p.rssi))
                    .put("age_sec", ageSec),
            )
        }
        return JSONObject()
            .put("enabled", enabled)
            .put("unlock_rssi", unlockRssi())
            .put("lock_rssi", lockRssi())
            .put("away_timeout_sec", awayTimeoutMs() / 1000L)
            .put("registered_targets", JSONArray(registeredTargets))
            .put("best_rssi", bestRssi)
            .put("estimated_distance_m", if (bestRssi > -120) estimateDistanceMeters(bestRssi) else -1.0)
            .put("seen_phones", phonesArr)
    }
}

class BluetoothPhoneAsKeyManager(
    private val context: Context,
    private val config: UlakCarConfig,
    private val bridge: DiLink3HardwareBridge,
    private val onPakEvent: (String) -> Unit,
) {
    companion object {
        private const val TAG = "UlakPhoneAsKey"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    val stateMachine = PhoneAsKeyStateMachine(
        unlockRssi = { config.pakUnlockRssi },
        lockRssi = { config.pakLockRssi },
        awayTimeoutMs = { config.pakAwayTimeoutSec * 1000L },
    )

    private var scanRunning = false
    private var receiverRegistered = false
    private var discoveryJob: Job? = null

    private val bleCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            handleDetectedDevice(device.name, device.address, result.rssi)
        }
    }

    private val classicReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_FOUND) {
                @Suppress("DEPRECATION")
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                if (rssi > -120) {
                    handleDetectedDevice(device.name, device.address, rssi)
                }
            }
        }
    }

    private fun handleDetectedDevice(name: String?, address: String?, rssi: Int) {
        val targets = config.getRegisteredPakTargets()
        if (targets.isEmpty()) return
        val matched = targets.firstOrNull { target ->
            (!address.isNullOrBlank() && address.equals(target, ignoreCase = true)) ||
                (!name.isNullOrBlank() && name.contains(target, ignoreCase = true))
        } ?: return

        stateMachine.recordSighting(matched, rssi)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!config.pakEnabled) return
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth kapalı veya desteklenmiyor - Phone as Key beklemede")
            return
        }

        if (!receiverRegistered) {
            try {
                context.registerReceiver(classicReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
                receiverRegistered = true
            } catch (_: Exception) { }
        }

        if (!scanRunning) {
            try {
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .build()
                adapter.bluetoothLeScanner?.startScan(null, settings, bleCallback)
                scanRunning = true
                Log.i(TAG, "Bluetooth Phone as Key BLE taraması başlatıldı.")
            } catch (e: Exception) {
                Log.w(TAG, "BLE taraması başlatılamadı: ${e.message}")
            }
        }

        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            while (isActive && config.pakEnabled) {
                try {
                    if (!adapter.isDiscovering) {
                        adapter.startDiscovery()
                    }
                } catch (_: Exception) { }
                delay(15_000L)
            }
        }
    }

    fun onTelemetryTick(snapshot: JSONObject) {
        val action = stateMachine.evaluate(
            enabled = config.pakEnabled,
            serviceMode = snapshot.optBoolean("service_mode", false),
            gear = snapshot.optString("gear", "P"),
            speedKmh = snapshot.optInt("speed_kmh", 0),
            currentlyLocked = snapshot.optBoolean("is_locked", true),
        )
        when (action) {
            PakAction.UNLOCK -> {
                bridge.executeRemoteCommand(JSONObject().put("command", "SET_LOCK").put("locked", false))
                onPakEvent("Phone as Key: Yaklaşıldı -> Kapı kilitleri otomatik açıldı")
            }
            PakAction.LOCK -> {
                bridge.executeRemoteCommand(JSONObject().put("command", "SET_LOCK").put("locked", true))
                onPakEvent("Phone as Key: Uzaklaşıldı -> Kapılar otomatik kilitlendi")
            }
            PakAction.NONE -> Unit
        }
    }

    fun getStatusJson(): JSONObject =
        stateMachine.getStatusJson(config.pakEnabled, config.getRegisteredPakTargets())

    fun reload() {
        stopScanningOnly()
        if (config.pakEnabled) start()
    }

    @SuppressLint("MissingPermission")
    private fun stopScanningOnly() {
        discoveryJob?.cancel()
        try {
            if (scanRunning) {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(bleCallback)
                scanRunning = false
            }
            bluetoothAdapter?.cancelDiscovery()
        } catch (_: Exception) { }
    }

    fun stop() {
        stopScanningOnly()
        if (receiverRegistered) {
            try { context.unregisterReceiver(classicReceiver) } catch (_: Exception) { }
            receiverRegistered = false
        }
        scope.cancel()
    }
}
