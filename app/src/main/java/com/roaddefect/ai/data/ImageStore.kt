package com.roaddefect.ai.data

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/**
 * Stores one JPEG per defect under files/inspections/{inspectionId}/. Images are downscaled and
 * compressed so long inspections stay small. Writes go to a temp file then rename, so a crash
 * never leaves a half-written image referenced from the database.
 */
class ImageStore(private val context: Context) {

    /** Returns the path relative to filesDir, or null if saving failed. */
    fun save(bitmap: Bitmap, inspectionId: Long, name: String, maxSidePx: Int, jpegQuality: Int): String? = runCatching {
        val dir = File(context.filesDir, "inspections/$inspectionId").apply { mkdirs() }
        val scale = maxSidePx.toFloat() / maxOf(bitmap.width, bitmap.height)
        val toWrite = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap

        val tmp = File(dir, "$name.jpg.tmp")
        val target = File(dir, "$name.jpg")
        try {
            FileOutputStream(tmp).use { toWrite.compress(Bitmap.CompressFormat.JPEG, jpegQuality, it) }
        } finally {
            if (toWrite !== bitmap) toWrite.recycle()
        }
        if (!tmp.renameTo(target)) { tmp.delete(); return@runCatching null }
        "inspections/$inspectionId/$name.jpg"
    }.getOrNull()

    fun resolve(relativePath: String?): File? = relativePath?.let { File(context.filesDir, it) }?.takeIf { it.exists() }

    fun deleteInspectionImages(inspectionId: Long) {
        File(context.filesDir, "inspections/$inspectionId").deleteRecursively()
    }
}
