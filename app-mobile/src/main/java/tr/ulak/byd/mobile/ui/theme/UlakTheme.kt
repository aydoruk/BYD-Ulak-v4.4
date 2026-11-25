package tr.ulak.byd.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object UlakColors {
    val BgDark = Color(0xFF060B14)
    val BarDark = Color(0xFF0B1324)
    val CardDark = Color(0xFF0F172A)
    val SurfaceElevated = Color(0xFF1E293B)
    val Emerald = Color(0xFF10B981)
    val EmeraldLight = Color(0xFF34D399)
    val Cyan = Color(0xFF22D3EE)
    val Amber = Color(0xFFF59E0B)
    val Crimson = Color(0xFFEF4444)
    val TextPrimary = Color(0xFFF8FAFC)
    val TextMuted = Color(0xFF94A3B8)
}

@Composable
fun UlakTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = UlakColors.Emerald,
            secondary = UlakColors.Cyan,
            background = UlakColors.BgDark,
            surface = UlakColors.CardDark,
            error = UlakColors.Crimson,
        ),
        content = content,
    )
}
