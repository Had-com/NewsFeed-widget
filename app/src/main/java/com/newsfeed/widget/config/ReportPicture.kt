package com.newsfeed.widget.config

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.newsfeed.widget.data.PICTURE_ERR_MEMORY
import com.newsfeed.widget.data.PICTURE_ERR_SAVE
import com.newsfeed.widget.data.PICTURE_ERR_TOO_LARGE
import com.newsfeed.widget.data.PICTURE_ERR_UNREADABLE
import com.newsfeed.widget.data.PICTURE_MAX_EDGE
import com.newsfeed.widget.data.PICTURE_THUMB_EDGE
import com.newsfeed.widget.data.PictureEncoding
import com.newsfeed.widget.data.attachmentFileName
import com.newsfeed.widget.data.compressToBudget
import com.newsfeed.widget.data.inSampleSizeFor
import com.newsfeed.widget.data.orientationFix
import com.newsfeed.widget.data.planImageSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneId

/**
 * The one picture attached to a problem report. It lives only in memory ([thumbnail]) and as a file in
 * cacheDir/reports ([path]); it is never copied anywhere until the user taps Share or Send, and Settings deletes
 * the file when it is left (see ReportUiState.discardAttachment).
 */
class ReportAttachment(val path: String, val bytes: Int, val thumbnail: ImageBitmap?)

sealed interface PictureResult {
    data class Ok(val attachment: ReportAttachment) : PictureResult
    data class Error(val message: String) : PictureResult
}

/**
 * Turns whatever the photo picker returned (JPEG, PNG, WEBP, HEIC ...) into a small JPEG without EXIF and saves it as
 * cacheDir/reports/attachment-<yyyyMMdd-HHmmss>.jpg. All decoding happens on [Dispatchers.IO], and never at full
 * size: the bounds are read first, the first decode is sampled so the bitmap is at most twice the target, and the
 * ladder in data/ReportAttachment.kt (quality 80 -> 70 -> 60 -> 50, then smaller) keeps the result under 900 KB
 * (hard limit 1,400,000 bytes). Re-encoding drops the location and camera tags; the EXIF rotation is applied to the
 * pixels first so the picture is not turned. Transparent pixels (PNG) are flattened onto white.
 */
suspend fun prepareReportPicture(context: Context, uri: Uri): PictureResult = withContext(Dispatchers.IO) {
    val app = context.applicationContext
    val resolver = app.contentResolver
    var sampled: Bitmap? = null
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext PictureResult.Error(PICTURE_ERR_UNREADABLE)

        val exifOrientation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
        }.getOrNull() ?: 1

        val options = BitmapFactory.Options().apply {
            inSampleSize = inSampleSizeFor(bounds.outWidth, bounds.outHeight, PICTURE_MAX_EDGE)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var source = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return@withContext PictureResult.Error(PICTURE_ERR_UNREADABLE)
        sampled = source

        val fix = orientationFix(exifOrientation)
        if (fix.degrees != 0 || fix.mirror) {
            val matrix = Matrix().apply {
                if (fix.degrees != 0) postRotate(fix.degrees.toFloat())
                if (fix.mirror) postScale(-1f, 1f)
            }
            val turned = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            if (turned !== source) source.recycle()
            source = turned
            sampled = source
        }
        ensureActive()

        val flat = source
        val encoding = compressToBudget(flat.width, flat.height) { w, h, quality -> encodeJpeg(flat, w, h, quality) }
        when (encoding) {
            PictureEncoding.TooLarge -> PictureResult.Error(PICTURE_ERR_TOO_LARGE)
            PictureEncoding.Failed -> PictureResult.Error(PICTURE_ERR_SAVE)
            is PictureEncoding.Ok -> {
                ensureActive()
                val dir = File(app.cacheDir, "reports").also { it.mkdirs() }
                val file = File(dir, attachmentFileName(System.currentTimeMillis(), ZoneId.systemDefault()))
                try {
                    file.writeBytes(encoding.bytes)
                } catch (e: java.io.IOException) {
                    runCatching { file.delete() }
                    return@withContext PictureResult.Error(PICTURE_ERR_SAVE)
                }
                PictureResult.Ok(ReportAttachment(file.path, encoding.bytes.size, decodeThumbnail(encoding.bytes, encoding.width, encoding.height)))
            }
        }
    } catch (e: OutOfMemoryError) {
        PictureResult.Error(PICTURE_ERR_MEMORY)
    } catch (e: SecurityException) {
        PictureResult.Error(PICTURE_ERR_UNREADABLE)
    } catch (e: java.io.IOException) {
        PictureResult.Error(PICTURE_ERR_UNREADABLE)
    } catch (e: IllegalArgumentException) {
        PictureResult.Error(PICTURE_ERR_UNREADABLE)
    } finally {
        sampled?.let { if (!it.isRecycled) it.recycle() }
    }
}

/** Scales [source] to [width] x [height] on a white background and compresses it as JPEG; the temporary bitmap is recycled. Null when the encoder fails. */
private fun encodeJpeg(source: Bitmap, width: Int, height: Int, quality: Int): ByteArray? {
    val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(target)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, Rect(0, 0, source.width, source.height), Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        val out = ByteArrayOutputStream(256 * 1024)
        return if (target.compress(Bitmap.CompressFormat.JPEG, quality, out)) out.toByteArray() else null
    } finally {
        target.recycle()
    }
}

/** A thumbnail of at most [PICTURE_THUMB_EDGE] px from the finished JPEG bytes, decoded with sampling. Null when it cannot be made (the picture is still attached). */
private fun decodeThumbnail(jpeg: ByteArray, width: Int, height: Int): ImageBitmap? = try {
    val options = BitmapFactory.Options().apply { inSampleSize = inSampleSizeFor(width, height, PICTURE_THUMB_EDGE) }
    val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
    if (decoded == null) null else {
        val size = planImageSize(decoded.width, decoded.height, PICTURE_THUMB_EDGE)
        if (size.width == decoded.width && size.height == decoded.height) decoded.asImageBitmap()
        else {
            val scaled = Bitmap.createScaledBitmap(decoded, size.width, size.height, true)
            if (scaled !== decoded) decoded.recycle()
            scaled.asImageBitmap()
        }
    }
} catch (e: OutOfMemoryError) {
    null
} catch (e: RuntimeException) {
    null
}
