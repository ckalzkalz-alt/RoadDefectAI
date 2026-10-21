package com.roaddefect.ai.ai

import android.content.Context

/**
 * Chooses the detector. A trained model is used when [MODEL_ASSET] exists in app assets;
 * otherwise the clearly labelled mock detector is returned. Replace the model by dropping a new
 * .tflite in assets and bumping [MODEL_VERSION], no other code changes.
 */
object DetectorFactory {
    const val MODEL_ASSET = "road_defect_v1.tflite"
    const val MODEL_VERSION = "RoadDefectAI-v1.1"

    fun hasTrainedModel(context: Context): Boolean =
        runCatching { context.assets.list("")?.contains(MODEL_ASSET) == true }.getOrDefault(false)

    /** True when [create] would return the mock detector. */
    fun willUseMock(context: Context, forceMock: Boolean): Boolean = forceMock || !hasTrainedModel(context)

    fun create(context: Context, forceMock: Boolean = false): RoadDefectDetector =
        if (willUseMock(context, forceMock)) MockRoadDefectDetector()
        else TfliteRoadDefectDetector(context, MODEL_ASSET, MODEL_VERSION)
}
