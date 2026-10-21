package com.roaddefect.ai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roaddefect.ai.AppContainer
import com.roaddefect.ai.data.InspectionStatus
import com.roaddefect.ai.data.InspectionWithCount
import com.roaddefect.ai.util.Format
import kotlinx.coroutines.launch

fun activeDurationMs(i: InspectionWithCount): Long? =
    i.endTime?.let { (it - i.startTime - i.pausedMs).coerceAtLeast(0) }

@Composable
fun HistoryScreen(c: AppContainer, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val items by c.repository.observeHistory().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var toDelete by remember { mutableStateOf<InspectionWithCount?>(null) }

    ScreenScaffold("Inspection History", onBack) { pad ->
        if (items.isEmpty()) {
            Text("No inspections yet.", modifier = Modifier.padding(pad).padding(24.dp))
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { it.id }) { i ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(i.id) }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(Format.inspectionId(i.id), fontWeight = FontWeight.Bold)
                                Text(Format.date(i.startTime))
                                Text(Format.km(i.distanceMeters))
                                Text("${i.defectCount} defects")
                                Text(activeDurationMs(i)?.let { Format.durationWords(it) } ?: "Duration unknown")
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (i.status == InspectionStatus.INTERRUPTED.name) Chip("INTERRUPTED", Color(0xFFEF6C00))
                                    if (i.modelIsMock) Chip("MOCK DETECTOR", Color(0xFFB71C1C))
                                }
                            }
                            IconButton(onClick = { toDelete = i }) { Icon(Icons.Default.Delete, "Delete inspection") }
                        }
                    }
                }
            }
        }
    }

    toDelete?.let { i ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete ${Format.inspectionId(i.id)}?") },
            text = { Text("This permanently deletes the inspection, its defects and photos from this device.") },
            confirmButton = {
                TextButton(onClick = { scope.launch { c.repository.deleteInspection(i.id) }; toDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}
