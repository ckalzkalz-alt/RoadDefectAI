package com.roaddefect.ai.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Format {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val dateFmt = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH)
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ENGLISH)

    fun inspectionId(id: Long) = "INS-%06d".format(id)
    fun defectId(id: Long) = "RD-%06d".format(id)

    fun date(ms: Long): String = dateFmt.format(Instant.ofEpochMilli(ms).atZone(zone))
    fun time(ms: Long): String = timeFmt.format(Instant.ofEpochMilli(ms).atZone(zone))

    fun km(meters: Double): String = "%.1f km".format(Locale.ENGLISH, meters / 1000.0)

    fun kmh(mps: Float?): String =
        if (mps == null || !mps.isFinite()) "—" else "%d km/h".format(Locale.ENGLISH, Math.round(mps * 3.6f))

    fun percent(confidence: Float): String = "${Math.round(confidence * 100)}%"

    fun durationWords(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        return when {
            minutes == 0L -> if (totalSeconds <= 1) "1 second" else "$totalSeconds seconds"
            minutes == 1L -> "1 minute"
            else -> "$minutes minutes"
        }
    }

    fun clock(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d".format(Locale.ENGLISH, s / 3600, (s % 3600) / 60, s % 60)
    }

    fun coords(lat: Double?, lon: Double?): String =
        if (lat == null || lon == null) "No GPS fix" else "%.6f, %.6f".format(Locale.ENGLISH, lat, lon)
}
