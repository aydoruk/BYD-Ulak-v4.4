package tr.ulak.byd.car

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class AutomationRuleState(
    val id: String,
    val title: String,
    val description: String,
    var enabled: Boolean,
    var lastTriggeredMs: Long = 0L,
)

/**
 * DashArc benzeri araç içi otomasyon motoru.
 * Koşullar sağlandığında bekleme süresi (cooldown) ile otomatik aksiyon üretir.
 */
class AutomationEngine(context: Context? = null) {

    companion object {
        private const val PREFS_NAME = "ulak_automations_v3"
        private const val COOLDOWN_MS = 5 * 60 * 1000L
        private val TR_LOCALE: Locale = Locale.forLanguageTag("tr-TR")
    }

    private val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val rules = linkedMapOf(
        "LOW_12V_PROTECT" to AutomationRuleState(
            id = "LOW_12V_PROTECT",
            title = "12V Akü Koruma Kalkanı",
            description = "12V akü 12.1V altına düşerse Nöbetçi Modunu otomatik durdurur.",
            enabled = true,
        ),
        "CABIN_OVERHEAT_VENT" to AutomationRuleState(
            id = "CABIN_OVERHEAT_VENT",
            title = "Kabin Aşırı Isınma Koruması",
            description = "Park halinde kabin 38°C üzerine çıkarsa klimayı/havalandırmayı tetikler.",
            enabled = true,
        ),
        "AUTO_LOCK_SENTRY" to AutomationRuleState(
            id = "AUTO_LOCK_SENTRY",
            title = "Kilitte Otomatik Nöbetçi",
            description = "Araç park halinde kilitlendiğinde Nöbetçi Modunu otomatik etkinleştirir.",
            enabled = false,
        ),
        "CHARGE_COMPLETE_ALERT" to AutomationRuleState(
            id = "CHARGE_COMPLETE_ALERT",
            title = "Tam Şarj Bildirimi",
            description = "LFP Blade batarya %100 doluluğa ulaştığında telefona uyarı gönderir.",
            enabled = true,
        ),
    )

    private var previousLockedState: Boolean? = null

    init {
        rules.values.forEach { rule ->
            val saved = prefs?.getBoolean("rule_${rule.id}", rule.enabled)
            if (saved != null) rule.enabled = saved
        }
    }

    @Synchronized
    fun setRuleEnabled(ruleId: String, enabled: Boolean): Boolean {
        val rule = rules[ruleId] ?: return false
        rule.enabled = enabled
        prefs?.edit()?.putBoolean("rule_$ruleId", enabled)?.apply()
        return true
    }

    @Synchronized
    fun evaluate(snapshot: JSONObject, bridge: DiLink3HardwareBridge): List<String> {
        val events = mutableListOf<String>()
        val now = snapshot.optLong("timestamp", System.currentTimeMillis())
        val aux12v = snapshot.optDouble("aux_12v_voltage", 13.8)
        val cabinTemp = snapshot.optDouble("cabin_temp_c", 22.0)
        val gear = snapshot.optString("gear", "P")
        val isLocked = snapshot.optBoolean("is_locked", true)
        val soc = snapshot.optInt("soc_percent", 0)
        val serviceMode = snapshot.optBoolean("service_mode", false)

        if (serviceMode) return events

        // 1. 12V Akü Koruma
        val low12vRule = rules["LOW_12V_PROTECT"]!!
        if (low12vRule.enabled && aux12v < 12.1 && bridge.sentryActiveFallback) {
            bridge.sentryActiveFallback = false
            low12vRule.lastTriggeredMs = now
            events.add("12V Akü Düşük (${String.format(TR_LOCALE, "%.2f", aux12v)}V) -> Nöbetçi Modu durduruldu")
        }

        // 2. Kabin Aşırı Isınma
        val overheatRule = rules["CABIN_OVERHEAT_VENT"]!!
        if (
            overheatRule.enabled &&
            gear == "P" &&
            cabinTemp >= 38.0 &&
            !bridge.acActiveFallback &&
            (now - overheatRule.lastTriggeredMs) > COOLDOWN_MS
        ) {
            overheatRule.lastTriggeredMs = now
            bridge.executeRemoteCommand(
                JSONObject().put("command", "SET_AC").put("enable", true).put("temp", 24.0),
            )
            events.add("Kabin ${String.format(TR_LOCALE, "%.1f", cabinTemp)}°C -> Otomatik serinletme başlatıldı")
        }

        // 3. Kilitlenince Otomatik Nöbetçi
        val autoSentryRule = rules["AUTO_LOCK_SENTRY"]!!
        if (autoSentryRule.enabled && gear == "P" && previousLockedState == false && isLocked && !bridge.sentryActiveFallback) {
            bridge.sentryActiveFallback = true
            autoSentryRule.lastTriggeredMs = now
            events.add("Araç kilitlendi -> Nöbetçi Modu otomatik açıldı")
        }
        previousLockedState = isLocked

        // 4. Tam Şarj Bildirimi
        val chargeRule = rules["CHARGE_COMPLETE_ALERT"]!!
        if (chargeRule.enabled && soc >= 100 && (now - chargeRule.lastTriggeredMs) > COOLDOWN_MS * 6) {
            chargeRule.lastTriggeredMs = now
            events.add("Batarya %100 dolu • Şarj tamamlandı")
        }

        return events
    }

    @Synchronized
    fun toJsonArray(): JSONArray {
        val arr = JSONArray()
        rules.values.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("title", r.title)
                    .put("description", r.description)
                    .put("enabled", r.enabled)
                    .put("last_triggered_ms", r.lastTriggeredMs),
            )
        }
        return arr
    }
}
