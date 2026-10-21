package com.roaddefect.ai.ui

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.roaddefect.ai.ai.DefectType
import com.roaddefect.ai.inspection.OverlayBox
import com.roaddefect.ai.util.Format
import com.roaddefect.ai.video.VideoDetectionRunner
import com.roaddefect.ai.video.VideoProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val HudBg = Color(0xB3000000)

/**
 * "Validate from video": run the trained detector over a video file.
 *
 * This is DETECTION VALIDATION ONLY. It intentionally bypasses InspectionController, so no GPS is
 * read, nothing is persisted to History, and the defect tracker is not involved. That keeps the
 * measurement honest (see VideoDetectionRunner for why the inspection path would corrupt it) and
 * guarantees a video run can never be mistaken for a real inspection.
 */
@Composable
fun VideoValidationScreen(c: com.roaddefect.ai.AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = remember { VideoDetectionRunner(context) }

    var uri by remember { mutableStateOf<Uri?>(null) }
    var playback by remember { mutableStateOf<VideoPlayback?>(null) }
    var letterboxWarned by remember { mutableStateOf(false) }
    var modelVersion by remember { mutableStateOf("") }
    var isMock by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf<VideoDetectionRunner.FrameResult?>(null) }
    var progress by remember { mutableStateOf(VideoDetectionRunner.Progress(0, 0, null)) }
    var totals by remember { mutableStateOf<Map<DefectType, Int>>(emptyMap()) }
    var summary by remember { mutableStateOf<VideoDetectionRunner.Summary?>(null) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            uri = picked
            playback = VideoPlayback(picked, letterboxed = false)
            frame = null
            summary = null
            error = null
            letterboxWarned = false
            progress = VideoDetectionRunner.Progress(0, 0, null)
            // Measuring the bars is best-effort: it only decides whether to show a hint.
            scope.launch {
                val flagged = withContext(Dispatchers.IO) { VideoProbe.isHeavilyLetterboxed(context, picked) }
                letterboxWarned = flagged
            }
        }
    }

    fun start(target: Uri) {
        job?.cancel()
        running = true
        error = null
        summary = null
        frame = null
        totals = emptyMap()
        progress = VideoDetectionRunner.Progress(0, 0, null)
        job = scope.launch {
            runner.run(target).collect { event ->
                when (event) {
                    is VideoDetectionRunner.Event.Started -> {
                        modelVersion = event.modelVersion
                        isMock = event.isMock
                    }
                    is VideoDetectionRunner.Event.Frame -> {
                        progress = event.progress
                        frame = event.result
                        val add = event.result.detections.groupingBy { it.type }.eachCount()
                        totals = totals.toMutableMap().apply {
                            for ((k, v) in add) this[k] = (this[k] ?: 0) + v
                        }
                    }
                    is VideoDetectionRunner.Event.Finished -> {
                        progress = event.progress
                        summary = event.summary
                        frame = null
                        running = false
                    }
                    is VideoDetectionRunner.Event.Failed -> {
                        error = event.message
                        running = false
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    ScreenScaffold("Validate from video", onBack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // The disclaimer is deliberately unmissable: a video run must never be confused with a
            // real, GPS-stamped inspection.
            Text(
                "DETECTION VALIDATION ONLY — no GPS, not saved to Inspection History",
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().background(Color(0xFF5D4037)).padding(horizontal = 12.dp, vertical = 6.dp),
            )
            if (isMock) MockBanner(Modifier.fillMaxWidth())
            error?.let {
                Text(
                    it, color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFB71C1C)).padding(10.dp),
                )
            }

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("video/*")) },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text(if (uri == null) "Choose a video" else "Choose a different video") }

                val current = uri
                if (current != null) {
                    Button(
                        onClick = { start(current) },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                    ) { Text(if (running) "Analysing…" else "Analyse video", fontSize = 18.sp) }
                }

                if (modelVersion.isNotEmpty()) {
                    Text(
                        "Detector: ${if (isMock) "MOCK" else "trained model"} $modelVersion",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    )
                }

                if (current != null) {
                    VideoPane(playback, frame)
                    if (letterboxWarned) {
                        Text(
                            "Heads up: this video is heavily letterboxed — its picture is a thin strip " +
                                "in a black frame, so it will look small here and the detector can only " +
                                "use a fraction of each frame. Re-exporting it without the bars, or " +
                                "recording the road framed to fill the picture, would be much more useful.",
                            fontSize = 11.sp,
                            color = Color(0xFF8D6E63),
                        )
                    }
                    LiveStats(progress, frame, totals)
                }

                summary?.let { SummaryCard(it, totals) }

                Text(
                    "How to read this: boxes are drawn by the same detector and the same overlay code " +
                        "the inspection uses, so what you see here is what the app would see. A video shot " +
                        "on a phone has a different lens, height and angle than the RDD2022 training data, " +
                        "so this measures how the model copes with that difference — it is not the same as " +
                        "driving the route.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/**
 * The video, with the SAME overlay composable the live inspection draws, so boxes look identical.
 *
 * The pane is given a generous share of the viewport rather than a small fraction of it, because
 * watching the video is the entire point of this screen. The stats and summary below it scroll.
 *
 * The video handle is created ONCE and the URI set from `update`; the pane recomposes on every
 * analysed frame, so building the view in `factory` would restart playback continuously.
 */
@Composable
private fun VideoPane(playback: VideoPlayback?, frame: VideoDetectionRunner.FrameResult?) {
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
        // Fill the width at 4:3, capped so the controls below stay reachable. The old layout sized
        // the pane from a 16:9 assumption and collapsed to ~315px wide on a 1080px screen.
        val byWidth = maxWidth * 3f / 4f
        val maximum = maxHeight * 0.62f
        val h = if (byWidth < maximum) byWidth else maximum
        Box(Modifier.fillMaxWidth().height(h)) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                        setOnPreparedListener { it.isLooping = false }
                    }
                },
                update = { view ->
                    val pb = playback
                    if (pb == null) {
                        runCatching { view.stopPlayback() }
                    } else if (view.tag != pb.uri) {
                        view.tag = pb.uri
                        view.setVideoURI(pb.uri)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            val boxes = frame?.detections?.map {
                OverlayBox(it.type, it.confidence, it.boundingBox, recorded = false)
            }.orEmpty()
            DetectionOverlay(boxes, Modifier.fillMaxSize())
        }
    }
}

