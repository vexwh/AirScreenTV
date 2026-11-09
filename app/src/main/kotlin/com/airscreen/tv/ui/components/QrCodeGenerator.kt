package com.airscreen.tv.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object QrCodeGenerator {
    suspend fun generate(content: String, size: Int = 320): Bitmap = withContext(Dispatchers.Default) {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
        )
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) {
                    0xFF0B0F19.toInt()
                } else {
                    0xFFFFFFFF.toInt()
                }
            }
        }
        createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }
}

@Composable
fun rememberQrBitmap(content: String, size: Int = 320): Bitmap? {
    val bitmap by produceState<Bitmap?>(initialValue = null, content, size) {
        value = runCatching { QrCodeGenerator.generate(content, size) }.getOrNull()
    }
    return bitmap
}