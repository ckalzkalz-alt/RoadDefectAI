package com.roaddefect.ai.ai

/**
 * The four MVP defect classes. The order of [modelClassIndex] MUST match the class order the
 * trained model was exported with (see docs/TRAINING.md in the README).
 *
 * Future classes (rutting, edge breaks, ...) are added here and in the model; nothing else in the
 * app switches exhaustively on this enum except UI label/colour mapping.
 */
enum class DefectType(
    val modelClassIndex: Int,
    val datasetLabel: String,
    val displayName: String,
    val pluralName: String,
    val code: String,
) {
    POTHOLE(0, "pothole", "Pothole", "Potholes", "PH"),
    LONGITUDINAL_CRACK(1, "longitudinal_crack", "Longitudinal Crack", "Longitudinal Cracks", "LC"),
    TRANSVERSE_CRACK(2, "transverse_crack", "Transverse Crack", "Transverse Cracks", "TC"),
    ALLIGATOR_CRACK(3, "alligator_crack", "Alligator Crack", "Alligator Cracks", "AC");

    companion object {
        fun fromModelClassIndex(index: Int): DefectType? = entries.firstOrNull { it.modelClassIndex == index }
        fun fromName(name: String): DefectType? = entries.firstOrNull { it.name == name }
    }
}

enum class DefectStatus(val displayName: String) {
    AI_DETECTED("AI Detected"),
    CONFIRMED("Confirmed"),
    REJECTED("Rejected");

    companion object {
        fun fromName(name: String): DefectStatus = entries.firstOrNull { it.name == name } ?: AI_DETECTED
    }
}
