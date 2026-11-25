package tr.ulak.byd.car

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

class UlakDiLink3Service : Service() {

    companion object {
        private const val TAG = "UlakService"
        const val UDP_PORT = 45454
        const val TCP_PORT = 45455
        const val ACTION_RELOAD_CONFIG = "tr.ulak.byd.car.RELOAD_CONFIG"
        const val ACTION_VOICE_PROMPT = "tr.ulak.byd.car.VOICE_PROMPT"
        const val EXTRA_PROMPT = "prompt"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var config: UlakCarConfig
    private lateinit var hardwareBridge: DiLink3HardwareBridge
    private lateinit var tripRecorder: TripRecorder
    private lateinit var automationEngine: AutomationEngine
    private lateinit var voiceAssistant: GeminiVoiceAssistant
    private lateinit var phoneAsKeyManager: BluetoothPhoneAsKeyManager
    private lateinit var webRtcBridge: WebRtcSignalingBridge
    private lateinit var cameraStreamer: CarCameraStreamer
    private lateinit var cloudRelay: CloudRelayClient

    private var wakeLock: PowerManager.WakeLock? = null
    private var serverSocket: ServerSocket? = null
    private val recentEvents = CopyOnWriteArrayList<JSONObject>()

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: android.location.Location) {
            hardwareBridge.updateLocation(location.latitude, location.longitude)
        }
        @Deprecated("Deprecated in Android API")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        config = UlakCarConfig(applicationContext)
        hardwareBridge = DiLink3HardwareBridge(applicationContext)
        hardwareBridge.serviceModeFallback = config.isServiceModeActiveNow()
        tripRecorder = TripRecorder(applicationContext)
        automationEngine = AutomationEngine(applicationContext)
        voiceAssistant = GeminiVoiceAssistant(applicationContext, config, hardwareBridge)
        webRtcBridge = WebRtcSignalingBridge(config)

        phoneAsKeyManager = BluetoothPhoneAsKeyManager(
            context = applicationContext,
            config = config,
            bridge = hardwareBridge,
            onPakEvent = { msg ->
                pushEvent("PHONE_AS_KEY", msg)
                cloudRelay.sendAlert("ULAK Bluetooth Anahtar", msg)
            },
        )

        cloudRelay = CloudRelayClient(
            config = config,
            onRemoteCommand = { cmd -> processCommand(cmd, skipAuth = true) },
            onWebRtcSignaling = { msg ->
                val ans = webRtcBridge.handleIncomingSignaling(msg)
                if (msg.optString("type") == "WEBRTC_OFFER") {
                    cameraStreamer.setCloudStreaming(true)
                }
                ans
            },
            onConnectedCallback = {
                webRtcBridge.refreshIceConfigFromRender()
            },
        )

        cameraStreamer = CarCameraStreamer(
            context = applicationContext,
            config = config,
            isSentryEnabled = { hardwareBridge.sentryActiveFallback && !config.isServiceModeActiveNow() },
            onMotionDetected = { ts ->
                pushEvent("SENTRY_MOTION", "Nöbetçi kamera hareket algıladı", ts)
                cloudRelay.sendAlert("ULAK Nöbetçi Uyarısı", "Park halindeki aracın çevresinde hareket algılandı!")
            },
            onCloudFrameReady = { camIndex, b64 ->
                cloudRelay.sendCameraFrame(camIndex, b64)
            },
        )

        acquireSafeWakeLock()
        startForeground(1001, createNotification())
        startLocationUpdates()
        cameraStreamer.start()
        cloudRelay.start()
        phoneAsKeyManager.start()
        startTelemetryBroadcastLoop()
        startCommandServerLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RELOAD_CONFIG -> {
                hardwareBridge.serviceModeFallback = config.isServiceModeActiveNow()
                phoneAsKeyManager.reload()
                cloudRelay.reconnect()
            }
            ACTION_VOICE_PROMPT -> {
                val prompt = intent.getStringExtra(EXTRA_PROMPT).orEmpty()
                if (prompt.isNotBlank()) {
                    serviceScope.launch {
                        val snap = buildEnrichedTelemetry()
                        val res = voiceAssistant.handlePrompt(prompt, snap, speakOnCar = true)
                        pushEvent("VOICE_AI", "Hey Ulak: ${res.reply}")
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun acquireSafeWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UlakCar::DiLink3TelemetryWakeLock")
        wakeLock?.acquire(12 * 60 * 60 * 1000L)
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5_000L, 10f, locationListener, mainLooper)
        } catch (_: Exception) { }
    }

    private fun pushEvent(type: String, message: String, ts: Long = System.currentTimeMillis()) {
        recentEvents.add(
            0,
            JSONObject()
                .put("type", type)
                .put("message", message)
                .put("timestamp", ts),
        )
        while (recentEvents.size > 30) {
            recentEvents.removeAt(recentEvents.lastIndex)
        }
    }

