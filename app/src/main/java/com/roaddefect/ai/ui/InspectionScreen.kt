package com.roaddefect.ai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roaddefect.ai.AppContainer
import com.roaddefect.ai.ai.DefectType
import com.roaddefect.ai.camera.CameraPreview
import com.roaddefect.ai.camera.CameraRules
import com.roaddefect.ai.inspection.InspectionUiState
import com.roaddefect.ai.inspection.OverlayBox
import com.roaddefect.ai.inspection.Phase
import com.roaddefect.ai.location.LocationQuality
import com.roaddefect.ai.util.Format
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private val HudBg = Color(0xB3000000)

@Composable
fun InspectionScreen(c: AppContainer, onFinished: (Long) -> Unit, onFailed: () -> Unit) {
    val controller = c.controller
    val s by controller.state.collectAsState()
    val completed by controller.completedId.collectAsState()

    // Back must never end the inspection: stopping needs a deliberate press-and-hold.
    BackHandler(enabled = true) { }

    LaunchedEffect(completed) {
        completed?.let { controller.consumeCompleted(); onFinished(it) }
    }
    LaunchedEffect(s.phase, s.message) {
        if (s.phase == Phase.IDLE && s.message != null) onFailed()
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val aspect = CameraRules.previewAspect(constraints.maxWidth, constraints.maxHeight)
        val fitsHeight = maxWidth / maxHeight > aspect
        val areaH = if (fitsHeight) maxHeight else maxWidth / aspect
        val areaW = if (fitsHeight) maxHeight * aspect else maxWidth

        // Camera + overlay share one box with the same aspect as the 16:9 stream, so overlay coordinates match.
        Box(Modifier.align(Alignment.Center).width(areaW).height(areaH)) {
            // Keying on the error forces a fresh preview after a camera failure, so Resume retries the bind.
            key(s.cameraError) {
                if (s.phase != Phase.IDLE && s.phase != Phase.STARTING) {
                    CameraPreview(controller, Modifier.fillMaxSize(), onError = { controller.reportCameraError(it) })
                }
            }
            DetectionOverlay(s.overlay, Modifier.fillMaxSize())
        }

        Column(Modifier.align(Alignment.TopStart).fillMaxWidth()) {
            Header(s)
            if (s.isMock) MockBanner(Modifier.fillMaxWidth())
            s.message?.let { Text(it, color = Color.White, modifier = Modifier.fillMaxWidth().background(Color(0xFF8D6E63)).padding(8.dp), fontSize = 13.sp) }
            s.cameraError?.let { Text(it, color = Color.White, modifier = Modifier.fillMaxWidth().background(Color(0xFFB71C1C)).padding(8.dp), fontSize = 13.sp) }
            if (s.locationPermissionMissing) Text("No location permission: defects are saved without coordinates.", color = Color.White, modifier = Modifier.fillMaxWidth().background(Color(0xFF8D6E63)).padding(8.dp), fontSize = 13.sp)
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Stats(s)
            Controls(s, onPause = { controller.pause() }, onResume = { controller.resume() }, onStop = { controller.stop() })
        }
    }
}

@Composable
private fun Header(s: InspectionUiState) {
    Row(
        Modifier.fillMaxWidth().background(HudBg).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("ROADDEFECT AI", color = Color.White, fontWeight = FontWeight.Black, fontSize = 15.sp)
        val (label, color) = when (s.phase) {
            Phase.ACTIVE -> "INSPECTION ACTIVE" to Color(0xFF2E7D32)
            Phase.PAUSED -> "PAUSED" to Color(0xFFEF6C00)
            Phase.STARTING -> "STARTING…" to Color(0xFF546E7A)
            Phase.STOPPING -> "SAVING…" to Color(0xFF546E7A)
            Phase.IDLE -> "IDLE" to Color(0xFF546E7A)
        }
        Chip(label, color)
        Text(Format.clock(s.elapsedMs), color = Color.White, fontSize = 15.sp)
    }
}

@Composable
private fun Stats(s: InspectionUiState) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(HudBg).padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("Defects detected: ${s.total}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            DefectType.entries.forEach {
                Text("${it.pluralName}: ${s.counts[it] ?: 0}", color = it.color(), fontSize = 12.sp)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            val gps = when {
                s.locationPermissionMissing -> "NO PERMISSION"
                s.gpsQuality == LocationQuality.GOOD -> "ACTIVE"
                s.gpsQuality == LocationQuality.LOW -> "LOW ACCURACY"
                else -> "SEARCHING…"
            }
            Text("GPS: $gps", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("Accuracy: ${s.gpsAccuracyM?.let { "%.0f m".format(it) } ?: "—"}", color = Color.White, fontSize = 12.sp)
            Text("Speed: ${Format.kmh(s.speedMps)}", color = Color.White, fontSize = 12.sp)
            Text("Distance: ${Format.km(s.distanceM)}", color = Color.White, fontSize = 12.sp)
            Text("AI: ${s.inferenceMs} ms", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
        }
    }
}

@Composable
private fun Controls(s: InspectionUiState, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    val enabled = s.phase == Phase.ACTIVE || s.phase == Phase.PAUSED
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = { if (s.phase == Phase.PAUSED) onResume() else onPause() },
            enabled = enabled,
            modifier = Modifier.weight(1f).height(56.dp),
        ) { Text(if (s.phase == Phase.PAUSED) "Resume" else "Pause") }
        HoldToStopButton(enabled = enabled, onConfirm = onStop, modifier = Modifier.weight(1f).height(56.dp))
    }
}

/** Stop must be held for 1.5 s, so a bump or stray tap cannot end the inspection. */
@Composable
private fun HoldToStopButton(enabled: Boolean, onConfirm: () -> Unit, modifier: Modifier) {
    val progress = remember { Animatable(0f) }
    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(if (enabled) Color(0xFFB71C1C) else Color.Gray)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    coroutineScope {
                        val job = launch {
                            progress.animateTo(1f, tween(1500, easing = LinearEasing))
                            onConfirm()
                        }
                        tryAwaitRelease()
                        job.cancel()
                        progress.snapTo(0f)
                    }
                })
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxSize().padding(0.dp)) {
            Box(Modifier.fillMaxWidth(progress.value).fillMaxSize().background(Color(0x66FFFFFF)))
        }
        Text("Hold to stop", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
internal fun DetectionOverlay(boxes: List<OverlayBox>, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        for (b in boxes) {
            val color = b.type.color()
            val topLeft = Offset(b.box.left * size.width, b.box.top * size.height)
            val boxSize = Size(b.box.width * size.width, b.box.height * size.height)
            drawRect(color, topLeft, boxSize, style = Stroke(width = 3.dp.toPx())) // thin outline: keeps the road visible
            val label = "${b.type.displayName.uppercase()} ${Format.percent(b.confidence)}" + if (b.recorded) " · RECORDED" else ""
            val layout = measurer.measure(label, TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold))
            val labelTop = (topLeft.y - layout.size.height).coerceAtLeast(0f)
            drawRect(color, Offset(topLeft.x, labelTop), Size(layout.size.width + 8.dp.toPx(), layout.size.height.toFloat()))
            drawText(layout, topLeft = Offset(topLeft.x + 4.dp.toPx(), labelTop))
        }
    }
}
