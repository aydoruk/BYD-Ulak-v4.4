package tr.ulak.byd.car

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.toColorInt
import java.net.NetworkInterface
import java.util.Locale

@SuppressLint("SetTextI18n")
class CarMainActivity : Activity() {

    companion object {
        private const val REQ_VOICE_SPEECH = 4040
        private val TR_LOCALE: Locale = Locale.forLanguageTag("tr-TR")
    }

    private lateinit var config: UlakCarConfig
    private lateinit var bridge: DiLink3HardwareBridge
    private lateinit var evMetricText: TextView
    private lateinit var hevMetricText: TextView
    private lateinit var auxMetricText: TextView
    private lateinit var statusBadgeText: TextView
    private lateinit var moduleStatusText: TextView
    private lateinit var featureStatusText: TextView
    private lateinit var pinInput: EditText
    private lateinit var cloudUrlInput: EditText
    private lateinit var geminiKeyInput: EditText
    private lateinit var voicePromptInput: EditText

    private val uiHandler = Handler(Looper.getMainLooper())

    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateDashboard()
            uiHandler.postDelayed(this, 2500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = UlakCarConfig(applicationContext)
        bridge = DiLink3HardwareBridge(applicationContext)

        requestRuntimePermissionsIfNeeded()
        startUlakService()
        setContentView(buildCockpitLayout())
        updateDashboard()
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val needed = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 2025)
        }
    }

    private fun buildCockpitLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#060B14".toColorInt())
            setPadding(32, 24, 32, 20)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 14)
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "ULAK v4.0 • BYD Seal U DM-i Kokpit"
            textSize = 23f
            setTextColor("#10B981".toColorInt())
            typeface = Typeface.DEFAULT_BOLD
        })
        titleCol.addView(TextView(this).apply {
            text = "DiLink 3.0 • IP: ${getLocalIpAddress()} • UDP 45454 | TCP 45455 | CAM :45456 | Render Bulut"
            textSize = 13f
            setTextColor("#22D3EE".toColorInt())
        })

        statusBadgeText = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(18, 9, 18, 9)
        }
        header.addView(titleCol)
        header.addView(statusBadgeText)
        root.addView(header)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.05f)
            setPadding(0, 0, 16, 0)
        }

        val gaugeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        evMetricText = makeMetricCard("#0F172A", "#10B981").also {
            gaugeRow.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 })
        }
        hevMetricText = makeMetricCard("#0F172A", "#F59E0B").also {
            gaugeRow.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 })
        }
        auxMetricText = makeMetricCard("#0F172A", "#22D3EE").also {
            gaugeRow.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        leftCol.addView(gaugeRow)

        featureStatusText = TextView(this).apply {
            textSize = 13f
            setTextColor("#E2E8F0".toColorInt())
            background = roundedBox("#0F172A", "#1E293B")
            setPadding(18, 14, 18, 14)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 10
            }
        }
        leftCol.addView(featureStatusText)

        moduleStatusText = TextView(this).apply {
            textSize = 12f
            setTextColor("#94A3B8".toColorInt())
            typeface = Typeface.MONOSPACE
            background = roundedBox("#0B1324", "#1E293B")
            setPadding(18, 12, 18, 12)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 10
            }
        }
        leftCol.addView(moduleStatusText)

        val voiceCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBox("#0F172A", "#10B981")
            setPadding(18, 14, 18, 14)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 12
            }
        }
        voiceCard.addView(makeLabel("HEY ULAK • GEMINI SESLİ ASİSTAN", "#34D399", 13f, true))

        val voiceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 8
            }
        }
        voicePromptInput = EditText(this).apply {
            hint = "Örn: Klimayı 21 derece yap ve menzilimi söyle"
            setTextColor("#FFFFFF".toColorInt())
            setHintTextColor("#64748B".toColorInt())
            background = roundedBox("#060B14", "#334155")
            setPadding(14, 10, 14, 10)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 }
        }
        val askBtn = Button(this).apply {
            text = "Gönder"
            setTextColor("#060B14".toColorInt())
            background = roundedBox("#10B981", "#10B981")
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                val p = voicePromptInput.text.toString().trim()
                if (p.isNotEmpty()) {
                    sendVoicePromptToService(p)
                    voicePromptInput.setText("")
                }
            }
        }
        val micBtn = Button(this).apply {
            text = "Mikrofon"
            setTextColor("#FFFFFF".toColorInt())
            background = roundedBox("#0284C7", "#38BDF8")
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = 8
            }
            setOnClickListener { launchSpeechRecognizer() }
        }
        voiceRow.addView(voicePromptInput)
        voiceRow.addView(askBtn)
        voiceRow.addView(micBtn)
        voiceCard.addView(voiceRow)
        leftCol.addView(voiceCard)

        val rightScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.95f)
        }
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBox("#0F172A", "#1E293B")
            setPadding(20, 18, 20, 18)
        }

        rightCol.addView(makeLabel("BULUT RÖLE, GÜVENLİK & GEMINI API", "#22D3EE", 13f, true))

        rightCol.addView(makeLabel("Eşleştirme PIN Kodu:", "#94A3B8", 11f, false, topMargin = 10))
        pinInput = makeInput(config.authToken, "ULAK2025")
        rightCol.addView(pinInput)

        rightCol.addView(makeLabel("Render Bulut Röle URL (4G/5G + WebRTC Kamera):", "#94A3B8", 11f, false, topMargin = 10))
        cloudUrlInput = makeInput(config.cloudRelayUrl, "https://server-kk7c.onrender.com")
        rightCol.addView(cloudUrlInput)

        rightCol.addView(makeLabel("Google Gemini API Anahtarı (Hey Ulak AI):", "#94A3B8", 11f, false, topMargin = 10))
        geminiKeyInput = makeInput(config.geminiApiKey, "AIzaSy...")
        rightCol.addView(geminiKeyInput)

        val saveBtn = Button(this).apply {
            text = "Ayarları Kaydet ve Servisi Yenile"
            setTextColor("#060B14".toColorInt())
            background = roundedBox("#10B981", "#10B981")
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                config.authToken = pinInput.text.toString()
                config.cloudRelayUrl = cloudUrlInput.text.toString()
                config.geminiApiKey = geminiKeyInput.text.toString()
                val reload = Intent(this@CarMainActivity, UlakDiLink3Service::class.java).apply {
                    action = UlakDiLink3Service.ACTION_RELOAD_CONFIG
                }
                startForegroundService(reload)
                Toast.makeText(this@CarMainActivity, "ULAK v4.0 ayarları kaydedildi", Toast.LENGTH_SHORT).show()
                updateDashboard()
            }
        }
        rightCol.addView(saveBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 14 })

        rightScroll.addView(rightCol)
        body.addView(leftCol)
        body.addView(rightScroll)
        root.addView(body)
        return root
    }

    private fun launchSpeechRecognizer() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, TR_LOCALE)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Hey Ulak dinliyor...")
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_VOICE_SPEECH)
        } catch (_: Exception) {
            Toast.makeText(this, "Ses tanıma servisi bulunamadı, metin kutusunu kullanabilirsiniz.", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VOICE_SPEECH && resultCode == RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                sendVoicePromptToService(spoken)
            }
        }
    }

    private fun sendVoicePromptToService(prompt: String) {
        val intent = Intent(this, UlakDiLink3Service::class.java).apply {
            action = UlakDiLink3Service.ACTION_VOICE_PROMPT
            putExtra(UlakDiLink3Service.EXTRA_PROMPT, prompt)
        }
        startForegroundService(intent)
        Toast.makeText(this, "Hey Ulak işliyor: $prompt", Toast.LENGTH_SHORT).show()
    }

    private fun makeInput(initial: String, hintText: String): EditText {
        return EditText(this).apply {
            setText(initial)
            hint = hintText
            setTextColor("#FFFFFF".toColorInt())
            setHintTextColor("#64748B".toColorInt())
            background = roundedBox("#060B14", "#334155")
            setPadding(14, 10, 14, 10)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 4
            }
        }
    }

    private fun makeMetricCard(bgHex: String, accentHex: String): TextView {
        return TextView(this).apply {
            textSize = 14f
            setTextColor("#FFFFFF".toColorInt())
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBox(bgHex, accentHex)
            setPadding(16, 14, 16, 14)
        }
    }

    private fun makeLabel(textStr: String, colorHex: String, sizeSp: Float, bold: Boolean, topMargin: Int = 0): TextView {
        return TextView(this).apply {
            text = textStr
            textSize = sizeSp
            setTextColor(colorHex.toColorInt())
            if (bold) typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                this.topMargin = topMargin
            }
        }
    }

    private fun roundedBox(fillHex: String, strokeHex: String): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillHex.toColorInt())
            setStroke(2, strokeHex.toColorInt())
            cornerRadius = 16f
        }
    }

    private fun updateDashboard() {
        val snap = bridge.readTelemetrySnapshot()
        val hwLive = snap.optBoolean("hardware_live", false)

        statusBadgeText.text = if (hwLive) "VHAL CANLI" else "FALLBACK / TEST"
        statusBadgeText.setTextColor(if (hwLive) "#10B981".toColorInt() else "#F59E0B".toColorInt())
        statusBadgeText.background = roundedBox("#0F172A", if (hwLive) "#10B981" else "#F59E0B")

        evMetricText.text = "LFP BLADE EV\n%${snap.optInt("soc_percent")} • ${snap.optInt("ev_range_km")} km"
        hevMetricText.text = "1.5L HEV YAKIT\n%${snap.optInt("fuel_percent")} • ${snap.optInt("hev_range_km")} km"
        auxMetricText.text = "12V & KABİN\n${String.format(TR_LOCALE, "%.1f", snap.optDouble("aux_12v_voltage"))}V • ${String.format(TR_LOCALE, "%.1f", snap.optDouble("cabin_temp_c"))}°C"

        featureStatusText.text =
            "Hey Ulak AI: ${if (config.geminiApiKey.isNotBlank()) "GEMINI AKTİF" else "ÇEVRİMDIŞI TÜRKÇE"}   |   " +
                "Bluetooth Anahtar: ${if (config.pakEnabled) "AKTİF (${config.pakUnlockRssi} dBm)" else "KAPALI"}   |   " +
                "Bulut: ${config.cloudRelayUrl.ifBlank { "Sadece LAN" }}"

        val diag = snap.optJSONObject("diagnostics")
        val modules = diag?.optJSONObject("modules")
        val sb = StringBuilder("VHAL (${diag?.optInt("connected_count", 0)}/${diag?.optInt("total_modules", 8)}): ")
        modules?.keys()?.forEach { key ->
            sb.append(if (modules.optBoolean(key, false)) "[OK]" else "[--]").append(key.uppercase()).append(" ")
        }
        moduleStatusText.text = sb.toString()
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(refreshRunnable)
    }

    private fun startUlakService() {
        startForegroundService(Intent(this, UlakDiLink3Service::class.java))
    }

    private fun getLocalIpAddress(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if ((!addr.isLoopbackAddress) && (addr.hostAddress?.contains(':') == false)) {
                        return addr.hostAddress ?: "192.168.43.1"
                    }
                }
            }
            "192.168.43.1"
        } catch (_: Exception) {
            "192.168.43.1"
        }
    }
}
