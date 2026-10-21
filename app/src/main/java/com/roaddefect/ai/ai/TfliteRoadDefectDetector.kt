package com.roaddefect.ai.ai

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Production path: runs a custom-trained TFLite object detector from app assets.
 *
 * Expected model (see docs/TRAINING.md): a YOLOv8-style detector exported to TFLite with
 *  - input  : [1, S, S, 3] float32, RGB, values 0..1
 *  - output : [1, 4 + C, N] float32, rows = cx, cy, w, h (normalised 0..1 of input size), then C
 *             class scores. C = 4, class order = [DefectType.modelClassIndex].
 * Different export layouts (e.g. [1, N, 4+C]) are handled; anything else fails fast in [initialize]
 * rather than producing wrong detections.
 *
 * **Box scale.** The contract above says boxes are normalised 0..1, but the standard
 * Ultralytics YOLOv8 export actually decodes anchors in *input-pixel* space (measured
 * 2.77..639.0 at input 640 on a real RDD2022 export, identically in ONNX and TFLite).
 * Feeding those straight to [BoundingBox] would clamp every box to a degenerate sliver
 * and report no detections at all. [initialize] therefore probes the export once and
 * rescales as needed, so both conventions work without a code change.
 *
 * NOTE: the model output path has been validated against a trained model; see
 * docs/TRAINING.md for the numbers. Field behaviour still needs real drives.
 */
