package tr.ulak.byd.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import tr.ulak.byd.mobile.model.ConnectionSettings
import tr.ulak.byd.mobile.model.TelemetryState
import tr.ulak.byd.mobile.ui.theme.UlakColors

@Composable
fun SettingsDiagnosticsScreen(
    state: TelemetryState,
    settings: ConnectionSettings,
    onSaveSettings: (ConnectionSettings) -> Unit,
    onSendCommand: (JSONObject) -> Unit,
) {
    var carIp by remember(settings.carIp) { mutableStateOf(settings.carIp) }
    var authToken by remember(settings.authToken) { mutableStateOf(settings.authToken) }
    var vehicleId by remember(settings.vehicleId) { mutableStateOf(settings.vehicleId) }
    var cloudUrl by remember(settings.cloudRelayUrl) { mutableStateOf(settings.cloudRelayUrl) }
    var geminiKey by remember(settings.geminiApiKey) { mutableStateOf(settings.geminiApiKey) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = UlakColors.CardDark),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("BAĞLANTI, RENDER BULUT & GEMINI API", color = UlakColors.EmeraldLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = carIp,
                        onValueChange = { carIp = it },
                        label = { Text("Araç Yerel IP (LAN)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = authToken,
                        onValueChange = { authToken = it },
                        label = { Text("Eşleştirme PIN Kodu") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = vehicleId,
                        onValueChange = { vehicleId = it },
                        label = { Text("Araç Kimliği (Vehicle ID)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = cloudUrl,
                        onValueChange = { cloudUrl = it },
                        label = { Text("Render Bulut & WebRTC Röle URL") },
                        placeholder = { Text("https://server-kk7c.onrender.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = geminiKey,
                        onValueChange = { geminiKey = it },
                        label = { Text("Google Gemini API Anahtarı (Hey Ulak)") },
                        placeholder = { Text("AIzaSy...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            onSaveSettings(
                                ConnectionSettings(
                                    carIp = carIp.trim(),
                                    authToken = authToken.trim(),
                                    vehicleId = vehicleId.trim(),
                                    cloudRelayUrl = cloudUrl.trim(),
                                    geminiApiKey = geminiKey.trim(),
                                ),
                            )
                            if (geminiKey.trim().isNotEmpty()) {
                                onSendCommand(
                                    JSONObject()
                                        .put("command", "SET_VOICE_CONFIG")
                                        .put("gemini_api_key", geminiKey.trim()),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Ayarları Kaydet & Araca Senkronize Et")
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = UlakColors.CardDark),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("YETKİLİ SERVİS MODU", color = UlakColors.Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Serviste Nöbetçi, alarm, otomasyonlar ve Phone as Key 24 saat duraklatılır.",
                            color = UlakColors.TextMuted,
                            fontSize = 12.sp,
                        )
                    }
                    Switch(
                        checked = state.serviceMode,
                        onCheckedChange = { enable ->
                            onSendCommand(
                                JSONObject()
                                    .put("command", "SET_SERVICE_MODE")
                                    .put("enable", enable)
                                    .put("duration_hours", 24),
                            )
                        },
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = UlakColors.CardDark),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("DILINK 3.0 VHAL & WEBRTC TANILAMA", color = UlakColors.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "LAN: ${if (state.connectedViaLan) "BAĞLI" else "YOK"} • Render: ${if (state.connectedViaCloud) "BAĞLI" else "YOK"}\n" +
                            "VHAL: ${if (state.hardwareLive) "GERÇEK" else "FALLBACK"} • TURN: ${if (state.webrtc.turnEnabled) "AKTİF" else "STUN/WS"}",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (state.moduleDiagnostics.isEmpty()) {
                        Text("Araçtan ilk telemetri paketi bekleniyor...", color = UlakColors.TextMuted, fontSize = 12.sp)
                    } else {
                        state.moduleDiagnostics.forEach { (mod, ok) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("BydAuto${mod.replaceFirstChar { it.uppercase() }}Device", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                Text(
                                    text = if (ok) "BAĞLI" else "FALLBACK",
                                    color = if (ok) UlakColors.EmeraldLight else UlakColors.Amber,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
