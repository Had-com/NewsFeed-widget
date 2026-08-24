package com.newsfeed.widget.glance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import com.newsfeed.widget.R

/**
 * Renders a text string to a Bitmap using a custom Typeface loaded from res/font.
 * Used by Glamour theme headlines in Glance widgets, where RemoteViews only supports
 * system font families and cannot reference R.font resources directly.
 */
object TextBitmapHelper {

    @Volatile private var cachedTypeface: Typeface? = null

    // Simple LRU cache — keyed on content + render parameters so bitmaps survive reuse.
    private val cache = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) = size > 40
    }

    private fun getTypeface(context: Context): Typeface =
        cachedTypeface ?: synchronized(this) {
            cachedTypeface ?: run {
                val tf = ResourcesCompat.getFont(context.applicationContext, R.font.miriam_libre_bold)
                    ?: Typeface.DEFAULT_BOLD
                cachedTypeface = tf
                tf
            }
        }

    /**
     * Renders [text] with the Glamour handwriting font into a Bitmap.
     *
     * @param textSizePx  Font size in screen pixels (sp value × scaledDensity).
     * @param colorArgb   Text color as Android ARGB int.
     * @param widthPx     Target bitmap width in pixels (matches the available column width).
     * @param isRtl       Whether the text should be right-aligned (RTL feeds).
     */
    fun headline(
        context: Context,
        text: String,
        textSizePx: Float,
        colorArgb: Int,
        widthPx: Int,
        isRtl: Boolean,
    ): Bitmap {
        val safeWidth = widthPx.coerceAtLeast(50)
        val key = "$text|$textSizePx|$colorArgb|$safeWidth|$isRtl"

        synchronized(cache) { cache[key] }?.let { return it }

        val tf = getTypeface(context)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface  = tf
            textSize  = textSizePx
            color     = colorArgb
            isAntiAlias = true
        }

        val alignment = if (isRtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, safeWidth)
            .setAlignment(alignment)
            .setLineSpacing(0f, 1.2f)
            .setMaxLines(3)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

        val bmp = Bitmap.createBitmap(safeWidth, layout.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        layout.draw(Canvas(bmp))

        synchronized(cache) { cache[key] = bmp }
        return bmp
    }
}
