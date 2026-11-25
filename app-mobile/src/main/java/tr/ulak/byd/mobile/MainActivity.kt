package tr.ulak.byd.mobile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import tr.ulak.byd.mobile.model.ConnectionSettings
import tr.ulak.byd.mobile.model.TR_LOCALE
import tr.ulak.byd.mobile.network.UlakCarClient
import tr.ulak.byd.mobile.ui.screens.*
import tr.ulak.byd.mobile.ui.theme.UlakColors
import tr.ulak.byd.mobile.ui.theme.UlakTheme
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : ComponentActivity() {

    private lateinit var client: UlakCarClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialSettings = loadSettings()
        client = UlakCarClient(initialSettings)
        client.start()

        setContent {
            UlakTheme {
                UlakS26UltraApp(
                    client = client,
                    onPersistSettings = { saveSettings(it) },
                )
            }
        }
    }

    override fun onDestroy() {
        client.close()
        super.onDestroy()
    }

    private fun loadSettings(): ConnectionSettings {
        val prefs = getSharedPreferences("ulak_mobile_v4", Context.MODE_PRIVATE)
        return ConnectionSettings(
            carIp = prefs.getString("car_ip", "192.168.43.1") ?: "192.168.43.1",
            authToken = prefs.getString("auth_token", "ULAK2025") ?: "ULAK2025",
            vehicleId = prefs.getString("vehicle_id", "BYD-SEAL-U-DMI-2025") ?: "BYD-SEAL-U-DMI-2025",
            cloudRelayUrl = prefs.getString("cloud_url", "https://server-kk7c.onrender.com") ?: "https://server-kk7c.onrender.com",
            geminiApiKey = prefs.getString("gemini_api_key", "") ?: "",
        )
    }

    private fun saveSettings(s: ConnectionSettings) {
        getSharedPreferences("ulak_mobile_v4", Context.MODE_PRIVATE).edit()
            .putString("car_ip", s.carIp)
            .putString("auth_token", s.authToken)
            .putString("vehicle_id", s.vehicleId)
            .putString("cloud_url", s.cloudRelayUrl)
            .putString("gemini_api_key", s.geminiApiKey)
            .apply()
    }
}

@Composable
fun UlakS26UltraApp(
    client: UlakCarClient,
    onPersistSettings: (ConnectionSettings) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val state by client.telemetry.collectAsState()
    val settings by client.settings.collectAsState()
    val trips by client.trips.collectAsState()
    val voiceHistory by client.voiceHistory.collectAsState()
    val liveFrame by client.liveCameraFrame.collectAsState()
    val streamStatus by client.streamStatus.collectAsState()
    val streamTransport by client.streamTransport.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var simulating by remember { mutableStateOf(false) }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                scope.launch { client.askVoiceAssistant(spoken) }
            }
        }
    }

    LaunchedEffect(simulating) {
        while (simulating && isActive) {
            val t = System.currentTimeMillis() / 900.0
            val speed = (68 + (sin(t) * 44)).roundToInt().coerceIn(0, 175)
            client.applySimulationTick(speed, state.driveMode)
            delay(800.milliseconds)
        }
        if (!simulating && !state.isOnline) {
            client.applySimulationTick(0, state.driveMode)
        }
    }

    fun dispatchCommand(cmd: JSONObject) {
        scope.launch {
            val ack = client.sendCommand(cmd)
            val prefix = when {
                !ack.ok -> "[Hata] "
                ack.hardwareExecuted -> "[VHAL] "
                else -> "[OK] "
            }
            snackbarHostState.showSnackbar(prefix + ack.message, duration = SnackbarDuration.Short)
        }
    }

    val navItems = listOf(
        Triple("Durum", Icons.Rounded.DirectionsCar, 0),
        Triple("Kamera", Icons.Rounded.Videocam, 1),
        Triple("Kontrol", Icons.Rounded.Tune, 2),
        Triple("Hey Ulak", Icons.Rounded.GraphicEq, 3),
        Triple("Sistem", Icons.Rounded.Settings, 4),
    )

    Scaffold(
        containerColor = UlakColors.BgDark,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UlakColors.BarDark)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "ULAK",
                                color = UlakColors.Emerald,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val connLabel = when {
                                state.connectedViaLan -> "LAN CANLI"
                                state.connectedViaCloud -> "RENDER 5G"
                                else -> "BAĞLANTI YOK"
                            }
                            StatusChip(
                                label = connLabel,
                                color = when {
                                    state.connectedViaLan -> UlakColors.Emerald
                                    state.connectedViaCloud -> UlakColors.Cyan
                                    else -> UlakColors.TextMuted
                                },
                            )
                        }
                        Text(
                            text = "${state.vehicleName} • ${String.format(TR_LOCALE, "%.1f", state.aux12vVoltage)}V • ${state.combinedRangeKm} km",
                            color = UlakColors.TextMuted,
                            fontSize = 12.sp,
                        )
                    }
                    Text(
                        text = "v4.0",
                        color = UlakColors.Cyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = UlakColors.BarDark) {
                navItems.forEach { (label, icon, index) ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label, fontSize = 10.sp, maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (selectedTab) {
                0 -> DashboardScreen(
                    state = state,
                    simulating = simulating,
                    onToggleSimulating = { simulating = !simulating },
                    onSendCommand = { dispatchCommand(it) },
                )

                1 -> CameraSentryScreen(
                    state = state,
                    liveFrame = liveFrame,
                    streamStatus = streamStatus,
                    streamTransport = streamTransport,
                    onStartLanStream = { client.startCameraStream(forceCloud = false) },
                    onStartCloudStream = { client.startCameraStream(forceCloud = true) },
                    onStopStream = { client.stopCameraStream() },
                    onSendCommand = { dispatchCommand(it) },
                )

                2 -> ControlsHubScreen(
                    state = state,
                    trips = trips,
                    onSendCommand = { dispatchCommand(it) },
                )

                3 -> AssistantPakScreen(
                    state = state,
                    voiceHistory = voiceHistory,
                    onAskVoice = { prompt ->
                        scope.launch { client.askVoiceAssistant(prompt) }
                    },
                    onLaunchMic = {
                        try {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, TR_LOCALE)
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "Hey Ulak dinliyor...")
                            }
                            speechLauncher.launch(intent)
                        } catch (_: Exception) {
                            scope.launch { snackbarHostState.showSnackbar("Mikrofon servisi açılamadı") }
                        }
                    },
                    onSendCommand = { dispatchCommand(it) },
                )

                4 -> SettingsDiagnosticsScreen(
                    state = state,
                    settings = settings,
                    onSaveSettings = {
                        onPersistSettings(it)
                        client.updateSettings(it)
                        scope.launch {
                            snackbarHostState.showSnackbar("ULAK v4.0 bağlantı ayarları kaydedildi")
                        }
                    },
                    onSendCommand = { dispatchCommand(it) },
                )
            }
        }
    }
}