    private fun buildEnrichedTelemetry(): JSONObject {
        hardwareBridge.serviceModeFallback = config.isServiceModeActiveNow()
        val snapshot = hardwareBridge.readTelemetrySnapshot(
            cameraClients = cameraStreamer.getActiveClientCount(),
            lastMotionTimestamp = cameraStreamer.getLastMotionTimestamp(),
            cloudConnected = cloudRelay.isConnected(),
        )
        snapshot.put("version", "4.0.0")
        val auxVoltage = snapshot.optDouble("aux_12v_voltage", 13.8)
        val sentryStatus = when {
            hardwareBridge.serviceModeFallback -> "SERVICE_MODE"
            auxVoltage < 12.1 -> "PAUSED_LOW_12V"
            hardwareBridge.sentryActiveFallback -> "ACTIVE"
            else -> "STANDBY"
        }
        snapshot.put("sentry_status", sentryStatus)
        snapshot.put("trip_active", tripRecorder.isTripActive())
        snapshot.put("camera_hw_active", cameraStreamer.isCameraHardwareActive())
        snapshot.put("voice_ready", config.geminiApiKey.isNotBlank())
        snapshot.put("voice_wake_word", config.voiceWakeWord)
        snapshot.put("pak_status", phoneAsKeyManager.getStatusJson())
        snapshot.put("webrtc_status", webRtcBridge.getStatusJson(cameraStreamer.isCloudStreamingActive()))
        snapshot.put("automations", automationEngine.toJsonArray())
        snapshot.put("events", JSONArray(recentEvents.take(10)))
        return snapshot
    }

