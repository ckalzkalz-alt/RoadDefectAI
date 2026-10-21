package com.roaddefect.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roaddefect.ai.AppContainer
import com.roaddefect.ai.ai.DetectorFactory
import com.roaddefect.ai.config.DetectionConfig
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(c: AppContainer, onBack: () -> Unit) {
    val initial = remember { c.configStore.load() }
    var minConf by remember { mutableFloatStateOf(initial.minConfidence) }
    var fps by remember { mutableFloatStateOf(initial.inferenceFps.toFloat()) }
    var cooldownS by remember { mutableFloatStateOf(initial.cooldownMs / 1000f) }
    var minDist by remember { mutableFloatStateOf(initial.minDistanceBetweenSameTypeM) }
    var forceMock by remember { mutableStateOf(c.configStore.forceMock) }
    var saved by remember { mutableStateOf(false) }

    ScreenScaffold("Settings", onBack) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Changes apply to the next inspection.")

            Text("Minimum confidence: ${(minConf * 100).roundToInt()}%")
            Slider(value = minConf, onValueChange = { minConf = it; saved = false }, valueRange = 0.5f..0.95f)

            Text("Analysis rate: ${fps.roundToInt()} frames/second")
            Slider(value = fps, onValueChange = { fps = it; saved = false }, valueRange = 1f..10f, steps = 8)

            Text("Duplicate cooldown: ${cooldownS.roundToInt()} s")
            Slider(value = cooldownS, onValueChange = { cooldownS = it; saved = false }, valueRange = 1f..15f)

            Text("Duplicate distance: ${minDist.roundToInt()} m")
            Slider(value = minDist, onValueChange = { minDist = it; saved = false }, valueRange = 2f..30f)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = forceMock, onCheckedChange = { forceMock = it; saved = false })
                Text("Force mock detector (development only)", modifier = Modifier.weight(1f))
            }
            Text(
                if (c.hasTrainedModel())
                    "Trained model file found: ${DetectorFactory.MODEL_ASSET} (${DetectorFactory.MODEL_VERSION})"
                else "No trained model file (${DetectorFactory.MODEL_ASSET}) in app assets — the mock detector will be used.",
            )

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    c.configStore.save(
                        initial.copy(
                            minConfidence = minConf, inferenceFps = fps.roundToInt(),
                            cooldownMs = (cooldownS * 1000).toLong(), minDistanceBetweenSameTypeM = minDist,
                        )
                    )
                    c.configStore.forceMock = forceMock
                    saved = true
                },
            ) { Text("Save") }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val d = DetectionConfig()
                    minConf = d.minConfidence; fps = d.inferenceFps.toFloat(); cooldownS = d.cooldownMs / 1000f
                    minDist = d.minDistanceBetweenSameTypeM; forceMock = false; saved = false
                },
            ) { Text("Reset to defaults") }
            if (saved) Text("Saved.")
        }
    }
}
