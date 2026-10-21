package com.roaddefect.ai.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.roaddefect.ai.AppContainer
import com.roaddefect.ai.ai.DefectStatus
import com.roaddefect.ai.ai.DefectType
import com.roaddefect.ai.util.Format
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Markers for every non-rejected defect with a position, plus the driven route when recorded.
 * Marker shape and colour differ per category. Map tiles need internet the first time an area is
 * viewed; markers and route draw offline over a blank background.
 */
@Composable
fun MapScreen(c: AppContainer, inspectionId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val defects by c.repository.observeDefects(inspectionId).collectAsState(initial = emptyList())
    val route by produceState(initialValue = emptyList<GeoPoint>(), inspectionId) {
        value = c.repository.getRoute(inspectionId).map { GeoPoint(it.latitude, it.longitude) }
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
        }
    }
    var framed = remember { booleanArrayOf(false) }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) mapView.onResume()
            if (e == Lifecycle.Event.ON_PAUSE) mapView.onPause()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); mapView.onDetach() }
    }

    val shown = defects.filter { it.status != DefectStatus.REJECTED.name && it.latitude != null && it.longitude != null }
    val withoutGps = defects.count { it.status != DefectStatus.REJECTED.name && (it.latitude == null || it.longitude == null) }

    ScreenScaffold("Inspection Map", onBack) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { mapView },
                update = { map ->
                    map.overlays.clear()
                    if (route.size >= 2) {
                        map.overlays.add(Polyline().apply {
                            setPoints(route)
                            outlinePaint.color = 0xFF1E88E5.toInt()
                            outlinePaint.strokeWidth = 8f
                        })
                    }
                    shown.forEach { d ->
                        val type = DefectType.fromName(d.type) ?: return@forEach
                        map.overlays.add(Marker(map).apply {
                            position = GeoPoint(d.latitude!!, d.longitude!!)
                            icon = markerIcon(context, type)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            title = "Defect: ${type.displayName}"
                            snippet = "Confidence: ${Format.percent(d.confidence)}\n" +
                                "Location: ${Format.coords(d.latitude, d.longitude)}\n" +
                                "Detected: ${Format.time(d.timestamp)}\n" +
                                "Speed: ${Format.kmh(d.speedMps)}"
                        })
                    }
                    val points = route + shown.map { GeoPoint(it.latitude!!, it.longitude!!) }
                    if (!framed[0] && points.isNotEmpty()) {
                        framed[0] = true
                        map.post {
                            if (points.size == 1) { map.controller.setZoom(18.0); map.controller.setCenter(points[0]) }
                            else map.zoomToBoundingBox(org.osmdroid.util.BoundingBox.fromGeoPoints(points), true, 120)
                        }
                    }
                    map.invalidate()
                },
            )
            Legend(Modifier.align(Alignment.TopStart).padding(8.dp))
            if (shown.isEmpty() && route.isEmpty()) {
                Text("No positioned defects or route to show.", modifier = Modifier.align(Alignment.Center).background(Color(0xCC000000)).padding(12.dp), color = Color.White)
            }
            if (withoutGps > 0) {
                Text("$withoutGps defect(s) have no GPS position and are not shown.", color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).background(Color(0xCC000000)).padding(8.dp))
            }
        }
    }
}

@Composable
private fun Legend(modifier: Modifier) {
    Column(modifier.background(Color(0xCC000000)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        DefectType.entries.forEach { Text("${it.code}  ${it.displayName}", color = it.color(), fontSize = 12.sp) }
    }
}

/** Distinct shape + colour + code per category: circle PH, square LC, diamond TC, triangle AC. */
private fun markerIcon(context: Context, type: DefectType): Drawable {
    val size = 72
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = type.color().toArgb() }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 5f }
    val m = 6f
    when (type) {
        DefectType.POTHOLE -> {
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - m, fill)
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - m, stroke)
        }
        DefectType.LONGITUDINAL_CRACK -> {
            val r = RectF(m, m, size - m, size - m)
            canvas.drawRoundRect(r, 8f, 8f, fill); canvas.drawRoundRect(r, 8f, 8f, stroke)
        }
        DefectType.TRANSVERSE_CRACK -> {
            val p = Path().apply { moveTo(size / 2f, 2f); lineTo(size - 2f, size / 2f); lineTo(size / 2f, size - 2f); lineTo(2f, size / 2f); close() }
            canvas.drawPath(p, fill); canvas.drawPath(p, stroke)
        }
        DefectType.ALLIGATOR_CRACK -> {
            val p = Path().apply { moveTo(size / 2f, 3f); lineTo(size - 3f, size - 6f); lineTo(3f, size - 6f); close() }
            canvas.drawPath(p, fill); canvas.drawPath(p, stroke)
        }
    }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; textSize = 24f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    val yOffset = if (type == DefectType.ALLIGATOR_CRACK) 0.74f else 0.6f
    canvas.drawText(type.code, size / 2f, size * yOffset, text)
    return BitmapDrawable(context.resources, bmp)
}
