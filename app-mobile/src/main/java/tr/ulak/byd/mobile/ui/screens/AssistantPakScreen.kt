package tr.ulak.byd.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import tr.ulak.byd.mobile.model.TR_LOCALE
import tr.ulak.byd.mobile.model.TelemetryState
import tr.ulak.byd.mobile.model.VoiceMessageItem
import tr.ulak.byd.mobile.ui.theme.UlakColors
import kotlin.math.roundToInt

@Composable
fun AssistantPakScreen(
    state: TelemetryState,
    voiceHistory: List<VoiceMessageItem>,
    onAskVoice: (String) -> Unit,
    onLaunchMic: () -> Unit,
    onSendCommand: (JSONObject) -> Unit,
) {
    var subTab by remember { mutableIntStateOf(0) }
    var promptText by remember { mutableStateOf("") }

    var pakEnabled by remember(state.pak.enabled) { mutableStateOf(state.pak.enabled) }
    var device1 by remember(state.pak.registeredTargets) {
        mutableStateOf(state.pak.registeredTargets.getOrNull(0) ?: "Galaxy S26 Ultra")
    }
    var device2 by remember(state.pak.registeredTargets) {
        mutableStateOf(state.pak.registeredTargets.getOrNull(1) ?: "")
    }
    var unlockRssi by remember(state.pak.unlockRssi) { mutableFloatStateOf(state.pak.unlockRssi.toFloat()) }
    var lockRssi by remember(state.pak.lockRssi) { mutableFloatStateOf(state.pak.lockRssi.toFloat()) }

    val quickPrompts = listOf(
        "Menzilim ne kadar?",
        "Klimayı 21 derece aç",
        "Camları kapat",
        "Lastik basınçları nasıl?",
        "Nöbetçiyi aktif et",
        "Havalimanına rota oluştur",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { subTab = 0 },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (subTab == 0) UlakColors.Emerald else UlakColors.SurfaceElevated,
                ),
            ) {
                Icon(Icons.Rounded.GraphicEq, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Hey Ulak AI", fontSize = 12.sp)
            }
            Button(
                onClick = { subTab = 1 },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (subTab == 1) UlakColors.Cyan else UlakColors.SurfaceElevated,
                ),
            ) {
                Icon(Icons.Rounded.BluetoothSearching, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Bluetooth Anahtar", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (subTab == 0) {
            Card(
                colors = CardDefaults.cardColors(containerColor = UlakColors.CardDark),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("HEY ULAK • GEMINI SESLİ ASİSTAN", color = UlakColors.EmeraldLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (state.voiceReady) "Google Gemini 2.5 Flash Aktif" else "Çevrimdışı Türkçe Motor (Sistemden Gemini Key eklenebilir)",
                            color = UlakColors.TextMuted,
                            fontSize = 11.sp,
                        )
                    }
                    StatusChip(
                        label = if (state.voiceReady) "GEMINI" else "YEREL",
                        color = if (state.voiceReady) UlakColors.Emerald else UlakColors.Amber,
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(quickPrompts) { chip ->
                    AssistChip(
                        onClick = { onAskVoice(chip) },
                        label = { Text(chip, fontSize = 11.sp, color = Color.White) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = UlakColors.SurfaceElevated),
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(voiceHistory) { msg ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth(0.86f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (msg.isUser) UlakColors.Emerald.copy(alpha = 0.22f) else UlakColors.CardDark)
                                .padding(12.dp),
                        ) {
                            Text(
                                text = if (msg.isUser) "Sen" else if (msg.usedGemini) "Hey Ulak (Gemini)" else "Hey Ulak",
                                color = if (msg.isUser) UlakColors.EmeraldLight else UlakColors.Cyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(msg.text, color = Color.White, fontSize = 13.sp)
                            if (msg.actions.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Uygulandı: ${msg.actions.joinToString(" • ")}",
                                    color = UlakColors.EmeraldLight,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    placeholder = { Text("Araca bir şey söyle veya yaz...", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onLaunchMic,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(UlakColors.Cyan.copy(alpha = 0.22f)),
                ) {
                    Icon(Icons.Rounded.Mic, contentDescription = "Konuş", tint = UlakColors.Cyan)
                }
                IconButton(
                    onClick = {
                        if (promptText.isNotBlank()) {
                            onAskVoice(promptText)
                            promptText = ""
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(UlakColors.Emerald),
                ) {
                    Icon(Icons.Rounded.Send, contentDescription = "Gönder", tint = UlakColors.BgDark)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = UlakColors.CardDark),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("BLUETOOTH YAKINLIK ANAHTARI (PaK)", color = UlakColors.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        "Yaklaşınca (~2-3 m) otomatik kilit açar, uzaklaşınca kilitler. Sadece P vitesinde çalışır.",
                                        color = UlakColors.TextMuted,
                                        fontSize = 12.sp,
                                    )
                                }
                                Switch(
                                    checked = pakEnabled,
                                    onCheckedChange = { pakEnabled = it },
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(UlakColors.BgDark)
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column {
                                    Text("Canlı Bluetooth Sinyali", color = UlakColors.TextMuted, fontSize = 11.sp)
                                    Text(
                                        text = if (state.pak.bestRssi > -120) "${state.pak.bestRssi} dBm" else "Sinyal Yok",
                                        color = UlakColors.EmeraldLight,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Tahmini Mesafe", color = UlakColors.TextMuted, fontSize = 11.sp)
                                    Text(
                                        text = if (state.pak.estimatedDistanceM >= 0) "~${String.format(TR_LOCALE, "%.1f", state.pak.estimatedDistanceM)} m" else "--",
                                        color = UlakColors.Cyan,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
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
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("KAYITLI TELEFONLAR (EN FAZLA 2 CİHAZ)", color = UlakColors.EmeraldLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            OutlinedTextField(
                                value = device1,
                                onValueChange = { device1 = it },
                                label = { Text("1. Telefon (Bluetooth Adı veya MAC)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = device2,
                                onValueChange = { device2 = it },
                                label = { Text("2. Telefon (İsteğe Bağlı)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = "Kilit Açma Eşiği: ${unlockRssi.roundToInt()} dBm (Yakın ~2-3 m)",
                                color = Color.White,
                                fontSize = 12.sp,
                            )
                            Slider(
                                value = unlockRssi,
                                onValueChange = { unlockRssi = it },
                                valueRange = -80f..-45f,
                            )
                            Text(
                                text = "Otomatik Kilitleme Eşiği: ${lockRssi.roundToInt()} dBm (Uzak)",
                                color = Color.White,
                                fontSize = 12.sp,
                            )
                            Slider(
                                value = lockRssi,
                                onValueChange = { lockRssi = it },
                                valueRange = -95f..-65f,
                            )
                            Button(
                                onClick = {
                                    onSendCommand(
                                        JSONObject()
                                            .put("command", "SET_PAK_CONFIG")
                                            .put("enabled", pakEnabled)
                                            .put("device_1", device1.trim())
                                            .put("device_2", device2.trim())
                                            .put("unlock_rssi", unlockRssi.roundToInt())
                                            .put("lock_rssi", lockRssi.roundToInt())
                                            .put("away_timeout_sec", 12),
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Bluetooth Anahtar Ayarlarını Araca Gönder")
                            }
                        }
                    }
                }
            }
        }
    }
}
