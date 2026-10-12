package com.deadlockclipper.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object C {
    val bg = Color(0xFF15171C)
    val text = Color(0xFFE8E8EA)
    val subtle = Color(0xFF9A9CA5)
    val reason = Color(0xFFF0B35A)
    val button = Color(0xFF2A2D36)
    val buttonBorder = Color(0xFF3A3E4A)
    val primary = Color(0xFFD9452B)
    val card = Color(0xFF1E2027)
    val cardBorder = Color(0xFF2C2F39)
    val good = Color(0xFF6BD96B)
    val bad = Color(0xFFFF6B6B)

    // editor
    val edBg = Color(0xFF0F1116)
    val edPanel = Color(0xFF14171F)
    val edBorder = Color(0xFF232838)
    val accent = Color(0xFFFF5A36)
    val muted = Color(0xFF8A90A2)
    val stage = Color(0xFF0B0C10)
    val layerColors = listOf(
        Color(0xFFF0B35A), Color(0xFF4FC3F7), Color(0xFF81C784), Color(0xFFE57373),
        Color(0xFFBA68C8), Color(0xFFFFF176), Color(0xFF4DB6AC), Color(0xFFFF8A65),
    )

    fun layer(i: Int) = layerColors[((i % layerColors.size) + layerColors.size) % layerColors.size]
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.primary, onPrimary = Color.White, secondary = C.reason,
            background = C.bg, onBackground = C.text, surface = C.card, onSurface = C.text,
            surfaceVariant = C.button, onSurfaceVariant = C.subtle, outline = C.buttonBorder,
            surfaceContainer = C.card, surfaceContainerHigh = C.card, surfaceContainerHighest = C.button,
        ),
        content = content,
    )
}
