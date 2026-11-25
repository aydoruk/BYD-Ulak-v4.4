package tr.ulak.byd.car

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

data class VoiceAssistantResult(
    val ok: Boolean,
    val reply: String,
    val usedGemini: Boolean,
    val executedActions: List<String>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("ok", ok)
        .put("reply", reply)
        .put("used_gemini", usedGemini)
        .put("executed_actions", JSONArray(executedActions))
}

/**
 * "Hey Ulak" — Gemini API Destekli Akıllı Araç Sesli Asistanı
 *
 * - Kullanıcının kendi Google Gemini API anahtarıyla (gemini-2.5-flash) çalışır.
 * - Araç telemetrisini (menzil, batarya, yakıt, TPMS, kabin ısısı) bağlam olarak gönderir.
 * - Doğal Türkçe konuşmayı yapılandırılmış araç komutlarına çevirip DiLink 3.0 üzerinde yürütür
 *   (Klima, camlar, sunroof, kilit, koltuk, bagaj, sürüş modu, Nöbetçi, Waze/Google Maps rota).
 * - İnternet veya API anahtarı yoksa yerel Türkçe çevrimdışı komut motoru devreye girer.
 */
class GeminiVoiceAssistant(
    private val context: Context?,
    private val config: UlakCarConfig,
    private val bridge: DiLink3HardwareBridge,
) {

    companion object {
        private const val TAG = "UlakVoiceAI"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val GEMINI_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady: Boolean = false

    init {
        if (context != null) {
            try {
                tts = TextToSpeech(context) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        tts?.language = Locale.forLanguageTag("tr-TR")
                        ttsReady = true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "TTS başlatılamadı: ${e.message}")
            }
        }
    }

    suspend fun handlePrompt(
        userPrompt: String,
        telemetrySnapshot: JSONObject,
        speakOnCar: Boolean = true,
    ): VoiceAssistantResult = withContext(Dispatchers.IO) {
        val cleaned = userPrompt.trim()
        if (cleaned.isEmpty()) {
            return@withContext VoiceAssistantResult(false, "Komut boş olamaz.", false, emptyList())
        }

        val apiKey = config.geminiApiKey.trim()
        val parsed = if (apiKey.isNotEmpty()) {
            queryGemini(apiKey, cleaned, telemetrySnapshot) ?: runOfflineTurkishParser(cleaned, telemetrySnapshot)
        } else {
            runOfflineTurkishParser(cleaned, telemetrySnapshot)
        }

        val executedSummaries = mutableListOf<String>()
        for (i in 0 until parsed.actions.length()) {
            val actionObj = parsed.actions.optJSONObject(i) ?: continue
            val cmdName = actionObj.optString("command", "").uppercase()
            if (cmdName == "OPEN_NAVIGATION") {
                val dest = actionObj.optString("destination", "").trim()
                if (dest.isNotEmpty()) {
                    val launched = launchNavigationOnHeadUnit(dest)
                    executedSummaries.add(if (launched) "Navigasyon: $dest" else "Navigasyon isteği: $dest")
                }
            } else if (cmdName.isNotEmpty()) {
                val res = bridge.executeRemoteCommand(actionObj)
                if (res.success) {
                    executedSummaries.add(res.message)
                }
            }
        }

        if (speakOnCar && config.voiceTtsEnabled && parsed.reply.isNotBlank()) {
            speak(parsed.reply)
        }

        VoiceAssistantResult(
            ok = true,
            reply = parsed.reply,
            usedGemini = parsed.usedGemini,
            executedActions = executedSummaries,
        )
    }

    internal data class ParsedAiPlan(
        val reply: String,
        val actions: JSONArray,
        val usedGemini: Boolean,
    )

    private fun queryGemini(apiKey: String, prompt: String, telemetry: JSONObject): ParsedAiPlan? {
        return try {
            val compactTelemetry = JSONObject()
                .put("soc_percent", telemetry.optInt("soc_percent"))
                .put("ev_range_km", telemetry.optInt("ev_range_km"))
                .put("fuel_percent", telemetry.optInt("fuel_percent"))
                .put("hev_range_km", telemetry.optInt("hev_range_km"))
                .put("combined_range_km", telemetry.optInt("combined_range_km"))
                .put("cabin_temp_c", telemetry.optDouble("cabin_temp_c"))
                .put("target_temp_c", telemetry.optDouble("target_temp_c"))
                .put("ac_active", telemetry.optBoolean("ac_active"))
                .put("is_locked", telemetry.optBoolean("is_locked"))
                .put("sentry_active", telemetry.optBoolean("sentry_active"))
                .put("aux_12v_voltage", telemetry.optDouble("aux_12v_voltage"))
                .put("drive_mode", telemetry.optString("drive_mode"))
                .put("gear", telemetry.optString("gear"))
                .put("tpms_kpa", telemetry.optJSONObject("tpms_kpa"))

            val systemInstruction = buildString {
                appendLine("Sen BYD Seal U DM-i (2025) araç içi sesli asistanı ULAK olarak görev yapıyorsun. Kısa, net, doğal Türkçe konuş.")
                appendLine("Sadece geçerli bir JSON nesnesi döndür (markdown kod bloğu kullanma):")
                appendLine("""{"reply":"Sürücüye sesli okunacak kısa Türkçe yanıt","actions":[]}""")
                appendLine("Desteklenen komutlar:")
                appendLine("""- {"command":"SET_AC","enable":true,"temp":21,"front_defrost":false,"rear_defrost":false}""")
                appendLine("""- {"command":"SET_LOCK","locked":true}""")
                appendLine("""- {"command":"SET_WINDOWS","percent":0}""")
                appendLine("""- {"command":"SET_SUNROOF","percent":50}""")
                appendLine("""- {"command":"SET_TRUNK","open":true}""")
                appendLine("""- {"command":"SET_MIRRORS","folded":true}""")
                appendLine("""- {"command":"SET_SEAT","zone":"driver","vent":2,"heat":0}""")
                appendLine("""- {"command":"SET_SENTRY","enable":true}""")
                appendLine("""- {"command":"SET_DRIVE_MODE","mode":"EV"}""")
                appendLine("""- {"command":"FIND_CAR"}""")
                appendLine("""- {"command":"OPEN_NAVIGATION","destination":"hedef yer adı"}""")
                appendLine("Sadece bilgi soruluyorsa actions boş dizi [] olsun.")
                append("Güncel araç telemetrisi: ").append(compactTelemetry.toString())
            }

            val bodyJson = JSONObject()
                .put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))),
                )
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", prompt))),
                    ),
                )
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("temperature", 0.2)
                        .put("responseMimeType", "application/json"),
                )

            val request = Request.Builder()
                .url("$GEMINI_ENDPOINT?key=$apiKey")
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Gemini HTTP ${resp.code} - çevrimdışı ayrıştırıcıya geçiliyor")
                    return null
                }
                val raw = resp.body?.string() ?: return null
                val root = JSONObject(raw)
                val text = root
                    .optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text", "")
                    ?.trim()
                    ?: return null

                val cleanJson = text
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()

                val obj = JSONObject(cleanJson)
                ParsedAiPlan(
                    reply = obj.optString("reply", "Komutunuz işlendi."),
                    actions = obj.optJSONArray("actions") ?: JSONArray(),
                    usedGemini = true,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gemini çağrısı başarısız: ${e.message}")
            null
        }
    }

    /**
     * İnternet veya Gemini API anahtarı yokken temel Türkçe araç komutlarını anında yürütür.
     */
    internal fun runOfflineTurkishParser(prompt: String, telemetry: JSONObject): ParsedAiPlan {
        val trLocale = Locale.forLanguageTag("tr-TR")
        val actions = JSONArray()
        val replyParts = mutableListOf<String>()

        // Bileşik komutları ("... ve ...", virgül, noktalı virgül) ayrı cümleciklere böl
        val clauses = prompt
            .split(Regex("""\s+(?:ve|veya|sonra|ardından)\s+|[,;]+""", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(prompt.trim()) }

        for (rawClause in clauses) {
            val clause = rawClause.lowercase(trLocale)

            if (clause.contains("menzil") || clause.contains("şarj") || clause.contains("batarya") || clause.contains("yakıt")) {
                val soc = telemetry.optInt("soc_percent", 0)
                val evKm = telemetry.optInt("ev_range_km", 0)
                val hevKm = telemetry.optInt("hev_range_km", 0)
                val totalKm = telemetry.optInt("combined_range_km", evKm + hevKm)
                replyParts.add("Batarya %$soc ile $evKm km elektrikli, toplam $totalKm km karma menziliniz var.")
            }

            if (clause.contains("lastik") || clause.contains("basınç") || clause.contains("tpms")) {
                val tpms = telemetry.optJSONObject("tpms_kpa")
                val fl = (tpms?.optInt("fl", 250) ?: 250) / 100.0
                val fr = (tpms?.optInt("fr", 250) ?: 250) / 100.0
                val flStr = String.format(trLocale, "%.2f", fl)
                val frStr = String.format(trLocale, "%.2f", fr)
                replyParts.add("Lastik basınçları normal, önler $flStr ve $frStr bar.")
            }

            if (clause.contains("klima") || clause.contains("serinlet") || clause.contains("ısıt") || clause.contains("derece")) {
                val tempMatch = Regex("""(\d{2})""").find(clause)
                val temp = tempMatch?.groupValues?.get(1)?.toDoubleOrNull()?.coerceIn(16.0, 32.0) ?: 22.0
                val disable = clause.contains("kapat") || clause.contains("durdur")
                actions.put(JSONObject().put("command", "SET_AC").put("enable", !disable).put("temp", temp))
                replyParts.add(if (disable) "Klimayı kapatıyorum." else "Klimayı ${temp.toInt()} dereceye ayarlıyorum.")
            }

            if (clause.contains("cam")) {
                val open = (clause.contains("aç") || clause.contains("havalandır")) && !clause.contains("kapat")
                actions.put(JSONObject().put("command", "SET_WINDOWS").put("percent", if (open) 20 else 0))
                replyParts.add(if (open) "Camları havalandırma konumunda açıyorum." else "Tüm camları kapatıyorum.")
            }

            if (clause.contains("sunroof") || clause.contains("tavan")) {
                val open = clause.contains("aç") && !clause.contains("kapat")
                actions.put(JSONObject().put("command", "SET_SUNROOF").put("percent", if (open) 50 else 0))
                replyParts.add(if (open) "Sunroof açılıyor." else "Sunroof kapatılıyor.")
            }

            if (clause.contains("kilitle") || (clause.contains("kapı") && (clause.contains("aç") || clause.contains("kapat")))) {
                val lock = clause.contains("kilitle") || clause.contains("kapat")
                actions.put(JSONObject().put("command", "SET_LOCK").put("locked", lock))
                replyParts.add(if (lock) "Kapıları kilitliyorum." else "Kapı kilitlerini açıyorum.")
            }

            if (clause.contains("bagaj")) {
                val open = !clause.contains("kapat")
                actions.put(JSONObject().put("command", "SET_TRUNK").put("open", open))
                replyParts.add(if (open) "Bagaj kapağını açıyorum." else "Bagajı kapatıyorum.")
            }

            if (clause.contains("nöbetçi") || clause.contains("sentry")) {
                val enable = !clause.contains("kapat") && !clause.contains("durdur")
                actions.put(JSONObject().put("command", "SET_SENTRY").put("enable", enable))
                replyParts.add(if (enable) "Nöbetçi modunu etkinleştiriyorum." else "Nöbetçi modunu kapatıyorum.")
            }

            if (clause.contains("götür") || clause.contains("rota") || clause.contains("waze") || clause.contains("navigasyon")) {
                val dest = rawClause
                    .replace(Regex("(?i)(beni|waze ile|waze'de|navigasyon|rota oluştur|götür|git)"), "")
                    .trim()
                    .ifEmpty { "En yakın şarj istasyonu" }
                actions.put(JSONObject().put("command", "OPEN_NAVIGATION").put("destination", dest))
                replyParts.add("$dest için navigasyonu başlatıyorum.")
            }
        }

        val finalReply = if (replyParts.isNotEmpty()) {
            replyParts.joinToString(" ")
        } else {
            "Çevrimdışı moddayım. Daha doğal sohbet için Sistem ayarlarından Gemini API anahtarınızı ekleyebilirsiniz."
        }

        return ParsedAiPlan(finalReply, actions, usedGemini = false)
    }

    private fun launchNavigationOnHeadUnit(destination: String): Boolean {
        val ctx = context ?: return false
        val encoded = Uri.encode(destination)
        return try {
            val wazeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?q=$encoded&navigate=yes")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(wazeIntent)
            true
        } catch (_: Exception) {
            try {
                val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(geoIntent)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Navigasyon başlatılamadı: ${e.message}")
                false
            }
        }
    }

    fun speak(text: String) {
        if (!ttsReady) return
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ulak_tts_${System.currentTimeMillis()}")
        } catch (_: Exception) { }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) { }
    }
}
