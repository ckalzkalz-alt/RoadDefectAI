package com.roaddefect.ai.tracking

import com.roaddefect.ai.ai.BoundingBox
import com.roaddefect.ai.ai.DefectDetection
import com.roaddefect.ai.ai.DefectType
import com.roaddefect.ai.config.DetectionConfig
import com.roaddefect.ai.location.Geo
import com.roaddefect.ai.location.LocationFix
import kotlin.math.abs

/**
 * Turns noisy per-frame detections into at most one record per physical defect.
 *
 * Pipeline per analysed frame:
 *  1. Drop detections below the confidence threshold or malformed.
 *  2. Associate each remaining detection with an open track of the same type (IoU or nearby centre,
 *     because the box drifts as the vehicle moves). Unmatched detections open new tracks.
 *  3. A track becomes a record when it persisted >= minPersistFrames OR hit the strong single-frame
 *     confidence, AND it is not a repeat of a recently recorded defect (same type, within the
 *     cooldown AND within the minimum distance).
 *  4. Events always carry the detection from the frame being processed, so the saved image and the
 *     stored confidence always belong together. After a record exists, a clearly better frame emits
 *     [Event.Improved] so the stored image becomes the best available (bounded per track).
 *  5. Tracks unseen for trackTimeoutMs are closed.
 *
 * Pure Kotlin, single-threaded use (the inference thread). No Android dependencies.
 */
class DefectTracker(private val config: () -> DetectionConfig) {

    sealed interface Event {
        val trackId: Long
        val detection: DefectDetection
        /** A new defect should be recorded. */
        data class Confirmed(override val trackId: Long, override val detection: DefectDetection) : Event
        /** A better frame arrived for an already recorded defect. */
        data class Improved(override val trackId: Long, override val detection: DefectDetection) : Event
    }

    private class Track(
        val id: Long,
        val type: DefectType,
        var box: BoundingBox,
        var best: DefectDetection,
        val firstSeenMs: Long,
    ) {
        var lastSeenMs: Long = firstSeenMs
        var hits = 1
        var recorded = false
        var suppressed = false
        var improvements = 0
        var recordedConf = 0f
    }

    private data class RecentRecord(val type: DefectType, val timeMs: Long, val fix: LocationFix?)

    private val tracks = ArrayList<Track>()
    private val recent = ArrayDeque<RecentRecord>()
    private var nextTrackId = 1L

    /**
     * Drops all open tracks. [preserveCooldown] keeps the recent-record history so a defect recorded
     * just before a pause cannot be recorded again immediately after resuming (the cooldown window
     * still expires naturally, so old history never suppresses a genuinely new defect).
     */
    fun reset(preserveCooldown: Boolean = false) {
        tracks.clear()
        if (!preserveCooldown) recent.clear()
    }

    fun update(detections: List<DefectDetection>, nowMs: Long, fix: LocationFix?): List<Event> {
        val cfg = config()
        val events = ArrayList<Event>()

        val usable = detections
            .filter { it.isWellFormed() && it.confidence >= cfg.minConfidence }
            .sortedByDescending { it.confidence }

        // Expire first: a track unseen for longer than the timeout must not absorb a new sighting.
        tracks.removeAll { nowMs - it.lastSeenMs > cfg.trackTimeoutMs }

        val matchedThisFrame = HashSet<Long>()
        for (det in usable) {
            val track = tracks
                .filter { it.type == det.type && it.id !in matchedThisFrame }
                .map { it to affinity(it.box, det.boundingBox, cfg) }
                .filter { it.second > 0f }
                .maxByOrNull { it.second }?.first

            if (track == null) {
                val t = Track(nextTrackId++, det.type, det.boundingBox, det, nowMs)
                tracks += t
                matchedThisFrame += t.id
                maybeConfirm(t, det, nowMs, fix, cfg, events)
            } else {
                matchedThisFrame += track.id
                track.hits++
                track.lastSeenMs = nowMs
                track.box = det.boundingBox
                if (det.confidence > track.best.confidence) track.best = det
                if (!track.recorded && !track.suppressed) {
                    maybeConfirm(track, det, nowMs, fix, cfg, events)
                } else if (track.recorded &&
                    det.confidence > track.recordedConf + MIN_IMPROVEMENT &&
                    track.improvements < MAX_IMPROVEMENTS
                ) {
                    track.improvements++
                    track.recordedConf = det.confidence
                    events += Event.Improved(track.id, det)
                }
            }
        }

        return events
    }

    private fun maybeConfirm(t: Track, cur: DefectDetection, nowMs: Long, fix: LocationFix?, cfg: DetectionConfig, out: MutableList<Event>) {
        val persisted = t.hits >= cfg.minPersistFrames
        val strong = t.best.confidence >= cfg.strongSingleFrameConfidence
        if (!persisted && !strong) return

        if (isRepeat(t.type, nowMs, fix, cfg)) {
            t.suppressed = true
            return
        }
        t.recorded = true
        recent.addLast(RecentRecord(t.type, nowMs, fix))
        while (recent.size > RECENT_LIMIT) recent.removeFirst()
        t.recordedConf = cur.confidence
        out += Event.Confirmed(t.id, cur)
    }

    /**
     * Repeat = same type, inside the cooldown window, and (when both positions are known) closer than
     * the minimum distance. Without a position on either side, only the time cooldown applies.
     */
    private fun isRepeat(type: DefectType, nowMs: Long, fix: LocationFix?, cfg: DetectionConfig): Boolean =
        recent.any { r ->
            if (r.type != type || nowMs - r.timeMs > cfg.cooldownMs) return@any false
            val a = r.fix
            if (a == null || fix == null) return@any true
            Geo.distanceM(a.latitude, a.longitude, fix.latitude, fix.longitude) < cfg.minDistanceBetweenSameTypeM
        }

    /** >0 when two boxes plausibly show the same defect in consecutive frames. */
    private fun affinity(prev: BoundingBox, cur: BoundingBox, cfg: DetectionConfig): Float {
        val iou = prev.iou(cur)
        if (iou >= cfg.trackMatchIou) return iou + 1f
        // Moving camera: allow a small centre shift relative to box size.
        val dx = abs(prev.centerX - cur.centerX)
        val dy = abs(prev.centerY - cur.centerY)
        val reach = maxOf(prev.width, cur.width, prev.height, cur.height) * 1.2f
        return if (dx < reach && dy < reach) (1f - maxOf(dx, dy) / reach) * 0.5f + 0.01f else 0f
    }

    companion object {
        private const val MIN_IMPROVEMENT = 0.02f
        private const val MAX_IMPROVEMENTS = 3
        private const val RECENT_LIMIT = 50
    }
}
