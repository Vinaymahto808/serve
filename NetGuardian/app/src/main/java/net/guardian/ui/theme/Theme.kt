package net.guardian.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4CC9F0),
    secondary = Color(0xFF7209B7),
    tertiary = Color(0xFFF72585),
    background = Color(0xFF0F0F23),
    surface = Color(0xFF1A1A2E),
    surfaceVariant = Color(0xFF16213E),
    onBackground = Color(0xFFEEEEEE),
    onSurface = Color(0xFFEEEEEE),
    error = Color(0xFFEF233C)
)

@Composable
fun NetGuardianTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
