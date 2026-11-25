package tr.ulak.byd.car

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class CloudRelayClient(
    private val config: UlakCarConfig,
    private val onRemoteCommand: suspend (JSONObject) -> JSONObject,
    private val onWebRtcSignaling: (JSONObject) -> JSONObject?,
    private val onConnectedCallback: () -> Unit = {},
) {

    companion object {
        private const val TAG = "UlakCloudRelay"
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: WebSocket? = null
    @Volatile private var connected: Boolean = false
    @Volatile private var running: Boolean = false

    fun isConnected(): Boolean = connected

    fun start() {
        if (running) return
        running = true
        scope.launch {
            while (isActive && running) {
                val baseUrl = config.cloudRelayUrl.trim()
                if (baseUrl.isNotEmpty() && !connected) {
                    connectOnce(baseUrl)
                }
                delay(5_000L)
            }
        }
    }

    private fun connectOnce(rawUrl: String) {
        try {
            val wsBase = rawUrl
                .replace("https://", "wss://")
                .replace("http://", "ws://")
                .trimEnd('/')
            val vid = URLEncoder.encode(config.vehicleId, "UTF-8")
            val tok = URLEncoder.encode(config.authToken, "UTF-8")
            val fullUrl = "$wsBase/ws?role=car&vehicleId=$vid&token=$tok"

            val request = Request.Builder().url(fullUrl).build()
            webSocket = client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        connected = true
                        Log.i(TAG, "Render bulut rölesine bağlanıldı: $wsBase")
                        scope.launch { onConnectedCallback() }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        scope.launch {
                            try {
                                val incoming = JSONObject(text)
                                val type = incoming.optString("type", "")
                                when {
                                    type == "WEBRTC_OFFER" || type == "WEBRTC_ICE" -> {
                                        val reply = onWebRtcSignaling(incoming)
                                        if (reply != null) {
                                            webSocket.send(reply.toString())
                                        }
                                    }

                                    type == "COMMAND" || incoming.has("command") -> {
                                        val cmdObj = incoming.optJSONObject("payload") ?: incoming
                                        val ack = onRemoteCommand(cmdObj)
                                        val envelope = JSONObject()
                                            .put("type", "ACK")
                                            .put("request_id", incoming.optString("request_id", ""))
                                            .put("payload", ack)
                                        webSocket.send(envelope.toString())
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Bulut mesaj işleme hatası: ${e.message}")
                            }
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        connected = false
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        connected = false
                        Log.w(TAG, "Bulut röle bağlantı hatası: ${t.message}")
                    }
                },
            )
        } catch (e: Exception) {
            connected = false
            Log.w(TAG, "Bulut röle URL hatası: ${e.message}")
        }
    }

    fun sendTelemetry(snapshot: JSONObject) {
        if (!connected) return
        val envelope = JSONObject()
            .put("type", "TELEMETRY")
            .put("payload", snapshot)
        webSocket?.send(envelope.toString())
    }

    fun sendCameraFrame(cameraIndex: Int, base64Jpeg: String) {
        if (!connected) return
        val envelope = JSONObject()
            .put("type", "CAMERA_FRAME")
            .put("camera_index", cameraIndex)
            .put("timestamp", System.currentTimeMillis())
            .put("data", base64Jpeg)
        webSocket?.send(envelope.toString())
    }

    fun sendAlert(title: String, message: String) {
        if (!connected) return
        val envelope = JSONObject()
            .put("type", "ALERT")
            .put("title", title)
            .put("message", message)
            .put("timestamp", System.currentTimeMillis())
        webSocket?.send(envelope.toString())
    }

    fun reconnect() {
        webSocket?.close(1000, "Config updated")
        connected = false
    }

    fun stop() {
        running = false
        connected = false
        webSocket?.close(1000, "Service stopped")
        scope.cancel()
    }
}
