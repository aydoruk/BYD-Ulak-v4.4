package tr.ulak.byd.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import tr.ulak.byd.mobile.model.TR_LOCALE
import tr.ulak.byd.mobile.model.TelemetryState
import tr.ulak.byd.mobile.model.TripRecordItem
import tr.ulak.byd.mobile.ui.theme.UlakColors
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun TripsAutomationsScreen(
    state: TelemetryState,
    trips: List<TripRecordItem>,
    onSendCommand: (JSONObject) -> Unit,
) {
    LaunchedEffect(Unit) {
        onSendCommand(JSONObject().put("command", "GET_TRIPS"))
    }

    val dateFmt = remember { SimpleDateFormat("dd MMM HH:mm", TR_LOCALE) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. Otomasyon Kuralları (DashArc Automations)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = UlakColors.CardDark),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "OTOMASYONLAR (ARAÇ İÇİ KURAL MOTORU)",
                        color = UlakColors.EmeraldLight,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    state.automations.forEachIndexed { idx, rule ->
                        if (idx > 0) {
                            HorizontalDivider(
                                color = UlakColors.SurfaceElevated,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(rule.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(rule.description, color = UlakColors.TextMuted, fontSize = 12.sp)
                            }
                            Switch(
                                checked = rule.enabled,
                                onCheckedChange = { enabled ->
                                    onSendCommand(
                                        JSONObject()
                                            .put("command", "SET_AUTOMATION")
                                            .put("rule_id", rule.id)
                                            .put("enabled", enabled),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        // 2. Gerçek Yolculuk Geçmişi (D/R -> P Kayıtları)
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
                        Column {
                            Text("YOLCULUK GEÇMİŞİ (D/R → P)", color = UlakColors.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            if (state.tripActive) {
                                Text("• Aktif sürüş kaydediliyor...", color = UlakColors.EmeraldLight, fontSize = 11.sp)
                            }
                        }
                        IconButton(onClick = { onSendCommand(JSONObject().put("command", "GET_TRIPS")) }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Yenile", tint = UlakColors.Cyan)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (trips.isEmpty()) {
                        Text(
                            text = "Araçta henüz tamamlanmış yolculuk kaydı yok. Vites D/R konumundan P konumuna geçtiğinde otomatik kaydedilir.",
                            color = UlakColors.TextMuted,
                            fontSize = 12.sp,
                        )
                    } else {
                        trips.forEachIndexed { idx, trip ->
                            if (idx > 0) {
                                HorizontalDivider(
                                    color = UlakColors.SurfaceElevated,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                            val mins = (trip.durationSec / 60).coerceAtLeast(1)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column {
                                    Text(
                                        text = "${dateFmt.format(Date(trip.startTs))} • ${String.format(TR_LOCALE, "%.1f", trip.distanceKm)} km ($mins dk)",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = "Maks: ${trip.maxSpeedKmh} km/s • %${trip.evRatioPct} EV / %${trip.hevRatioPct} HEV • SoC Δ${trip.socDelta}%",
                                        color = UlakColors.TextMuted,
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
}
