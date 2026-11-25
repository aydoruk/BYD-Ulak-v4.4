package tr.ulak.byd.mobile.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONObject
import tr.ulak.byd.mobile.model.*
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class UlakCarClient(initialSettings: ConnectionSettings) {

    companion object {
        const val UDP_PORT = 45454
        const val TCP_PORT = 45455
        const val MJPEG_PORT = 45456
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private val _settings = MutableStateFlow(initialSettings)
    val settings: StateFlow<ConnectionSettings> = _settings.asStateFlow()

    private val _telemetry = MutableStateFlow(TelemetryState())
    val telemetry: StateFlow<TelemetryState> = _telemetry.asStateFlow()

    private val _trips = MutableStateFlow<List<TripRecordItem>>(emptyList())
    val trips: StateFlow<List<TripRecordItem>> = _trips.asStateFlow()

    private val _voiceHistory = MutableStateFlow<List<VoiceMessageItem>>(
        listOf(
            VoiceMessageItem(
                isUser = false,
                text = "Merhaba! Ben Hey Ulak. Klima, camlar, menzil, lastik basıncı veya rota hakkında konuşabilirsin.",
            ),
        ),
    )
    val voiceHistory: StateFlow<List<VoiceMessageItem>> = _voiceHistory.asStateFlow()

    private val _liveCameraFrame = MutableStateFlow<Bitmap?>(null)
    val liveCameraFrame: StateFlow<Bitmap?> = _liveCameraFrame.asStateFlow()

    private val _streamStatus = MutableStateFlow("Beklemede")
    val streamStatus: StateFlow<String> = _streamStatus.asStateFlow()

    private val _streamTransport = MutableStateFlow("OTOMATİK")
    val streamTransport: StateFlow<String> = _streamTransport.asStateFlow()

    private var udpSocket: DatagramSocket? = null
    private var udpJob: Job? = null
    private var cloudWebSocket: WebSocket? = null
    private var cloudJob: Job? = null
    private var mjpegJob: Job? = null
    @Volatile private var cloudStreamRequested: Boolean = false

    private val pendingCloudAcks = ConcurrentHashMap<String, CompletableDeferred<CommandAck>>()

    fun start() {
        startUdpListener()
        startCloudRelayLoop()
    }

    fun updateSettings(newSettings: ConnectionSettings) {
        _settings.value = newSettings
        reconnectCloudRelay()
    }

    private fun startUdpListener() {
        udpJob?.cancel()
        udpJob = scope.launch {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(UDP_PORT))
                    soTimeout = 3000
                }
                udpSocket = socket
                val buffer = ByteArray(24576)
                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val senderIp = packet.address.hostAddress
                        val payload = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val json = JSONObject(payload)

                        if (!senderIp.isNullOrBlank() && _settings.value.carIp != senderIp) {
                            _settings.value = _settings.value.copy(carIp = senderIp)
                        }
                        _telemetry.value = TelemetryState.fromJson(
                            json = json,
                            current = _telemetry.value,
                            viaLan = true,
                            viaCloud = _telemetry.value.connectedViaCloud,
                        )
                    } catch (_: SocketTimeoutException) {
                        val elapsed = System.currentTimeMillis() - _telemetry.value.lastUpdateMs
                        if (elapsed > 8_000L && _telemetry.value.connectedViaLan) {
                            _telemetry.value = _telemetry.value.copy(connectedViaLan = false)
                        }
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) {
            } finally {
                try { udpSocket?.close() } catch (_: Exception) { }
                udpSocket = null
            }
        }
    }

    private fun startCloudRelayLoop() {
        cloudJob?.cancel()
        cloudJob = scope.launch {
            while (isActive) {
                val url = _settings.value.cloudRelayUrl.trim()
                if (url.isNotEmpty() && cloudWebSocket == null) {
                    connectCloudWebSocket(url)
                }
                delay(5_000L)
            }
        }
    }

    private fun reconnectCloudRelay() {
        cloudWebSocket?.close(1000, "Reconnect")
        cloudWebSocket = null
        _telemetry.value = _telemetry.value.copy(connectedViaCloud = false)
    }

    private fun connectCloudWebSocket(rawUrl: String) {
        try {
            val wsBase = rawUrl
                .replace("https://", "wss://")
                .replace("http://", "ws://")
                .trimEnd('/')
            val cfg = _settings.value
            val vid = URLEncoder.encode(cfg.vehicleId, "UTF-8")
            val tok = URLEncoder.encode(cfg.authToken, "UTF-8")
            val req = Request.Builder().url("$wsBase/ws?role=mobile&vehicleId=$vid&token=$tok").build()

            cloudWebSocket = httpClient.newWebSocket(
                req,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        _telemetry.value = _telemetry.value.copy(connectedViaCloud = true)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        try {
                            val msg = JSONObject(text)
                            when (msg.optString("type")) {
                                "TELEMETRY" -> {
                                    val payload = msg.optJSONObject("payload") ?: return
                                    _telemetry.value = TelemetryState.fromJson(
                                        json = payload,
                                        current = _telemetry.value,
                                        viaLan = _telemetry.value.connectedViaLan,
                                        viaCloud = true,
                                    )
                                }

                                "CAMERA_FRAME" -> {
                                    val b64 = msg.optString("data", "")
                                    if (b64.isNotEmpty()) {
                                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                        if (bmp != null) {
                                            _liveCameraFrame.value = bmp
                                            _streamTransport.value = "RENDER 4G/5G"
                                            _streamStatus.value = "CANLI BULUT AKIŞI (Render)"
                                        }
                                    }
                                }

                                "WEBRTC_ANSWER" -> {
                                    _streamTransport.value = "RENDER WEBRTC/WS"
                                    _streamStatus.value = msg.optString("message", "Render WebRTC/WS bağlı")
                                }

                                "ACK" -> {
                                    val reqId = msg.optString("request_id", "")
                                    val p = msg.optJSONObject("payload") ?: JSONObject()
                                    val ack = CommandAck(
                                        ok = p.optBoolean("ok", false),
                                        hardwareExecuted = p.optBoolean("hardware_executed", false),
                                        message = p.optString("message", "Bulut yanıtı alındı"),
                                        raw = p,
                                    )
                                    pendingCloudAcks.remove(reqId)?.complete(ack)
                                }
                            }
                        } catch (_: Exception) { }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        cloudWebSocket = null
                        _telemetry.value = _telemetry.value.copy(connectedViaCloud = false)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        cloudWebSocket = null
                        _telemetry.value = _telemetry.value.copy(connectedViaCloud = false)
                    }
                },
            )
        } catch (_: Exception) {
            cloudWebSocket = null
        }
    }

    suspend fun sendCommand(commandJson: JSONObject): CommandAck = withContext(Dispatchers.IO) {
        val cfg = _settings.value
        val enriched = JSONObject(commandJson.toString()).apply {
            put("auth_token", cfg.authToken)
        }

        // 1. LAN TCP (en düşük gecikme)
        val lanAck = trySendViaTcp(cfg.carIp, enriched)
        if (lanAck != null) {
            handleSpecialAck(enriched.optString("command"), lanAck)
            return@withContext lanAck
        }

        // 2. Render Bulut WebSocket (4G/5G her yerden)
        val ws = cloudWebSocket
        if (ws != null && _telemetry.value.connectedViaCloud) {
            val reqId = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<CommandAck>()
            pendingCloudAcks[reqId] = deferred
            val envelope = JSONObject()
                .put("type", "COMMAND")
                .put("request_id", reqId)
                .put("payload", enriched)
            ws.send(envelope.toString())
            val cloudAck = withTimeoutOrNull(14_000L) { deferred.await() }
            pendingCloudAcks.remove(reqId)
            if (cloudAck != null) {
                handleSpecialAck(enriched.optString("command"), cloudAck)
                return@withContext cloudAck
            }
        }

        CommandAck(
            ok = false,
            hardwareExecuted = false,
            message = "Araca ulaşılamadı (${cfg.carIp}:$TCP_PORT). Aynı Wi-Fi'de olun veya Render Bulut bağlantısını kontrol edin.",
        )
    }

    private fun handleSpecialAck(commandName: String, ack: CommandAck) {
        if (commandName == "GET_TRIPS") {
            _trips.value = TelemetryState.parseTrips(ack.raw?.optJSONArray("trips"))
        }
    }

    suspend fun askVoiceAssistant(prompt: String, speakOnCar: Boolean = true): CommandAck {
        val clean = prompt.trim()
        if (clean.isEmpty()) return CommandAck(false, false, "Boş komut")

        _voiceHistory.value = _voiceHistory.value + VoiceMessageItem(isUser = true, text = clean)

        val ack = sendCommand(
            JSONObject()
                .put("command", "VOICE_ASK")
                .put("prompt", clean)
                .put("speak_on_car", speakOnCar),
        )

        val voiceObj = ack.raw?.optJSONObject("voice")
        val actionsList = mutableListOf<String>()
        val actArr = voiceObj?.optJSONArray("executed_actions")
        if (actArr != null) {
            for (i in 0 until actArr.length()) actionsList.add(actArr.optString(i))
        }
        val replyText = voiceObj?.optString("reply")?.ifBlank { ack.message } ?: ack.message
        val usedGemini = voiceObj?.optBoolean("used_gemini", false) ?: false

        _voiceHistory.value = _voiceHistory.value + VoiceMessageItem(
            isUser = false,
            text = replyText,
            actions = actionsList,
            usedGemini = usedGemini,
        )
        return ack
    }

    private fun trySendViaTcp(carIp: String, payload: JSONObject): CommandAck? {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(carIp, TCP_PORT), 2200)
                socket.soTimeout = 15_000
                val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                writer.write(payload.toString() + "\n")
                writer.flush()
                val respLine = reader.readLine()
                if (!respLine.isNullOrBlank()) {
                    val obj = JSONObject(respLine)
                    CommandAck(
                        ok = obj.optBoolean("ok", true),
                        hardwareExecuted = obj.optBoolean("hardware_executed", false),
                        message = obj.optString("message", "Komut iletildi"),
                        raw = obj,
                    )
                } else {
                    CommandAck(true, false, "Komut iletildi")
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun startCameraStream(forceCloud: Boolean = false) {
        stopCameraStream()
        if (!forceCloud && _telemetry.value.connectedViaLan) {
            startLanMjpegStream()
        } else {
            startCloudRenderCameraStream()
        }
    }

    fun startCloudRenderCameraStream() {
        mjpegJob?.cancel()
        cloudStreamRequested = true
        _streamTransport.value = "RENDER 4G/5G"
        _streamStatus.value = "Render WebRTC/Bulut akışı isteniyor..."

        scope.launch {
            // WebRTC sinyalleşme teklifi + bulut canlı akışı başlat
            cloudWebSocket?.send(
                JSONObject()
                    .put("type", "WEBRTC_OFFER")
                    .put("peer_id", "s26_ultra")
                    .toString(),
            )
            val ack = sendCommand(JSONObject().put("command", "START_CLOUD_STREAM"))
            if (!ack.ok) {
                _streamStatus.value = ack.message
            }
        }
    }

    private fun startLanMjpegStream() {
        mjpegJob = scope.launch {
            val cfg = _settings.value
            val tokenParam = URLEncoder.encode(cfg.authToken, "UTF-8")
            val streamUrl = "http://${cfg.carIp}:$MJPEG_PORT/stream?token=$tokenParam"
            _streamTransport.value = "LAN MJPEG"
            _streamStatus.value = "LAN kameraya bağlanıyor..."

            try {
                val request = Request.Builder().url(streamUrl).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        _streamStatus.value = "Kamera HTTP Hatası: ${response.code}"
                        return@launch
                    }
                    _streamStatus.value = "CANLI LAN AKIŞ • ${cfg.carIp}:$MJPEG_PORT"
                    val input = BufferedInputStream(response.body?.byteStream() ?: return@launch)
                    val frameBuffer = ByteArrayOutputStream()
                    var prev = -1
                    var inJpeg = false

                    while (isActive) {
                        val b = input.read()
                        if (b == -1) break
                        if (!inJpeg) {
                            if (prev == 0xFF && b == 0xD8) {
                                frameBuffer.reset()
                                frameBuffer.write(0xFF)
                                frameBuffer.write(0xD8)
                                inJpeg = true
                            }
                        } else {
                            frameBuffer.write(b)
                            if (prev == 0xFF && b == 0xD9) {
                                val bytes = frameBuffer.toByteArray()
                                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bmp != null) {
                                    _liveCameraFrame.value = bmp
                                }
                                inJpeg = false
                            }
                        }
                        prev = b
                    }
                }
            } catch (_: Exception) {
                if (_telemetry.value.connectedViaCloud) {
                    startCloudRenderCameraStream()
                } else {
                    _streamStatus.value = "Akış kapalı (Bağlantı yok)"
                }
            }
        }
    }

    fun stopCameraStream() {
        mjpegJob?.cancel()
        mjpegJob = null
        if (cloudStreamRequested) {
            cloudStreamRequested = false
            scope.launch {
                sendCommand(JSONObject().put("command", "STOP_CLOUD_STREAM"))
            }
        }
        _streamStatus.value = "Durduruldu"
    }

    fun applySimulationTick(speed: Int, driveMode: String) {
        _telemetry.value = _telemetry.value.copy(
            speedKmh = speed,
            driveMode = driveMode,
            gear = if (speed > 0) "D" else "P",
        )
    }

    fun close() {
        stopCameraStream()
        udpJob?.cancel()
        cloudJob?.cancel()
        try { udpSocket?.close() } catch (_: Exception) { }
        cloudWebSocket?.close(1000, "Client closed")
        scope.cancel()
    }
}
