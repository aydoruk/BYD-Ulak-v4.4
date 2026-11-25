package tr.ulak.byd.mobile.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import tr.ulak.byd.mobile.model.TelemetryState
import tr.ulak.byd.mobile.ui.theme.UlakColors
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun CameraSentryScreen(
    state: TelemetryState,
    liveFrame: Bitmap?,
    streamStatus: String,
    streamTransport: String,
    onStartLanStream: () -> Unit,
    onStartCloudStream: () -> Unit,
    onStopStream: () -> Unit,
    onSendCommand: (JSONObject) -> Unit,
) {
    DisposableEffect(Unit) {
        if (state.connectedViaLan) onStartLanStream() else onStartCloudStream()
        onDispose { onStopStream() }
    }

    val cameraNames = listOf("Ön / AVM", "Arka", "360° AVM", "İç Kabin")

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
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "CANLI KAMERA ($streamTransport)",
                            color = UlakColors.Cyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        StatusChip(
                            label = when {
                                state.webrtc.cloudVideoStreaming -> "RENDER CANLI"
                                state.cameraHwActive -> "CAMERA2"
                                else -> "DİAGNOSTİK"
                            },
                            color = if (state.webrtc.cloudVideoStreaming || state.cameraHwActive) UlakColors.Emerald else UlakColors.Amber,
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(215.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(UlakColors.BgDark),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (liveFrame != null) {
                            Image(
                                bitmap = liveFrame.asImageBitmap(),
                                contentDescription = "BYD Canlı Kamera",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Rounded.CameraAlt, null, tint = UlakColors.TextMuted, modifier = Modifier.size(36.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(streamStatus, color = UlakColors.TextMuted, fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        cameraNames.forEachIndexed { idx, label ->
                            val selected = state.activeCameraIndex == idx
                            Button(
                                onClick = {
                                    onSendCommand(JSONObject().put("command", "SWITCH_CAMERA").put("camera_index", idx))
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selected) UlakColors.Emerald else UlakColors.SurfaceElevated,
                                ),
                            ) {
                                Text(label, fontSize = 10.sp, maxLines = 1)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onStartLanStream,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            Icon(Icons.Rounded.Wifi, null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("LAN Akış", fontSize = 11.sp)
                        }
                        Button(
                            onClick = onStartCloudStream,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = UlakColors.Cyan.copy(alpha = 0.25f)),
                        ) {
                            Icon(Icons.Rounded.Cloud, null, tint = UlakColors.Cyan, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Render 4G/5G", color = UlakColors.Cyan, fontSize = 11.sp)
                        }
                        Button(
                            onClick = {
                                onSendCommand(JSONObject().put("command", "SET_SENTRY").put("enable", !state.sentryActive))
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.sentryActive) UlakColors.Emerald else UlakColors.SurfaceElevated,
                            ),
                        ) {
                            Text(if (state.sentryActive) "Nöbetçi Açık" else "Nöbetçi", fontSize = 11.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "WebRTC Sinyal: ${state.webrtc.signalingState} • TURN: ${if (state.webrtc.turnEnabled) "Aktif" else "STUN/WS"} • ICE: ${state.webrtc.iceServerCount}",
                        color = UlakColors.TextMuted,
                        fontSize = 11.sp,
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("ANTİ-HIRSIZLIK ALARMI", color = UlakColors.Crimson, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Siren/far uyarısı, sürekli kamera izleme ve konum takibi başlatır.",
                            color = UlakColors.TextMuted,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(
                        onClick = {
                            onSendCommand(JSONObject().put("command", "SET_ANTI_THEFT").put("enable", !state.antiTheftActive))
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.antiTheftActive) UlakColors.Crimson else UlakColors.SurfaceElevated,
                        ),
                    ) {
                        Icon(Icons.Rounded.Warning, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (state.antiTheftActive) "DURDUR" else "AKTİF ET", fontSize = 11.sp)
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
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "NÖBETÇİ & GÜVENLİK OLAYLARI (${state.sentryStatus})",
                        color = UlakColors.TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    if (state.recentEvents.isEmpty()) {
                        Text(
                            text = "Henüz hareket veya güvenlik olayı yok. 12V akü 12.1V altına düşerse Nöbetçi otomatik durur.",
                            color = UlakColors.TextMuted,
                            fontSize = 12.sp,
                        )
                    } else {
                        val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                        state.recentEvents.forEach { ev ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("• ${ev.message}", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Text(fmt.format(Date(ev.timestamp)), color = UlakColors.Cyan, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
