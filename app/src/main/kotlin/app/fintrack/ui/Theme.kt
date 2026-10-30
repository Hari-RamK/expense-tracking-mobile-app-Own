package app.fintrack.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val Ink = Color(0xFF1F2A44)
    val Teal = Color(0xFF0F766E)
    val Amber = Color(0xFFB45309)
    val Red = Color(0xFFB91C1C)
    val Green = Color(0xFF15803D)
    val Slate = Color(0xFF64748B)
    val AmberTint = Color(0xFFFEF3C7)
    val RedTint = Color(0xFFFEE2E2)
    val GreenTint = Color(0xFFDCFCE7)
    val TealTint = Color(0xFFCCFBF1)
}

private val Light = lightColorScheme(
    primary = Palette.Teal, onPrimary = Color.White, secondary = Palette.Ink, error = Palette.Red,
    background = Color(0xFFF8FAFC), surface = Color.White, surfaceVariant = Color(0xFFF1F5F9),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF5EEAD4), onPrimary = Color(0xFF042F2E), secondary = Color(0xFFCBD5E1), error = Color(0xFFFCA5A5),
    background = Color(0xFF0F172A), surface = Color(0xFF1E293B), surfaceVariant = Color(0xFF273449),
)

@Composable
fun FinTrackTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
