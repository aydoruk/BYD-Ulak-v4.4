package tr.ulak.byd.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import tr.ulak.byd.mobile.model.TR_LOCALE
import tr.ulak.byd.mobile.model.TelemetryState
import tr.ulak.byd.mobile.ui.theme.UlakColors
import kotlin.math.roundToInt

@Composable
fun ControlsScreen(
    state: TelemetryState,
    onSendCommand: (JSONObject) -> Unit,
) {
    var localTargetTemp by remember(state.targetTempC) { mutableFloatStateOf(state.targetTempC) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. İklimlendirme & Buz Çözücü
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = UlakColors.CardDark),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("İKLİMLENDİRME & BUZ ÇÖZÜCÜ", color = UlakColors.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("Hedef: ${String.format(TR_LOCALE, "%.1f", localTargetTemp)}°C", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("Kabin Sıcaklığı: ${String.format(TR_LOCALE, "%.1f", state.cabinTempC)}°C", color = UlakColors.TextMuted, fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                onSendCommand(
                                    JSONObject()
                                        .put("command", "SET_AC")
                                        .put("enable", !state.acActive)
                                        .put("temp", localTargetTemp.toDouble())
                                        .put("front_defrost", state.frontDefrost)
                                        .put("rear_defrost", state.rearDefrost),
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.acActive) UlakColors.Cyan else UlakColors.SurfaceElevated,
                            ),
                        ) {
                            Text(if (state.acActive) "Klimayı Kapat" else "Klimayı Başlat")
                        }
                    }
                    Slider(
                        value = localTargetTemp,
                        onValueChange = { localTargetTemp = (it * 2f).roundToInt() / 2f },
                        onValueChangeFinished = {
                            if (state.acActive) {
                                onSendCommand(
                                    JSONObject()
                                        .put("command", "SET_AC")
                                        .put("enable", true)
                                        .put("temp", localTargetTemp.toDouble()),
                                )
                            }
                        },
                        valueRange = 16f..32f,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                onSendCommand(
                                    JSONObject()
                                        .put("command", "SET_AC")
                                        .put("enable", true)
                                        .put("temp", localTargetTemp.toDouble())
                                        .put("front_defrost", !state.frontDefrost),
                                )
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.frontDefrost) UlakColors.Amber else UlakColors.SurfaceElevated,
                            ),
                        ) {
                            Text("Ön Cam Defrost", fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                onSendCommand(
                                    JSONObject()
                                        .put("command", "SET_AC")
                                        .put("enable", true)
                                        .put("temp", localTargetTemp.toDouble())
                                        .put("rear_defrost", !state.rearDefrost),
                                )
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.rearDefrost) UlakColors.Amber else UlakColors.SurfaceElevated,
                            ),
                        ) {
                            Text("Arka Cam Defrost", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 2. Koltuk Isıtma & Havalandırma
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = UlakColors.CardDark),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("KOLTUK KONFORU (SÜRÜCÜ & YOLCU)", color = UlakColors.EmeraldLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val next = (state.driverVent + 1) % 4
                                onSendCommand(JSONObject().put("command", "SET_SEAT").put("zone", "driver").put("vent", next))
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.driverVent > 0) UlakColors.Cyan.copy(alpha = 0.35f) else UlakColors.SurfaceElevated,
                            ),
                        ) {
                            Text("Sürücü Soğut: ${state.driverVent}", fontSize = 11.sp)
                        }
                        Button(
                            onClick = {
                                val next = (state.driverHeat + 1) % 4
                                onSendCommand(JSONObject().put("command", "SET_SEAT").put("zone", "driver").put("heat", next))
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.driverHeat > 0) UlakColors.Amber.copy(alpha = 0.35f) else UlakColors.SurfaceElevated,
                            ),
                        ) {
                            Text("Sürücü Isıt: ${state.driverHeat}", fontSize = 11.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val next = (state.passengerVent + 1) % 4
                                onSendCommand(JSONObject().put("command", "SET_SEAT").put("zone", "passenger").put("vent", next))
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.passengerVent > 0) UlakColors.Cyan.copy(alpha = 0.35f) else UlakColors.SurfaceElevated,
                            ),
                        ) {
                            Text("Yolcu Soğut: ${state.passengerVent}", fontSize = 11.sp)
                        }
                        Button(
                            onClick = {
                                val next = (state.passengerHeat + 1) % 4
                                onSendCommand(JSONObject().put("command", "SET_SEAT").put("zone", "passenger").put("heat", next))
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.passengerHeat > 0) UlakColors.Amber.copy(alpha = 0.35f) else UlakColors.SurfaceElevated,
                            ),
                        ) {
                            Text("Yolcu Isıt: ${state.passengerHeat}", fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // 3. Gövde, Camlar, Sunroof, Bagaj & Aynalar
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = UlakColors.CardDark),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("GÖVDE, CAMLAR, SUNROOF & BAGAJ", color = UlakColors.Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onSendCommand(JSONObject().put("command", "SET_WINDOWS").put("percent", 0)) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = UlakColors.SurfaceElevated),
                        ) { Text("Camları Kapat", fontSize = 11.sp) }
                        Button(
                            onClick = { onSendCommand(JSONObject().put("command", "SET_WINDOWS").put("percent", 15)) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = UlakColors.SurfaceElevated),
                        ) { Text("Havalandır (%15)", fontSize = 11.sp) }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val target = if (state.sunroofOpenPct > 0) 0 else 50
                                onSendCommand(JSONObject().put("command", "SET_SUNROOF").put("percent", target))
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = UlakColors.SurfaceElevated),
                        ) { Text(if (state.sunroofOpenPct > 0) "Sunroof Kapat" else "Sunroof Aç", fontSize = 11.sp) }
                        Button(
                            onClick = { onSendCommand(JSONObject().put("command", "SET_TRUNK").put("open", !state.trunkOpen)) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = UlakColors.SurfaceElevated),
                        ) { Text(if (state.trunkOpen) "Bagajı Kapat" else "Bagajı Aç", fontSize = 11.sp) }
                        Button(
                            onClick = { onSendCommand(JSONObject().put("command", "SET_MIRRORS").put("folded", !state.mirrorsFolded)) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = UlakColors.SurfaceElevated),
                        ) { Text(if (state.mirrorsFolded) "Ayna Aç" else "Ayna Katla", fontSize = 11.sp) }
                    }
                }
            }
        }

        // 4. Sürüş Modları
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = UlakColors.CardDark),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("SÜRÜŞ MODU", color = UlakColors.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("EV", "HEV", "ECO", "SPORT").forEach { mode ->
                            val selected = state.driveMode.equals(mode, ignoreCase = true)
                            Button(
                                onClick = { onSendCommand(JSONObject().put("command", "SET_DRIVE_MODE").put("mode", mode)) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selected) UlakColors.Emerald else UlakColors.SurfaceElevated,
                                ),
                            ) {
                                Text(mode, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
