package tr.ulak.byd.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import tr.ulak.byd.mobile.model.TelemetryState
import tr.ulak.byd.mobile.model.TripRecordItem
import tr.ulak.byd.mobile.ui.theme.UlakColors

@Composable
fun ControlsHubScreen(
    state: TelemetryState,
    trips: List<TripRecordItem>,
    onSendCommand: (JSONObject) -> Unit,
) {
    var section by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { section = 0 },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (section == 0) UlakColors.Emerald else UlakColors.SurfaceElevated,
                ),
            ) {
                Text("Komutlar", fontSize = 12.sp)
            }
            Button(
                onClick = { section = 1 },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (section == 1) UlakColors.Cyan else UlakColors.SurfaceElevated,
                ),
            ) {
                Text("Otomasyon & Yolculuk", fontSize = 12.sp)
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (section == 0) {
                ControlsScreen(state = state, onSendCommand = onSendCommand)
            } else {
                TripsAutomationsScreen(state = state, trips = trips, onSendCommand = onSendCommand)
            }
        }
    }
}
