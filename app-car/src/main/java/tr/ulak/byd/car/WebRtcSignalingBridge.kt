package tr.ulak.byd.car

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Render Sunucusu (/api/ice-config + /ws) için WebRTC Sinyalleşme & TURN/ICE Köprüsü.
 */
class WebRtcSignalingBridge(private val config: UlakCarConfig) {

    companion object {
        private const val TAG = "UlakWebRtcBridge"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Volatile var iceServersJson: JSONArray = defaultIceServers()
        private set

    @Volatile var signalingState: String = "IDLE"
        private set

    @Volatile var activeRemotePeer: String = ""
        private set

    @Volatile var turnConfigured: Boolean = false
        private set

    private fun defaultIceServers(): JSONArray {
        return JSONArray().put(
            JSONObject().put("urls", "stun:stun.l.google.com:19302"),
        )
    }

    fun refreshIceConfigFromRender() {
        val baseUrl = config.cloudRelayUrl.trim().trimEnd('/')
        if (baseUrl.isEmpty()) return
        try {
            val req = Request.Builder().url("$baseUrl/api/ice-config").build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return
                val body = resp.body?.string() ?: return
                val obj = JSONObject(body)
                val servers = obj.optJSONArray("iceServers")
                if (servers != null && servers.length() > 0) {
                    iceServersJson = servers
                    turnConfigured = obj.optBoolean("turnEnabled", false)
                    Log.i(TAG, "Render ICE/TURN yapılandırması alındı (TURN=$turnConfigured)")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ICE config alınamadı: ${e.message}")
        }
    }

    fun handleIncomingSignaling(message: JSONObject): JSONObject? {
        val type = message.optString("type", "")
        return when (type) {
            "WEBRTC_OFFER" -> {
                signalingState = "STREAMING_VIA_RENDER"
                activeRemotePeer = message.optString("peer_id", "mobile")
                JSONObject()
                    .put("type", "WEBRTC_ANSWER")
                    .put("peer_id", activeRemotePeer)
                    .put("mode", "RENDER_HYBRID_STREAM")
                    .put("iceServers", iceServersJson)
                    .put("turn_enabled", turnConfigured)
                    .put("message", "DiLink 3.0 Render WebRTC/WebSocket canlı akışı başlatıldı")
            }

            "WEBRTC_ICE" -> {
                signalingState = "ICE_RECEIVED"
                null
            }

            else -> null
        }
    }

    fun getStatusJson(cloudStreamActive: Boolean): JSONObject {
        return JSONObject()
            .put("signaling_state", signalingState)
            .put("turn_enabled", turnConfigured)
            .put("cloud_video_streaming", cloudStreamActive)
            .put("ice_server_count", iceServersJson.length())
    }
}
