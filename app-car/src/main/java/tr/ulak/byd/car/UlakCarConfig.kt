package tr.ulak.byd.car

import android.content.Context
import android.content.SharedPreferences

/**
 * ULAK v4.0 Araç Tarafı Kalıcı Yapılandırma
 * - Eşleştirme PIN, Bulut/WebRTC Render Röle URL
 * - Gemini Sesli Asistan ("Hey Ulak") API Anahtarı & Uyandırma Kelimesi
 * - Bluetooth Yakınlık Anahtarı (Phone as Key - 2 telefon, RSSI kalibrasyonu)
 * - Yetkili Servis Modu
 */
class UlakCarConfig(context: Context?) {

    companion object {
        private const val PREFS_NAME = "ulak_car_config_v4"
        const val DEFAULT_AUTH_TOKEN = "ULAK2025"
        const val DEFAULT_VEHICLE_ID = "BYD-SEAL-U-DMI-2025"
        const val DEFAULT_CLOUD_URL = "https://server-kk7c.onrender.com"
        const val DEFAULT_WAKE_WORD = "Hey Ulak"
        const val DEFAULT_PAK_UNLOCK_RSSI = -65
        const val DEFAULT_PAK_LOCK_RSSI = -82
        const val DEFAULT_PAK_AWAY_TIMEOUT_SEC = 12
    }

    private val prefs: SharedPreferences? =
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var authToken: String
        get() = prefs?.getString("auth_token", DEFAULT_AUTH_TOKEN) ?: DEFAULT_AUTH_TOKEN
        set(value) {
            prefs?.edit()?.putString("auth_token", value.trim().ifEmpty { DEFAULT_AUTH_TOKEN })?.apply()
        }

    var vehicleId: String
        get() = prefs?.getString("vehicle_id", DEFAULT_VEHICLE_ID) ?: DEFAULT_VEHICLE_ID
        set(value) {
            prefs?.edit()?.putString("vehicle_id", value.trim().ifEmpty { DEFAULT_VEHICLE_ID })?.apply()
        }

    var cloudRelayUrl: String
        get() = prefs?.getString("cloud_relay_url", DEFAULT_CLOUD_URL) ?: DEFAULT_CLOUD_URL
        set(value) {
            prefs?.edit()?.putString("cloud_relay_url", value.trim())?.apply()
        }

    var geminiApiKey: String
        get() = prefs?.getString("gemini_api_key", "") ?: ""
        set(value) {
            prefs?.edit()?.putString("gemini_api_key", value.trim())?.apply()
        }

    var voiceWakeWord: String
        get() = prefs?.getString("voice_wake_word", DEFAULT_WAKE_WORD) ?: DEFAULT_WAKE_WORD
        set(value) {
            prefs?.edit()?.putString("voice_wake_word", value.trim().ifEmpty { DEFAULT_WAKE_WORD })?.apply()
        }

    var voiceTtsEnabled: Boolean
        get() = prefs?.getBoolean("voice_tts_enabled", true) ?: true
        set(value) {
            prefs?.edit()?.putBoolean("voice_tts_enabled", value)?.apply()
        }

    // Bluetooth Phone as Key (PaK)
    var pakEnabled: Boolean
        get() = prefs?.getBoolean("pak_enabled", false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean("pak_enabled", value)?.apply()
        }

    var pakDevice1: String
        get() = prefs?.getString("pak_device_1", "Galaxy S26 Ultra") ?: "Galaxy S26 Ultra"
        set(value) {
            prefs?.edit()?.putString("pak_device_1", value.trim())?.apply()
        }

    var pakDevice2: String
        get() = prefs?.getString("pak_device_2", "") ?: ""
        set(value) {
            prefs?.edit()?.putString("pak_device_2", value.trim())?.apply()
        }

    var pakUnlockRssi: Int
        get() = prefs?.getInt("pak_unlock_rssi", DEFAULT_PAK_UNLOCK_RSSI) ?: DEFAULT_PAK_UNLOCK_RSSI
        set(value) {
            prefs?.edit()?.putInt("pak_unlock_rssi", value.coerceIn(-90, -40))?.apply()
        }

    var pakLockRssi: Int
        get() = prefs?.getInt("pak_lock_rssi", DEFAULT_PAK_LOCK_RSSI) ?: DEFAULT_PAK_LOCK_RSSI
        set(value) {
            prefs?.edit()?.putInt("pak_lock_rssi", value.coerceIn(-100, -55))?.apply()
        }

    var pakAwayTimeoutSec: Int
        get() = prefs?.getInt("pak_away_timeout_sec", DEFAULT_PAK_AWAY_TIMEOUT_SEC) ?: DEFAULT_PAK_AWAY_TIMEOUT_SEC
        set(value) {
            prefs?.edit()?.putInt("pak_away_timeout_sec", value.coerceIn(5, 60))?.apply()
        }

    var serviceModeEnabled: Boolean
        get() = prefs?.getBoolean("service_mode_enabled", false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean("service_mode_enabled", value)?.apply()
        }

    var serviceModeUntilMs: Long
        get() = prefs?.getLong("service_mode_until_ms", 0L) ?: 0L
        set(value) {
            prefs?.edit()?.putLong("service_mode_until_ms", value)?.apply()
        }

    fun isServiceModeActiveNow(): Boolean {
        if (!serviceModeEnabled) return false
        val until = serviceModeUntilMs
        if (until > 0L && System.currentTimeMillis() > until) {
            serviceModeEnabled = false
            serviceModeUntilMs = 0L
            return false
        }
        return true
    }

    fun getRegisteredPakTargets(): List<String> {
        return listOf(pakDevice1, pakDevice2).map { it.trim() }.filter { it.isNotEmpty() }
    }
}
