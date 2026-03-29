package com.firestream.chat.data.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class ImageResult(
    val file: File,
    val width: Int,
    val height: Int,
    val mimeType: String
)

@Singleton
class ImageCompressor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val MAX_DIMENSION = 1600
        private const val JPEG_QUALITY = 80
    }

    suspend fun processImage(uri: Uri, fullQuality: Boolean): ImageResult = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open URI: $uri")

        // Step 1: Read dimensions without loading full bitmap
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        inputStream.use { BitmapFactory.decodeStream(it, null, options) }
        val originalWidth = options.outWidth
        val originalHeight = options.outHeight

        // Step 2: Read EXIF orientation
        val exifRotation = readExifRotation(uri)

        // Step 3: Calculate dimensions after rotation
        val isRotated = exifRotation == 90 || exifRotation == 270
        val sourceWidth = if (isRotated) originalHeight else originalWidth
        val sourceHeight = if (isRotated) originalWidth else originalHeight

        if (fullQuality) {
            // Full quality: decode, apply rotation if needed, save as-is quality
            val sampleSize = calculateInSampleSize(originalWidth, originalHeight, originalWidth, originalHeight)
            val bitmap = decodeBitmap(uri, sampleSize)
            val rotated = applyRotation(bitmap, exifRotation)
            val outputFile = createTempFile()
            outputFile.outputStream().use { out ->
                rotated.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
            if (rotated !== bitmap) bitmap.recycle()
            rotated.recycle()
            ImageResult(outputFile, sourceWidth, sourceHeight, "image/jpeg")
        } else {
            // Compressed: resize to MAX_DIMENSION on longest edge, JPEG_QUALITY
            val longestEdge = maxOf(sourceWidth, sourceHeight)
            val scale = if (longestEdge > MAX_DIMENSION) MAX_DIMENSION.toFloat() / longestEdge else 1f
            val targetWidth = (sourceWidth * scale).toInt().coerceAtLeast(1)
            val targetHeight = (sourceHeight * scale).toInt().coerceAtLeast(1)

            // Use inSampleSize for memory-safe initial decode
            val sampleSize = calculateInSampleSize(
                originalWidth, originalHeight,
                if (isRotated) targetHeight else targetWidth,
                if (isRotated) targetWidth else targetHeight
            )
            val sampled = decodeBitmap(uri, sampleSize)
            val rotated = applyRotation(sampled, exifRotation)

            // Fine-scale to exact target dimensions
            val scaled = if (rotated.width != targetWidth || rotated.height != targetHeight) {
                Bitmap.createScaledBitmap(rotated, targetWidth, targetHeight, true).also {
                    if (it !== rotated) rotated.recycle()
                }
            } else {
                rotated
            }
            if (sampled !== rotated && sampled !== scaled) sampled.recycle()

            val outputFile = createTempFile()
            outputFile.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            scaled.recycle()
            ImageResult(outputFile, targetWidth, targetHeight, "image/jpeg")
        }
    }

    private fun readExifRotation(uri: Uri): Int {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return 0
            val exif = ExifInterface(input)
            input.close()
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun applyRotation(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun decodeBitmap(uri: Uri, sampleSize: Int): Bitmap {
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open URI: $uri")
        return input.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalArgumentException("Cannot decode image: $uri")
    }

    private fun calculateInSampleSize(srcWidth: Int, srcHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        var sampleSize = 1
        if (srcHeight > reqHeight || srcWidth > reqWidth) {
            val halfHeight = srcHeight / 2
            val halfWidth = srcWidth / 2
            while (halfHeight / sampleSize >= reqHeight && halfWidth / sampleSize >= reqWidth) {
                sampleSize *= 2
            }
        }
        return sampleSize
    }

    private fun createTempFile(): File {
        val dir = File(context.cacheDir, "compressed").also { it.mkdirs() }
        return File.createTempFile("img_", ".jpg", dir)
    }
}
