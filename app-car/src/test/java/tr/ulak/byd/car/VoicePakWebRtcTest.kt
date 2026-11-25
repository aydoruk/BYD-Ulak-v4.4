package tr.ulak.byd.car

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VoicePakWebRtcTest {

    @Test
    fun testGeminiVoiceAssistant_offlineTurkishCommandsWork(): Unit = runBlocking {
        val bridge = DiLink3HardwareBridge()
        val config = UlakCarConfig(null)
        val assistant = GeminiVoiceAssistant(null, config, bridge)

        val snap = bridge.readTelemetrySnapshot()
        val res = assistant.handlePrompt("Klimayı 21 derece aç ve camları kapat", snap, speakOnCar = false)

        assertTrue(res.ok)
        assertFalse(res.usedGemini)
        assertTrue(bridge.acActiveFallback)
        assertEquals(21.0, bridge.targetTempFallback, 0.01)
        assertEquals(0, bridge.windowsOpenPercentFallback)
        assertTrue(res.executedActions.size >= 2)
    }

    @Test
    fun testGeminiVoiceAssistant_rangeQueryAnswersWithTelemetry(): Unit = runBlocking {
        val bridge = DiLink3HardwareBridge()
        val config = UlakCarConfig(null)
        val assistant = GeminiVoiceAssistant(null, config, bridge)

        val res = assistant.handlePrompt("Menzilim ne kadar?", bridge.readTelemetrySnapshot(), speakOnCar = false)
        assertTrue(res.ok)
        assertTrue(res.reply.contains("menzil"))
        assertTrue(res.executedActions.isEmpty())
    }

    @Test
    fun testPhoneAsKey_unlockOnApproachAndLockOnWalkAway() {
        val sm = PhoneAsKeyStateMachine(
            unlockRssi = { -65 },
            lockRssi = { -82 },
            awayTimeoutMs = { 12_000L },
        )

        val t0 = 1_700_000_000_000L
        // 1. okuma (-60 dBm): Henüz 1 hit -> hemen açma
        sm.recordSighting("Galaxy S26 Ultra", -60, t0)
        assertEquals(PakAction.NONE, sm.evaluate(true, false, "P", 0, currentlyLocked = true, nowMs = t0))

        // 2. ardışık yakın okuma (-58 dBm): Kilit açılmalı!
        sm.recordSighting("Galaxy S26 Ultra", -58, t0 + 1_000L)
        assertEquals(PakAction.UNLOCK, sm.evaluate(true, false, "P", 0, currentlyLocked = true, nowMs = t0 + 1_000L))

        // Araç D vitesindeyken asla kilitleme/açma yapmamalı
        sm.recordSighting("Galaxy S26 Ultra", -90, t0 + 15_000L)
        assertEquals(PakAction.NONE, sm.evaluate(true, false, "D", 20, currentlyLocked = false, nowMs = t0 + 15_000L))

        // Park halinde sinyal zayıflayıp uzaklaşınca otomatik kilitlemeli
        assertEquals(PakAction.LOCK, sm.evaluate(true, false, "P", 0, currentlyLocked = false, nowMs = t0 + 20_000L))
    }

    @Test
    fun testWebRtcSignalingBridge_handlesOffer() {
        val config = UlakCarConfig(null)
        val bridge = WebRtcSignalingBridge(config)

        val ans = bridge.handleIncomingSignaling(
            JSONObject().put("type", "WEBRTC_OFFER").put("peer_id", "s26_ultra"),
        )
        assertNotNull(ans)
        assertEquals("WEBRTC_ANSWER", ans.getString("type"))
        assertEquals("s26_ultra", ans.getString("peer_id"))
        assertEquals("STREAMING_VIA_RENDER", bridge.signalingState)
    }
}
