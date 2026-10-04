package com.firestream.chat.data.sticker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import com.firestream.chat.data.util.MediaProcessingLimiter
import com.firestream.chat.domain.model.StickerCrop
import com.firestream.chat.domain.model.StickerDraft
import com.firestream.chat.domain.model.StickerDraftImage
import com.firestream.chat.domain.util.ImageEditGeometry
import com.firestream.chat.domain.util.StickerGeometry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The bitmap work of the sticker maker: a picked photo becomes a draft of up
 * to three pictures, and one of them, placed in a square, becomes the bytes of
 * a sticker.
 *
 * A draft lives in `cacheDir/sticker-maker/`. Preparing a photo deletes the
 * draft before it, so the directory holds one draft. Every decode, the cutout
 * and the encode run under [MediaProcessingLimiter]
 * (docs/PATTERNS.md, "MediaProcessingLimiter owns the concurrency bound").
 *
 * Nothing here touches the library. `StickerRepositoryImpl` stores what
 * [render] returns.
 */
@Singleton
class StickerMaker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val processingLimiter: MediaProcessingLimiter,
    private val subjectCutout: SubjectCutout,
    private val encoder: StickerEncoder,
) {

    private val dir: File
        get() = File(context.cacheDir, DIR_NAME)

    /**
     * Decodes the photo at [sourceUri], with its long edge capped at
     * [SOURCE_MAX_DIMENSION], and cuts its subject out. Throws
     * [IllegalStateException] when the photo cannot be read. A cutout that
     * fails leaves a draft without one.
     */
    suspend fun prepare(sourceUri: String): StickerDraft = processingLimiter.withPermit {
        withContext(Dispatchers.IO) {
            dir.deleteRecursively()
            check(dir.mkdirs()) { "Cannot prepare a sticker" }
            val name = UUID.randomUUID().toString()
            val photo = try {
                decode(Uri.parse(sourceUri))
            } catch (e: IOException) {
                // Not an IOException to the screen: `AppError.from` reads one as a lost connection.
                throw IllegalStateException("That photo could not be read", e)
            }
            val original = write(photo, "$name-original")
            // Each bitmap is a megabyte or four. None is kept past the file it becomes.
            val cutout = subjectCutout.cutOut(photo)
            photo.recycle()
            val subject = cutout?.let(::trimToSubject)
            if (cutout !== subject) cutout?.recycle()
            StickerDraft(
                original = original,
                cutout = subject?.let { write(it, "$name-cutout") },
                outlined = subject?.let { outlined(it) }?.let { outline ->
                    write(outline, "$name-outlined").also { outline.recycle() }
                },
            ).also { subject?.recycle() }
        }
    }

    /**
     * The picture at [imagePath], placed by [crop] on a transparent square of
     * [StickerGeometry.CANVAS] pixels, as a WebP. [imagePath] must name a
     * picture of the current draft. Throws [IllegalStateException], with a
     * message fit to show, when it cannot be read or cannot be encoded under
     * the size limit.
     */
    suspend fun render(imagePath: String, crop: StickerCrop): ByteArray = processingLimiter.withPermit {
        withContext(Dispatchers.IO) {
            val file = File(imagePath).canonicalFile
            // The path comes back from a screen. Only a draft's own file is read.
            require(file.parentFile == dir.canonicalFile) { "Not a sticker draft" }
            val picture = checkNotNull(BitmapFactory.decodeFile(file.path)) { "That picture is no longer available" }
            val size = StickerGeometry.CANVAS
            val sticker = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val placement = StickerGeometry.placement(
                picture.width,
                picture.height,
                StickerGeometry.clamp(crop, picture.width, picture.height),
            )
            Canvas(sticker).drawBitmap(
                picture,
                null,
                RectF(placement.left * size, placement.top * size, placement.right * size, placement.bottom * size),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
            )
            picture.recycle()
            val bytes = encoder.encode(sticker)
            sticker.recycle()
            checkNotNull(bytes) { "That picture has too much detail for a sticker" }
        }
    }

    private fun decode(source: Uri): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, source)) { decoder, info, _ ->
            // Software, because the pixels are read back for the trim and the outline.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val (width, height) = ImageEditGeometry.cappedSize(info.size.width, info.size.height, SOURCE_MAX_DIMENSION)
            if (width > 0 && height > 0) decoder.setTargetSize(width, height)
        }

    private fun write(bitmap: Bitmap, name: String): StickerDraftImage {
        val file = File(dir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return StickerDraftImage(file.absolutePath, bitmap.width, bitmap.height)
    }

    internal companion object {
        const val DIR_NAME = "sticker-maker"

        /** Twice the sticker's side, so a zoom to half the photo still fills the sticker with real pixels. */
        const val SOURCE_MAX_DIMENSION = 1024

        /** A pixel fainter than this is not part of the subject. It keeps the model's haze out of the bounds. */
        private const val SUBJECT_ALPHA = 24

        /** A subject smaller than this on its long edge is noise, not a subject. */
        private const val MIN_SUBJECT = 16

        /** [cutout] cropped to the pixels of its subject, or `null` when it shows none. */
        fun trimToSubject(cutout: Bitmap): Bitmap? {
            val width = cutout.width
            val height = cutout.height
            val pixels = IntArray(width * height)
            cutout.getPixels(pixels, 0, width, 0, 0, width, height)
            var left = width
            var top = height
            var right = -1
            var bottom = -1
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) {
                    if (Color.alpha(pixels[row + x]) > SUBJECT_ALPHA) {
                        if (x < left) left = x
                        if (x > right) right = x
                        if (y < top) top = y
                        if (y > bottom) bottom = y
                    }
                }
            }
            if (right < left || maxOf(right - left, bottom - top) + 1 < MIN_SUBJECT) return null
            return Bitmap.createBitmap(cutout, left, top, right - left + 1, bottom - top + 1)
        }

        /**
         * [subject] with a white outline around its shape. The result is larger
         * than [subject] by the outline's width on every side.
         */
        fun outlined(subject: Bitmap): Bitmap {
            val width = StickerGeometry.outlineWidth(maxOf(subject.width, subject.height))
            val result = Bitmap.createBitmap(subject.width + 2 * width, subject.height + 2 * width, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            val shape = subject.extractAlpha()
            val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            StickerGeometry.outlineShifts(width).forEach { shift ->
                canvas.drawBitmap(shape, width + shift.dx, width + shift.dy, white)
            }
            shape.recycle()
            canvas.drawBitmap(subject, width.toFloat(), width.toFloat(), null)
            return result
        }
    }
}
