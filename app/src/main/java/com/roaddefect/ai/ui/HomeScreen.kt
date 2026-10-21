package com.roaddefect.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roaddefect.ai.AppContainer
import com.roaddefect.ai.ai.DetectorFactory
import com.roaddefect.ai.inspection.Phase

@Composable
fun HomeScreen(
    c: AppContainer,
    onStart: () -> Unit,
    onReturnToInspection: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    val state by c.controller.state.collectAsState()
    val mock = c.activeDetectorIsMock

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("ROADDEFECT AI", fontSize = 30.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        Text("Road condition inspection", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))

        if (mock) MockBanner(Modifier.fillMaxWidth())

        state.message?.let { msg ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(msg)
                    TextButton(onClick = { c.controller.clearMessage() }) { Text("Dismiss") }
                }
            }
        }

        if (state.phase != Phase.IDLE) {
            Button(onClick = onReturnToInspection, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text("Return to active inspection", fontSize = 18.sp)
            }
        } else {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text("Start Inspection", fontSize = 18.sp)
            }
        }
        OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Inspection History") }
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Settings") }

        Spacer(Modifier.height(8.dp))
        Text(
            if (mock) "Detector: MOCK (development only — no trained model installed)"
            else "Detector: trained model ${DetectorFactory.MODEL_VERSION}",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
    }
}