    private fun startTelemetryBroadcastLoop() {
        serviceScope.launch {
            while (isActive) {
                val snapshot = buildEnrichedTelemetry()

                val completedTrip = tripRecorder.onTelemetryTick(snapshot)
                if (completedTrip != null) {
                    pushEvent("TRIP_SAVED", "Yolculuk kaydedildi (${completedTrip.optDouble("distance_km")} km)")
                }

                val triggeredAutomations = automationEngine.evaluate(snapshot, hardwareBridge)
                triggeredAutomations.forEach { msg ->
                    pushEvent("AUTOMATION", msg)
                    cloudRelay.sendAlert("ULAK Otomasyon", msg)
                }

                phoneAsKeyManager.onTelemetryTick(snapshot)

                val payload = snapshot.toString()
                broadcastToUlakMobile(payload)
                cloudRelay.sendTelemetry(snapshot)

                delay(1500.milliseconds)
            }
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private fun startCommandServerLoop() {
        serviceScope.launch {
            try {
                serverSocket = ServerSocket(TCP_PORT)
                while (isActive) {
                    val client = serverSocket?.accept() ?: break
                    launch {
                        try {
                            client.soTimeout = 18_000
                            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                            val writer = OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8)
                            val line = reader.readLine()
                            if (!line.isNullOrBlank()) {
                                val cmd = JSONObject(line)
                                val response = processCommand(cmd, skipAuth = false)
                                writer.write(response.toString() + "\n")
                                writer.flush()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Komut okuma hatası: ${e.message}")
                        } finally {
                            try { client.close() } catch (_: Exception) { }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TCP Komut Sunucusu hatası: ${e.message}")
            }
        }
    }

    private suspend fun processCommand(cmd: JSONObject, skipAuth: Boolean): JSONObject {
        val expectedToken = config.authToken
        val providedToken = cmd.optString("auth_token", "")
        if (!skipAuth && expectedToken.isNotBlank() && providedToken != expectedToken) {
            return JSONObject()
                .put("ok", false)
                .put("hardware_executed", false)
                .put("message", "Yetkisiz istek: Eşleştirme PIN kodu eşleşmiyor.")
        }

        val action = cmd.optString("command", "").uppercase()
        return when (action) {
            "VOICE_ASK" -> {
                val prompt = cmd.optString("prompt", "")
                val speakOnCar = cmd.optBoolean("speak_on_car", true)
                val result = voiceAssistant.handlePrompt(prompt, buildEnrichedTelemetry(), speakOnCar)
                pushEvent("VOICE_AI", "Hey Ulak: ${result.reply}")
                JSONObject()
                    .put("ok", result.ok)
                    .put("hardware_executed", result.executedActions.isNotEmpty())
                    .put("message", result.reply)
                    .put("voice", result.toJson())
            }

            "SET_VOICE_CONFIG" -> {
                if (cmd.has("gemini_api_key")) {
                    config.geminiApiKey = cmd.optString("gemini_api_key", "")
                }
                if (cmd.has("wake_word")) {
                    config.voiceWakeWord = cmd.optString("wake_word", UlakCarConfig.DEFAULT_WAKE_WORD)
                }
                if (cmd.has("tts_enabled")) {
                    config.voiceTtsEnabled = cmd.optBoolean("tts_enabled", true)
                }
                JSONObject()
                    .put("ok", true)
                    .put("hardware_executed", true)
                    .put("message", "Hey Ulak sesli asistan ayarları kaydedildi")
            }

            "SET_PAK_CONFIG" -> {
                if (cmd.has("enabled")) config.pakEnabled = cmd.optBoolean("enabled", false)
                if (cmd.has("device_1")) config.pakDevice1 = cmd.optString("device_1", "")
                if (cmd.has("device_2")) config.pakDevice2 = cmd.optString("device_2", "")
                if (cmd.has("unlock_rssi")) config.pakUnlockRssi = cmd.optInt("unlock_rssi", UlakCarConfig.DEFAULT_PAK_UNLOCK_RSSI)
                if (cmd.has("lock_rssi")) config.pakLockRssi = cmd.optInt("lock_rssi", UlakCarConfig.DEFAULT_PAK_LOCK_RSSI)
                if (cmd.has("away_timeout_sec")) config.pakAwayTimeoutSec = cmd.optInt("away_timeout_sec", UlakCarConfig.DEFAULT_PAK_AWAY_TIMEOUT_SEC)
                phoneAsKeyManager.reload()
                JSONObject()
                    .put("ok", true)
                    .put("hardware_executed", true)
                    .put("message", if (config.pakEnabled) "Bluetooth Phone as Key aktif edildi" else "Bluetooth Phone as Key kapatıldı")
                    .put("pak_status", phoneAsKeyManager.getStatusJson())
            }

            "START_CLOUD_STREAM" -> {
                cameraStreamer.setCloudStreaming(true)
                JSONObject()
                    .put("ok", true)
                    .put("hardware_executed", true)
                    .put("message", "Render bulut canlı kamera akışı başlatıldı")
            }

            "STOP_CLOUD_STREAM" -> {
                cameraStreamer.setCloudStreaming(false)
                JSONObject()
                    .put("ok", true)
                    .put("hardware_executed", true)
                    .put("message", "Render bulut canlı kamera akışı durduruldu")
            }

            "GET_TRIPS" -> JSONObject()
                .put("ok", true)
                .put("hardware_executed", true)
                .put("message", "Yolculuk kayıtları gönderildi")
                .put("trips", tripRecorder.getTripsJsonArray())

            "SET_AUTOMATION" -> {
                val ruleId = cmd.optString("rule_id", "")
                val enabled = cmd.optBoolean("enabled", true)
                val updated = automationEngine.setRuleEnabled(ruleId, enabled)
                JSONObject()
                    .put("ok", updated)
                    .put("hardware_executed", updated)
                    .put("message", if (updated) "Otomasyon kuralı güncellendi" else "Kural bulunamadı")
                    .put("automations", automationEngine.toJsonArray())
            }

            "SWITCH_CAMERA" -> {
                val idx = cmd.optInt("camera_index", 0).coerceIn(0, 3)
                cameraStreamer.switchCamera(idx)
                val res = hardwareBridge.executeRemoteCommand(cmd)
                JSONObject()
                    .put("ok", res.success)
                    .put("hardware_executed", res.hardwareExecuted)
                    .put("message", res.message)
            }

            "SET_SERVICE_MODE" -> {
                val enable = cmd.optBoolean("enable", false)
                val hours = cmd.optInt("duration_hours", 24).coerceIn(1, 720)
                config.serviceModeEnabled = enable
                config.serviceModeUntilMs = if (enable) System.currentTimeMillis() + (hours * 3_600_000L) else 0L
                val res = hardwareBridge.executeRemoteCommand(cmd)
                JSONObject()
                    .put("ok", res.success)
                    .put("hardware_executed", res.hardwareExecuted)
                    .put("message", res.message)
            }

            else -> {
                val res = hardwareBridge.executeRemoteCommand(cmd)
                JSONObject()
                    .put("ok", res.success)
                    .put("hardware_executed", res.hardwareExecuted)
                    .put("message", res.message)
                    .apply { if (res.data != null) put("data", res.data) }
            }
        }
    }

    private fun broadcastToUlakMobile(jsonPayload: String) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply { broadcast = true }
            val data = jsonPayload.toByteArray(Charsets.UTF_8)
            for (addr in resolveBroadcastAddresses()) {
                try {
                    socket.send(DatagramPacket(data, data.size, addr, UDP_PORT))
                } catch (_: Exception) { }
            }
        } catch (_: Exception) {
        } finally {
            try { socket?.close() } catch (_: Exception) { }
        }
    }

    private fun resolveBroadcastAddresses(): Set<InetAddress> {
        val result = linkedSetOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || intf.isLoopback) continue
                for (ifa in intf.interfaceAddresses) {
                    ifa.broadcast?.let { result.add(it) }
                }
            }
        } catch (_: Exception) { }
        try {
            result.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) { }
        return result
    }

    private fun createNotification(content: String = "ULAK v4.0 • Hey Ulak AI • Phone as Key • Render Canlı Kamera"): Notification {
        val channelId = "ulak_dilink3_channel"
        val channel = NotificationChannel(channelId, "Ulak DiLink 3.0 Servisi", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        return Notification.Builder(this, channelId)
            .setContentTitle("ULAK v4.0 • BYD Seal U DM-i (DiLink 3.0)")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    override fun onDestroy() {
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            lm.removeUpdates(locationListener)
        } catch (_: Exception) { }
        phoneAsKeyManager.stop()
        voiceAssistant.shutdown()
        cameraStreamer.stop()
        cloudRelay.stop()
        try { serverSocket?.close() } catch (_: Exception) { }
        serviceScope.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
