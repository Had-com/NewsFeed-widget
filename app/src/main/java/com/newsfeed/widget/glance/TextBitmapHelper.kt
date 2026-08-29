package com.newsfeed.widget.glance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.MetricAffectingSpan
import androidx.core.content.res.ResourcesCompat
import com.newsfeed.widget.R

/**
 * Renders a text string to a Bitmap using a custom Typeface loaded from res/font.
 * Used by Glamour theme headlines in Glance widgets, where RemoteViews only supports
 * system font families and cannot reference R.font resources directly.
 *
 * Font: Dana Yad (דנה יד), from AlefAlefAlef (alefalefalef.co.il) — free-tier commercial
 * license, not OFL/GPL. Known restrictions: capped at 5,000 downloads before a paid license
 * is required, requires the registered account used to obtain it, and is personal/
 * non-transferable (can't be handed to collaborators or reused in other builds without
 * their own license). Adopted with explicit user sign-off despite these constraints.
 */
object TextBitmapHelper {

    // null = not yet loaded or load failed; never cache a fallback so next call retries
    @Volatile private var cachedTypeface: Typeface? = null

    // Simple LRU cache — keyed on content + render parameters so bitmaps survive reuse.
    private val cache = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) = size > 40
    }

    private fun getTypeface(context: Context): Typeface {
        cachedTypeface?.let { return it }
        return synchronized(this) {
            cachedTypeface ?: run {
                val ctx = context.applicationContext
                // Typeface.createFromAssets() is the most reliable method in background/widget
                // contexts — Resources.getFont() can silently fail on Glance's coroutine thread.
                val tf: Typeface? = try {
                    ctx.assets.open("fonts/dana_yad.otf").use { stream ->
                        val file = java.io.File(ctx.cacheDir, "dana_yad.otf")
                        file.outputStream().use { stream.copyTo(it) }
                        Typeface.createFromFile(file)
                    }
                } catch (_: Exception) {
                    try { ResourcesCompat.getFont(ctx, R.font.dana_yad) }
                    catch (_: Exception) { null }
                }
                if (tf != null) cachedTypeface = tf
                tf ?: Typeface.DEFAULT_BOLD
            }
        }
    }

    private class RunTypefaceSpan(private val tf: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) { tp.typeface = tf }
        override fun updateMeasureState(tp: TextPaint) { tp.typeface = tf }
    }

    // Dana Yad (like every Hebrew handwriting font tried for Glamour) has zero Latin glyphs —
    // verified directly against its cmap table (0/52 A-Za-z, 27/27 Hebrew). Any English
    // embedded in a headline/description therefore silently fell back to whatever plain
    // system font Android substitutes, breaking the "handwriting" look for exactly the
    // characters most likely to appear mid-sentence (site names, abbreviations). Spans the
    // Latin runs onto Android's built-in generic "cursive" family instead — no new font
    // asset needed, and it's guaranteed present on every device (unlike shipping/licensing
    // a second custom Latin script font).
    private fun withLatinCursiveFallback(text: String, bold: Boolean): CharSequence {
        var hasLatin = false
        for (c in text) if (c in 'A'..'Z' || c in 'a'..'z') { hasLatin = true; break }
        if (!hasLatin) return text

        val latinTf = Typeface.create("cursive", if (bold) Typeface.BOLD else Typeface.NORMAL)
        val spannable = SpannableString(text)
        var runStart = -1
        for (i in text.indices) {
            val isLatin = text[i] in 'A'..'Z' || text[i] in 'a'..'z'
            if (isLatin && runStart == -1) runStart = i
            if (!isLatin && runStart != -1) {
                spannable.setSpan(RunTypefaceSpan(latinTf), runStart, i, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                runStart = -1
            }
        }
        if (runStart != -1) {
            spannable.setSpan(RunTypefaceSpan(latinTf), runStart, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return spannable
    }

    /**
     * Renders [text] with the Glamour handwriting font into a Bitmap.
     * Returns null if font loading and bitmap creation both fail — callers must fall back
     * to a Text composable in that case.
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
    ): Bitmap? = render(context, text, textSizePx, colorArgb, widthPx, isRtl, bold = true, maxLines = 3)

    /**
     * Renders a short, already char-clipped body-text snippet with the same handwriting font
     * as [headline], but in the font's own regular weight — the headline is bold to stand
     * out, body text isn't. NOT for unbounded text (e.g. a fully-fetched article): a Bitmap's
     * memory cost scales with width × height × 4 bytes, so an unbounded input on a large,
     * high-density widget could re-risk the RemoteViews bitmap-memory budget this project
     * already hit once (see the thumbnail-resolution fix) — callers must clip [text] to a
     * small char count first, and [maxLines] should stay in the same modest range as the
     * default. Long input just ellipsizes instead of growing the bitmap further.
     */
    fun paragraph(
        context: Context,
        text: String,
        textSizePx: Float,
        colorArgb: Int,
        widthPx: Int,
        isRtl: Boolean,
        maxLines: Int = 10,
    ): Bitmap? = render(context, text, textSizePx, colorArgb, widthPx, isRtl, bold = false, maxLines = maxLines)

    private fun render(
        context: Context,
        text: String,
        textSizePx: Float,
        colorArgb: Int,
        widthPx: Int,
        isRtl: Boolean,
        bold: Boolean,
        maxLines: Int,
    ): Bitmap? {
        if (text.isBlank() || textSizePx <= 0f) return null
        val safeWidth = widthPx.coerceAtLeast(50)
        val key = "$text|$textSizePx|$colorArgb|$safeWidth|$isRtl|$bold|$maxLines"

        synchronized(cache) { cache[key] }?.let { return it }

        return try {
            val tf = getTypeface(context)
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface    = tf
                textSize    = textSizePx
                color       = colorArgb
                isAntiAlias = true
                // Dana Yad ships as a single (regular) weight — synthesize bold via the
                // paint's fake-bold stroke. Verified this doesn't disturb StaticLayout's
                // ALIGN_OPPOSITE math on the last line of a wrapped RTL headline (Solitreo,
                // a different handwriting font tried here, broke that specifically — its last
                // line rendered flush-left instead of right — even with fake-bold off, so the
                // bug was in that font's own metrics/shaping, not this bold technique).
                isFakeBoldText = bold
            }

            // Force the paragraph direction explicitly instead of relying on StaticLayout's
            // default FIRSTSTRONG_LTR guess. That default already detects Hebrew text as an
            // RTL paragraph on its own, which made ALIGN_NORMAL mean "right" and ALIGN_OPPOSITE
            // mean "left" — so the old `if (isRtl) ALIGN_OPPOSITE` was backwards and left-aligned
            // every RTL headline. ALIGN_NORMAL always means "start of paragraph direction," so
            // pairing it with an explicit direction keeps the two in sync unambiguously.
            val textDirection = if (isRtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR
            val renderText = withLatinCursiveFallback(text, bold)
            // Verified via logcat (safeWidth vs. StaticLayout.getLineWidth(i) per line across
            // ~15 real headlines) that safeWidth itself is already correct — many lines
            // independently reached 96-100% of it. The lines that fell short did so purely
            // from natural word-boundary wrapping (Hebrew news headlines have organically
            // uneven word lengths), which ragged (non-justified) text always produces to some
            // degree. Inter-word justification stretches spacing so every line but the true
            // last one flushes both edges instead of leaving that natural slack.
            val layout = StaticLayout.Builder
                .obtain(renderText, 0, renderText.length, paint, safeWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(textDirection)
                .setLineSpacing(0f, 1.2f)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
                .build()

            val bmp = Bitmap.createBitmap(safeWidth, layout.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            layout.draw(Canvas(bmp))
            synchronized(cache) { cache[key] = bmp }
            bmp
        } catch (_: Exception) {
            null
        }
    }
}
