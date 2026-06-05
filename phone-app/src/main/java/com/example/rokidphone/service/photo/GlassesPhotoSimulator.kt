package com.example.rokidphone.service.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.example.rokidcommon.protocol.photo.PhotoTransferConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.ceil

data class GlassesPhotoCompressionProfile(
    val targetWidth: Int = PhotoTransferConstants.TARGET_IMAGE_WIDTH,
    val targetHeight: Int = PhotoTransferConstants.TARGET_IMAGE_HEIGHT,
    val jpegQuality: Int = PhotoTransferConstants.JPEG_QUALITY,
    val minJpegQuality: Int = 30,
    val maxCompressedSize: Int = PhotoTransferConstants.MAX_COMPRESSED_SIZE,
    val chunkSize: Int = PhotoTransferConstants.CHUNK_SIZE,
    val bluetoothBytesPerMs: Double = 50.0
)

data class SimulatedGlassesPhoto(
    val data: ByteArray,
    val originalBytes: Int,
    val compressedBytes: Int,
    val width: Int,
    val height: Int,
    val chunks: Int,
    val estimatedBluetoothMs: Long,
    val quality: Int
) {
    fun summary(simulateMs: Long): String {
        val originalKb = originalBytes / 1024
        val compressedKb = compressedBytes / 1024
        val btSeconds = estimatedBluetoothMs / 1000.0
        return "Glass sim: ${originalKb}KB -> ${compressedKb}KB, ${width}x${height}, " +
            "JPEG q$quality, ${chunks} chunks, BT est ${String.format(Locale.US, "%.1f", btSeconds)}s, " +
            "sim ${simulateMs}ms"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SimulatedGlassesPhoto
        return originalBytes == other.originalBytes &&
            compressedBytes == other.compressedBytes &&
            width == other.width &&
            height == other.height &&
            chunks == other.chunks &&
            estimatedBluetoothMs == other.estimatedBluetoothMs &&
            quality == other.quality &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + originalBytes
        result = 31 * result + compressedBytes
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + chunks
        result = 31 * result + estimatedBluetoothMs.hashCode()
        result = 31 * result + quality
        return result
    }
}

object GlassesPhotoSimulator {
    val profile = GlassesPhotoCompressionProfile()

    suspend fun simulate(imageData: ByteArray): SimulatedGlassesPhoto = withContext(Dispatchers.Default) {
        if (!hasSupportedImageHeader(imageData)) {
            return@withContext fallback(imageData, quality = 100)
        }

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(imageData, 0, imageData.size, options)

        val originalWidth = options.outWidth
        val originalHeight = options.outHeight
        if (originalWidth <= 0 || originalHeight <= 0) {
            return@withContext fallback(imageData, quality = 100)
        }

        options.inSampleSize = calculateInSampleSize(
            originalWidth,
            originalHeight,
            profile.targetWidth,
            profile.targetHeight
        )
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.ARGB_8888

        val decoded = BitmapFactory.decodeByteArray(imageData, 0, imageData.size, options)
            ?: return@withContext fallback(imageData, quality = 100, width = originalWidth, height = originalHeight)

        val rotated = rotateBitmapIfNeeded(decoded, imageData)
        val scaled = scaleBitmapToFit(rotated, profile.targetWidth, profile.targetHeight)

        var quality = profile.jpegQuality
        var compressed = compressBitmapToJpeg(scaled, quality)
        while (compressed.size > profile.maxCompressedSize && quality > profile.minJpegQuality) {
            quality -= 10
            compressed = compressBitmapToJpeg(scaled, quality)
        }

        val width = scaled.width
        val height = scaled.height
        if (scaled !== rotated) scaled.recycle()
        if (rotated !== decoded) rotated.recycle()
        decoded.recycle()

        SimulatedGlassesPhoto(
            data = compressed,
            originalBytes = imageData.size,
            compressedBytes = compressed.size,
            width = width,
            height = height,
            chunks = estimateChunkCount(compressed.size),
            estimatedBluetoothMs = estimateBluetoothTransferMs(compressed.size),
            quality = quality
        )
    }

    internal fun calculateInSampleSize(
        actualWidth: Int,
        actualHeight: Int,
        targetWidth: Int = profile.targetWidth,
        targetHeight: Int = profile.targetHeight
    ): Int {
        var inSampleSize = 1
        if (actualHeight > targetHeight || actualWidth > targetWidth) {
            val halfHeight = actualHeight / 2
            val halfWidth = actualWidth / 2
            while ((halfHeight / inSampleSize) >= targetHeight &&
                (halfWidth / inSampleSize) >= targetWidth
            ) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    internal fun estimateChunkCount(sizeBytes: Int): Int {
        return ceil(sizeBytes.toDouble() / profile.chunkSize.toDouble()).toInt()
            .coerceAtLeast(1)
    }

    internal fun estimateBluetoothTransferMs(sizeBytes: Int): Long {
        return (sizeBytes / profile.bluetoothBytesPerMs).toLong()
    }

    private fun fallback(
        imageData: ByteArray,
        quality: Int,
        width: Int = 0,
        height: Int = 0
    ): SimulatedGlassesPhoto {
        return SimulatedGlassesPhoto(
            data = imageData,
            originalBytes = imageData.size,
            compressedBytes = imageData.size,
            width = width,
            height = height,
            chunks = estimateChunkCount(imageData.size),
            estimatedBluetoothMs = estimateBluetoothTransferMs(imageData.size),
            quality = quality
        )
    }

    private fun hasSupportedImageHeader(imageData: ByteArray): Boolean {
        val isJpeg = imageData.size >= 2 &&
            imageData[0] == 0xFF.toByte() &&
            imageData[1] == 0xD8.toByte()
        val isPng = imageData.size >= 8 &&
            imageData[0] == 0x89.toByte() &&
            imageData[1] == 0x50.toByte() &&
            imageData[2] == 0x4E.toByte() &&
            imageData[3] == 0x47.toByte() &&
            imageData[4] == 0x0D.toByte() &&
            imageData[5] == 0x0A.toByte() &&
            imageData[6] == 0x1A.toByte() &&
            imageData[7] == 0x0A.toByte()
        val isWebp = imageData.size >= 12 &&
            imageData[0] == 'R'.code.toByte() &&
            imageData[1] == 'I'.code.toByte() &&
            imageData[2] == 'F'.code.toByte() &&
            imageData[3] == 'F'.code.toByte() &&
            imageData[8] == 'W'.code.toByte() &&
            imageData[9] == 'E'.code.toByte() &&
            imageData[10] == 'B'.code.toByte() &&
            imageData[11] == 'P'.code.toByte()
        return isJpeg || isPng || isWebp
    }

    private fun rotateBitmapIfNeeded(bitmap: Bitmap, imageData: ByteArray): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(imageData)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }

        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun scaleBitmapToFit(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val scale = minOf(
            targetWidth.toFloat() / bitmap.width.toFloat(),
            targetHeight.toFloat() / bitmap.height.toFloat()
        )
        if (scale >= 1.0f) return bitmap

        val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun compressBitmapToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
        return output.toByteArray()
    }
}
