package com.roaddefect.ai.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.roaddefect.ai.AppContainer
import com.roaddefect.ai.camera.CameraRules

const val SAFETY_MESSAGE =
    "Mount the device securely before starting the inspection. Do not interact with the device while driving."

@Composable
fun SetupScreen(c: AppContainer, onBack: () -> Unit, onStarted: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    fun anyLocation() = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    fun readiness() = CameraRules.readiness(granted(Manifest.permission.CAMERA), anyLocation())

    var mounted by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(readiness()) }
    var denied by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        status = readiness()
        if (status == CameraRules.Readiness.BLOCKED_NO_CAMERA) denied = true
        else { c.controller.start(); onStarted() }
    }

    // Permissions can also be changed in system settings; refresh when the user comes back.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                status = readiness()
                if (status != CameraRules.Readiness.BLOCKED_NO_CAMERA) denied = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ScreenScaffold("Start Inspection", onBack) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF5D4037))) {
                Text(SAFETY_MESSAGE, modifier = Modifier.padding(16.dp), color = Color.White, fontWeight = FontWeight.SemiBold)
            }

            Text("Permissions needed", style = MaterialTheme.typography.titleMedium)
            PermissionRow("Camera (required)", granted(Manifest.permission.CAMERA))
            PermissionRow("Location / GPS (stamps defect positions)", anyLocation())
            Text(
                "No motion-sensor permission is needed. Everything runs on this device; no internet connection is required to detect or record defects.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (status == CameraRules.Readiness.READY_WITHOUT_GPS) {
                Text("Without location permission, defects are saved with no coordinates.", color = MaterialTheme.colorScheme.primary)
            } else if (granted(Manifest.permission.ACCESS_COARSE_LOCATION) && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                Text(
                    "Approximate location only: defect positions will be flagged as low accuracy.",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (denied) {
                Text(
                    "Camera permission is required. Enable it for RoadDefect AI in the system app settings, then try again.",
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = mounted, onCheckedChange = { mounted = it })
                Text("The device is securely mounted and I will not interact with it while driving")
            }
            Button(
                enabled = mounted,
                onClick = {
                    denied = false
                    launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                },
                modifier = Modifier.fillMaxWidth().height(60.dp),
            ) { Text("Begin inspection") }
        }
    }
}

@Composable
private fun PermissionRow(label: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f))
        Text(if (ok) "Granted" else "Not granted", color = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
    }
}