/** What the video pane needs to play a clip: the source URI plus whether it was flagged. */
private data class VideoPlayback(val uri: Uri, val letterboxed: Boolean)

@Composable
private fun LiveStats(
    progress: VideoDetectionRunner.Progress,
    frame: VideoDetectionRunner.FrameResult?,
    totals: Map<DefectType, Int>,
) {
    val perFrame = frame?.detections?.groupingBy { it.type }?.eachCount().orEmpty()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(HudBg).padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("Frame ${frame?.frameIndex?.toString() ?: "—"} · analysed ${progress.framesAnalysed}", color = Color.White, fontSize = 12.sp)
            Text("Video time ${frame?.let { Format.clock(it.videoTimeMs) } ?: "—"}", color = Color.White, fontSize = 12.sp)
            Text("Frames with detections: ${progress.framesWithDetections}", color = Color.White, fontSize = 12.sp)
            Text("In this frame: ${frame?.detections?.size ?: 0}", color = Color.White, fontSize = 12.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("RUN TOTAL", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            DefectType.entries.forEach {
                // Run total, with this frame's contribution in brackets, so a finished run is
                // still readable (the per-frame figure drops to 0 once the last frame is done).
                Text(
                    "${it.pluralName}: ${totals[it] ?: 0}" + (perFrame[it]?.let { " (+$it)" } ?: ""),
                    color = it.color(), fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(s: VideoDetectionRunner.Summary, totals: Map<DefectType, Int>) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Run finished", fontWeight = FontWeight.Bold)
        Text("Frames analysed: ${s.framesAnalysed}", fontSize = 13.sp)
        Text("Frames with detections: ${s.framesWithDetections} (${Format.percent(pct(s.framesWithDetections, s.framesAnalysed))})", fontSize = 13.sp)
        Text("Mean inference: ${s.meanInferenceMs} ms per frame", fontSize = 13.sp)
        Text("Wall time: ${Format.clock(s.elapsedWallMs)}", fontSize = 13.sp)
        Text("Total detections: ${totals.values.sum()}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        DefectType.entries.forEach {
            Text("  ${it.pluralName}: ${totals[it] ?: 0}", color = it.color(), fontSize = 13.sp)
        }
        if (s.decodeFailures > 0) {
            Text("${s.decodeFailures} frame(s) could not be decoded and were skipped", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        }
        if (s.framesAnalysed == 0) {
            Text("No frames were analysed — the video may be unreadable.", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun pct(n: Int, total: Int): Float = if (total <= 0) 0f else n.toFloat() / total
