package com.roaddefect.ai.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roaddefect.ai.ai.DefectStatus
import com.roaddefect.ai.data.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun Chip(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        fontSize = 11.sp,
        modifier = modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

fun DefectStatus.chipColor(): Color = when (this) {
    DefectStatus.AI_DETECTED -> Color(0xFF546E7A)
    DefectStatus.CONFIRMED -> Color(0xFF2E7D32)
    DefectStatus.REJECTED -> Color(0xFFC62828)
}

@Composable
fun MockBanner(modifier: Modifier = Modifier) {
    Text(
        "MOCK DETECTOR — synthetic detections, not real AI results",
        color = Color.White, fontSize = 12.sp,
        modifier = modifier.background(Color(0xFFB71C1C)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Loads a downsampled JPEG off the main thread. Shows an empty tile if the file is missing. */
@Composable
fun DefectImage(images: ImageStore, relativePath: String?, modifier: Modifier = Modifier, maxWidthPx: Int = 480) {
    val bitmap by produceState<Bitmap?>(initialValue = null, relativePath) {
        value = withContext(Dispatchers.IO) {
            val file = images.resolve(relativePath) ?: return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxWidthPx) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = "Defect photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } ?: Text("No image", modifier = Modifier.align(Alignment.Center), fontSize = 11.sp)
    }
}
