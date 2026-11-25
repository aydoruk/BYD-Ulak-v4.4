package tr.ulak.byd.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import tr.ulak.byd.mobile.model.TR_LOCALE
import tr.ulak.byd.mobile.model.TelemetryState
import tr.ulak.byd.mobile.ui.theme.UlakColors

@Composable
fun DashboardScreen(
    state: TelemetryState,
    simulating: Boolean,
    onToggleSimulating: () -> Unit,
    onSendCommand: (JSONObject) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. Hızlı Aksiyonlar (DashArc "Current Status" shortcuts)
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
                        Text(
                            text = "HIZLI KONTROLLER",
                            color = UlakColors.TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        StatusChip(
                            label = when {
                                state.serviceMode -> "SERVİS MODU"
                                state.hardwareLive -> "VHAL CANLI"
                                state.isOnline -> "FALLBACK / TEST"
                                else -> "ÇEVRİMDIŞI"
                            },
                            color = when {
                                state.serviceMode -> UlakColors.Amber
                                state.hardwareLive -> UlakColors.Emerald
                                state.isOnline -> UlakColors.Cyan
                                else -> UlakColors.TextMuted
                            },
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        QuickActionButton(
                            modifier = Modifier.weight(1f),
                            icon = if (state.isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                            label = if (state.isLocked) "Kilitli" else "Açık",
                            active = state.isLocked,
                            activeColor = UlakColors.Emerald,
                        ) {
                            onSendCommand(JSONObject().put("command", "SET_LOCK").put("locked", !state.isLocked))
                        }
                        QuickActionButton(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.AcUnit,
                            label = if (state.acActive) "${String.format(TR_LOCALE, "%.1f", state.targetTempC)}°C" else "Klima",
                            active = state.acActive,
                            activeColor = UlakColors.Cyan,
                        ) {
                            onSendCommand(
                                JSONObject()
                                    .put("command", "SET_AC")
                                    .put("enable", !state.acActive)
                                    .put("temp", state.targetTempC.toDouble()),
                            )
                        }
                        QuickActionButton(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.Shield,
                            label = if (state.sentryActive) "Nöbetçi" else "Pasif",
                            active = state.sentryActive,
                            activeColor = UlakColors.Emerald,
                        ) {
                            onSendCommand(JSONObject().put("command", "SET_SENTRY").put("enable", !state.sentryActive))
                        }
                        QuickActionButton(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.NotificationsActive,
                            label = "Araç Bul",
                            active = false,
                            activeColor = UlakColors.Amber,
                        ) {
                            onSendCommand(JSONObject().put("command", "FIND_CAR"))
                        }
                    }
                }
            }
        }

        // 2. DM-i Çift Enerji Kartı (18.3 kWh LFP Blade + 1.5L Xiaoyun)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = UlakColors.CardDark),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "DM-i ÇİFT ENERJİ & MENZİL",
                        color = UlakColors.TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Bolt, null, tint = UlakColors.EmeraldLight, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("LFP Blade EV", color = UlakColors.EmeraldLight, fontSize = 12.sp)
                            }
                            Text(
                                text = "%${state.socPercent} • ${state.evRangeKm} km",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { (state.socPercent / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth(0.9f)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = UlakColors.Emerald,
                                trackColor = UlakColors.SurfaceElevated,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.End,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.LocalGasStation, null, tint = UlakColors.Amber, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("1.5L HEV Yakıt", color = UlakColors.Amber, fontSize = 12.sp)
                            }
                            Text(
                                text = "%${state.fuelPercent} • ${state.hevRangeKm} km",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { (state.fuelPercent / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth(0.9f)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = UlakColors.Amber,
                                trackColor = UlakColors.SurfaceElevated,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = UlakColors.SurfaceElevated)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Karma Menzil: ${state.combinedRangeKm} km", color = UlakColors.Cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Km: ${String.format(TR_LOCALE, "%.0f", state.odometerKm)} km", color = UlakColors.TextMuted, fontSize = 12.sp)
                    }
                }
            }
        }

        // 3. Araç Sağlığı & 4 Tekerlek TPMS
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = UlakColors.CardDark),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "ARAÇ DURUMU & TPMS LASTİK BASINCI",
                        color = UlakColors.TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        MetricMini("12V Akü", "${String.format(TR_LOCALE, "%.1f", state.aux12vVoltage)} V", if (state.aux12vVoltage < 12.1f) UlakColors.Crimson else UlakColors.EmeraldLight)
                        MetricMini("Kabin", "${String.format(TR_LOCALE, "%.1f", state.cabinTempC)}°C", UlakColors.Cyan)
                        MetricMini("Vites / Hız", "${state.gear} • ${state.speedKmh} km/s", Color.White)
                        MetricMini("Mod", state.driveMode, UlakColors.Amber)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TpmsBadge("Ön Sol", state.tpms.toBarString(state.tpms.flKpa), Modifier.weight(1f))
                        TpmsBadge("Ön Sağ", state.tpms.toBarString(state.tpms.frKpa), Modifier.weight(1f))
                        TpmsBadge("Arka Sol", state.tpms.toBarString(state.tpms.rlKpa), Modifier.weight(1f))
                        TpmsBadge("Arka Sağ", state.tpms.toBarString(state.tpms.rrKpa), Modifier.weight(1f))
                    }
                }
            }
        }

        // 4. Konum & Demo Simülasyon Kontrolü
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text("GPS KONUM & TEST MODU", color = UlakColors.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Enlem: ${String.format(TR_LOCALE, "%.5f", state.lat)} • Boylam: ${String.format(TR_LOCALE, "%.5f", state.lon)}",
                            color = UlakColors.TextMuted,
                            fontSize = 12.sp,
                        )
                    }
                    Button(
                        onClick = onToggleSimulating,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (simulating) UlakColors.Crimson else UlakColors.SurfaceElevated,
                        ),
                    ) {
                        Text(if (simulating) "Simülasyonu Durdur" else "Sürüş Testi", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActionButton(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(66.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) activeColor.copy(alpha = 0.22f) else UlakColors.SurfaceElevated,
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, tint = if (active) activeColor else Color.White, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(label, fontSize = 10.sp, color = if (active) activeColor else Color.White, maxLines = 1)
        }
    }
}

@Composable
private fun MetricMini(label: String, value: String, valueColor: Color) {
    Column {
        Text(label, color = UlakColors.TextMuted, fontSize = 11.sp)
        Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TpmsBadge(label: String, pressure: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(UlakColors.BgDark)
            .padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = UlakColors.TextMuted, fontSize = 10.sp)
        Text(pressure, color = UlakColors.EmeraldLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun StatusChip(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
