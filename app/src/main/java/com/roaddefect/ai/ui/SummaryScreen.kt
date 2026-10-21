package com.roaddefect.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roaddefect.ai.AppContainer
import com.roaddefect.ai.ai.DefectStatus
import com.roaddefect.ai.ai.DefectType
import com.roaddefect.ai.data.DefectEntity
import com.roaddefect.ai.data.InspectionEntity
import com.roaddefect.ai.data.InspectionStatus
import com.roaddefect.ai.location.LocationQuality
import com.roaddefect.ai.util.Format
import kotlinx.coroutines.launch

/** Used both right after stopping an inspection and when reopening one from History. */
@Composable
fun SummaryScreen(c: AppContainer, inspectionId: Long, onBack: () -> Unit, onMap: () -> Unit) {
    val inspection by c.repository.observeInspection(inspectionId).collectAsState(initial = null)
    val defects by c.repository.observeDefects(inspectionId).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    ScreenScaffold("Inspection Summary", onBack) { pad ->
        val insp = inspection
        if (insp == null) {
            Text("Loading…", modifier = Modifier.padding(pad).padding(20.dp))
            return@ScreenScaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SummaryCard(insp, defects) }
            item { Button(onClick = onMap, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("View map") } }
            item { Text("Defects (${defects.size})", style = MaterialTheme.typography.titleMedium) }
            items(defects, key = { it.id }) { d ->
                DefectRow(c, d, onStatus = { st -> scope.launch { c.repository.setDefectStatus(d.id, st) } })
            }
        }
    }
}

@Composable
private fun SummaryCard(insp: InspectionEntity, defects: List<DefectEntity>) {
    val live = defects.filter { it.status != DefectStatus.REJECTED.name }
    fun count(t: DefectType) = live.count { it.type == t.name }
    val mono = FontFamily.Monospace
    val duration = insp.endTime?.let { Format.durationWords((it - insp.startTime - insp.pausedMs).coerceAtLeast(0)) } ?: "unknown"

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("INSPECTION SUMMARY", fontWeight = FontWeight.Black)
            Text("Inspection ID: ${Format.inspectionId(insp.id)}", fontFamily = mono, fontSize = 13.sp)
            Text("Date: ${Format.date(insp.startTime)}", fontFamily = mono, fontSize = 13.sp)
            Text("Duration: $duration", fontFamily = mono, fontSize = 13.sp)
            Text("Distance: ${Format.km(insp.distanceMeters)}", fontFamily = mono, fontSize = 13.sp)
            Text("AI model: ${insp.modelVersion}", fontFamily = mono, fontSize = 13.sp)
            Text("Min. confidence: ${Format.percent(insp.minConfidence)}", fontFamily = mono, fontSize = 13.sp)
            Text("DEFECTS", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            DefectType.entries.forEach {
                Text("${(it.pluralName + ":").padEnd(22)}${count(it).toString().padStart(3)}", fontFamily = mono, fontSize = 13.sp)
            }
            Text("${"Total Defects:".padEnd(22)}${live.size.toString().padStart(3)}", fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("Rejected detections are not counted.", fontSize = 11.sp, modifier = Modifier.alpha(0.7f))
            if (insp.status == InspectionStatus.INTERRUPTED.name) {
                Text("This inspection was interrupted (app closed or device restarted). Data saved up to that point is shown.", color = Color(0xFFEF6C00), fontSize = 12.sp)
            }
            if (insp.modelIsMock) {
                MockBanner(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun DefectRow(c: AppContainer, d: DefectEntity, onStatus: (DefectStatus) -> Unit) {
    val type = DefectType.fromName(d.type)
    val status = DefectStatus.fromName(d.status)
    Card(Modifier.fillMaxWidth().alpha(if (status == DefectStatus.REJECTED) 0.55f else 1f)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DefectImage(c.repository.images, d.imagePath, Modifier.size(width = 112.dp, height = 84.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text("${Format.defectId(d.id)} · ${type?.displayName ?: d.type}", fontWeight = FontWeight.Bold, color = type?.color() ?: Color.Unspecified)
                    Text("Confidence: ${Format.percent(d.confidence)}", fontSize = 13.sp)
                    Text(Format.coords(d.latitude, d.longitude), fontSize = 12.sp)
                    Text("${Format.time(d.timestamp)} · ${Format.kmh(d.speedMps)}", fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip(status.displayName.uppercase(), status.chipColor())
                        when (d.locationQuality) {
                            LocationQuality.LOW.name -> Chip("LOW GPS ACCURACY", Color(0xFFEF6C00))
                            LocationQuality.NONE.name -> Chip("NO GPS", Color(0xFF6D4C41))
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (status != DefectStatus.CONFIRMED) TextButton(onClick = { onStatus(DefectStatus.CONFIRMED) }) { Text("Confirm") }
                if (status != DefectStatus.REJECTED) TextButton(onClick = { onStatus(DefectStatus.REJECTED) }) { Text("Reject") }
                if (status != DefectStatus.AI_DETECTED) TextButton(onClick = { onStatus(DefectStatus.AI_DETECTED) }) { Text("Reset") }
            }
        }
    }
}
