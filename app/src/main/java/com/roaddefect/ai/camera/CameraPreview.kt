package com.roaddefect.ai.camera

import android.content.pm.PackageManager
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.roaddefect.ai.inspection.InspectionController
import java.util.concurrent.Executors

/**
 * CameraX preview + analysis bound to the composition's lifecycle. Camera resources are released when
 * the screen leaves composition or the lifecycle stops; rotation is re-applied on configuration change.
 */
@Composable
fun CameraPreview(
    controller: InspectionController,
    modifier: Modifier = Modifier,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val selector = remember {
        ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).build()
    }
    val preview = remember { Preview.Builder().setResolutionSelector(selector).build() }
    val analysis = remember {
        ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(executor, FrameAnalyzer(controller)) }
    }

    DisposableEffect(lifecycleOwner) {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            onError("No camera available on this device.")
            return@DisposableEffect onDispose { }
        }
        var disposed = false
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                if (disposed) {
                    // The screen went away while the provider was still starting: release it anyway.
                    provider.unbindAll()
                    return@addListener
                }
                preview.setSurfaceProvider(previewView.surfaceProvider)
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (t: Throwable) {
                if (!disposed) onError("Camera unavailable: ${t.message ?: "unknown error"}")
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            if (future.isDone) {
                runCatching { future.get().unbindAll() }
            } else {
                // Provider not ready yet: unbind as soon as it is, so the camera is never left open.
                future.addListener(
                    { runCatching { future.get().unbindAll() } },
                    ContextCompat.getMainExecutor(context),
                )
            }
        }
    }

    // Keep the target rotation in sync with the display (the Activity handles rotation itself).
    LaunchedEffect(configuration.orientation, configuration.screenWidthDp) {
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        preview.targetRotation = rotation
        analysis.targetRotation = rotation
    }

    DisposableEffect(Unit) {
        onDispose {
            analysis.clearAnalyzer()
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
