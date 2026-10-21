package com.roaddefect.ai.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.roaddefect.ai.ai.DefectType

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB300), onPrimary = Color(0xFF1A1200),
    secondary = Color(0xFF4FC3F7), background = Color(0xFF101418), surface = Color(0xFF181D23),
    surfaceVariant = Color(0xFF232A32), onBackground = Color(0xFFE6EAEE), onSurface = Color(0xFFE6EAEE),
)
private val Light = lightColorScheme(
    primary = Color(0xFF8A5B00), secondary = Color(0xFF00629B), background = Color(0xFFF6F7F9), surface = Color.White,
)

@Composable
fun RoadDefectTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** One colour per defect category, shared by overlay, lists and map markers. */
fun DefectType.color(): Color = when (this) {
    DefectType.POTHOLE -> Color(0xFFE53935)
    DefectType.LONGITUDINAL_CRACK -> Color(0xFFFFB300)
    DefectType.TRANSVERSE_CRACK -> Color(0xFF29B6F6)
    DefectType.ALLIGATOR_CRACK -> Color(0xFFAB47BC)
}