class TfliteRoadDefectDetector(
    private val context: Context,
    private val modelAssetPath: String,
    override val modelVersion: String,
    private val numThreads: Int = 4,
    private val nmsIouThreshold: Float = 0.5f,
    /** Pre-NMS floor; the user-facing confidence threshold is applied later in the pipeline. */
    private val candidateScoreFloor: Float = 0.25f,
) : RoadDefectDetector {

    override val isMock: Boolean = false

    private var interpreter: Interpreter? = null
    private var inputSize = 0
    private var channelsFirst = true
    private var numPredictions = 0
    private var numClasses = 0
    /** Multiplier applied to raw cx/cy/w/h to bring them into 0..1 (1f or 1/inputSize). */
    private var boxScale = 1f
    private lateinit var inputBuffer: ByteBuffer
    private lateinit var pixels: IntArray
    private lateinit var output: Array<Array<FloatArray>>
    private var scaled: Bitmap? = null

    override fun initialize() {
        val model = loadModel()
        val interp = Interpreter(model, Interpreter.Options().apply { setNumThreads(numThreads) })

        val inShape = interp.getInputTensor(0).shape() // [1, S, S, 3]
        require(inShape.size == 4 && inShape[3] == 3 && inShape[1] == inShape[2]) {
            "Unsupported input shape ${inShape.toList()}; expected [1,S,S,3]"
        }
        inputSize = inShape[1]

        val outShape = interp.getOutputTensor(0).shape()
        require(outShape.size == 3 && outShape[0] == 1) { "Unsupported output shape ${outShape.toList()}" }
        val expectedChannels = 4 + DefectType.entries.size
        when {
            outShape[1] == expectedChannels -> { channelsFirst = true; numPredictions = outShape[2] }
            outShape[2] == expectedChannels -> { channelsFirst = false; numPredictions = outShape[1] }
            else -> error(
                "Model output ${outShape.toList()} does not match 4 box values + ${DefectType.entries.size} classes. " +
                    "The model must be trained on exactly the four MVP classes."
            )
        }
        numClasses = DefectType.entries.size
        output = Array(1) { if (channelsFirst) Array(expectedChannels) { FloatArray(numPredictions) } else Array(numPredictions) { FloatArray(expectedChannels) } }
        inputBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3).order(ByteOrder.nativeOrder())
        pixels = IntArray(inputSize * inputSize)
        interpreter = interp
        boxScale = probeBoxScale(interp)
    }

    /**
     * Runs one inference on an all-zero frame to see whether this export emits boxes in
     * input-pixel space or normalised 0..1.
     *
     * Decoded anchors are always grid-anchored, so a pixel-space export can never report a
     * max box coordinate below (inputSize - stride) regardless of image content, whereas a
     * normalised export is bounded by 1. The threshold is therefore far from both.
     */
    private fun probeBoxScale(interp: Interpreter): Float {
        inputBuffer.rewind() // freshly allocated => all zero
        interp.run(inputBuffer, output)
        var maxCoord = 0f
        for (i in 0 until numPredictions) {
            for (ch in 0 until 4) {
                val v = kotlin.math.abs(if (channelsFirst) output[0][ch][i] else output[0][i][ch])
                if (v > maxCoord) maxCoord = v
            }
        }
        return boxScaleMultiplier(maxCoord, inputSize)
    }

    private fun loadModel(): MappedByteBuffer {
        try {
            context.assets.openFd(modelAssetPath).use { fd ->
                java.io.FileInputStream(fd.fileDescriptor).use { input ->
                    return input.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
        } catch (e: FileNotFoundException) {
            throw IllegalStateException("Model asset '$modelAssetPath' not found in app/src/main/assets", e)
        }
    }

    override fun detect(frame: DetectorFrame): List<DefectDetection> {
        val interp = interpreter ?: return emptyList()

        val src = frame.bitmap
        val bmp = if (src.width == inputSize && src.height == inputSize) src else {
            // Reuse one scratch bitmap to avoid per-frame allocation.
            val s = scaled ?: Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888).also { scaled = it }
            android.graphics.Canvas(s).drawBitmap(
                src,
                android.graphics.Rect(0, 0, src.width, src.height),
                android.graphics.Rect(0, 0, inputSize, inputSize),
                null,
            )
            s
        }
        bmp.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        inputBuffer.rewind()
        for (p in pixels) {
            inputBuffer.putFloat(((p shr 16) and 0xFF) / 255f)
            inputBuffer.putFloat(((p shr 8) and 0xFF) / 255f)
            inputBuffer.putFloat((p and 0xFF) / 255f)
        }
        inputBuffer.rewind()
        interp.run(inputBuffer, output)

        val candidates = ArrayList<DefectDetection>()
        for (i in 0 until numPredictions) {
            fun v(ch: Int) = if (channelsFirst) output[0][ch][i] else output[0][i][ch]
            var best = -1
            var bestScore = candidateScoreFloor
            for (c in 0 until numClasses) {
                val s = v(4 + c)
                if (s > bestScore) { bestScore = s; best = c }
            }
            if (best < 0) continue
            val type = DefectType.fromModelClassIndex(best) ?: continue
            val cx = v(0) * boxScale; val cy = v(1) * boxScale
            val w = v(2) * boxScale; val h = v(3) * boxScale
            val box = BoundingBox(
                (cx - w / 2f).coerceIn(0f, 1f), (cy - h / 2f).coerceIn(0f, 1f),
                (cx + w / 2f).coerceIn(0f, 1f), (cy + h / 2f).coerceIn(0f, 1f),
            )
            if (!box.isValid()) continue
            candidates += DefectDetection(type, bestScore, box, frame.timestamp)
        }
        return nonMaxSuppression(candidates)
    }

    private fun nonMaxSuppression(dets: List<DefectDetection>): List<DefectDetection> {
        val kept = ArrayList<DefectDetection>()
        for (d in dets.sortedByDescending { it.confidence }) {
            if (kept.none { it.type == d.type && it.boundingBox.iou(d.boundingBox) > nmsIouThreshold }) kept += d
        }
        return kept
    }

    override fun release() {
        interpreter?.close()
        interpreter = null
        scaled?.recycle()
        scaled = null
    }

    companion object {
        /**
         * Largest box coordinate the model produced on a probe frame -> multiplier to turn raw
         * cx/cy/w/h into 0..1.
         *
         * Normalised exports never exceed 1; pixel-space exports always land above
         * (inputSize - stride) on every anchor, so [PROBE_PIXEL_THRESHOLD] sits between the two
         * by orders of magnitude in both directions.
         */
        fun boxScaleMultiplier(maxRawBoxCoord: Float, inputSize: Int): Float =
            if (maxRawBoxCoord > PROBE_PIXEL_THRESHOLD) 1f / inputSize else 1f

        /** Anything > 1 means "input-pixel space"; a normalised export is bounded by 1. */
        private const val PROBE_PIXEL_THRESHOLD = 1.5f
    }
}
