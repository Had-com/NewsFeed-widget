package com.newsfeed.widget.glance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import com.newsfeed.widget.R

/**
 * Renders a text string to a Bitmap using a custom Typeface loaded from res/font.
 * Used by Glamour theme headlines in Glance widgets, where RemoteViews only supports
 * system font families and cannot reference R.font resources directly.
 *
 * Font: Playpen Sans Hebrew (TypeTogether, via Google Fonts) — OFL licensed, no usage
 * restrictions. Unlike every earlier candidate tried for Glamour (Dana Yad, Refoyl,
 * Solitreo — all Hebrew-only, 0/52 Latin glyphs, verified via fontTools cmap inspection),
 * this family has full native coverage of both Hebrew (27/27) and Latin (52/52) in the
 * same face, in both weights used here — verified the same way before adopting it. That
 * removes the whole Latin-cursive-fallback mechanism this file used to need (mixed-script
 * text — site names, abbreviations — no longer needs a second, differently-styled fallback
 * typeface spliced in), and headline bold is the font's own real bold weight rather than a
 * synthesized fake-bold stroke.
 */
object TextBitmapHelper {

    // null = not yet loaded or load failed; never cache a fallback so next call retries
    @Volatile private var cachedRegular: Typeface? = null
    @Volatile private var cachedBold: Typeface? = null

    // Simple LRU cache — keyed on content + render parameters so bitmaps survive reuse.
    private val cache = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) = size > 40
    }

    private fun getTypeface(context: Context, bold: Boolean): Typeface {
        val cached = if (bold) cachedBold else cachedRegular
        cached?.let { return it }
        return synchronized(this) {
            val already = if (bold) cachedBold else cachedRegular
            already ?: run {
                val ctx = context.applicationContext
                val assetName = if (bold) "playpen_sans_hebrew_bold.ttf" else "playpen_sans_hebrew.ttf"
                val fontRes = if (bold) R.font.playpen_sans_hebrew_bold else R.font.playpen_sans_hebrew
                // Typeface.createFromAssets() is the most reliable method in background/widget
                // contexts — Resources.getFont() can silently fail on Glance's coroutine thread.
                val tf: Typeface? = try {
                    ctx.assets.open("fonts/$assetName").use { stream ->
                        val file = java.io.File(ctx.cacheDir, assetName)
                        file.outputStream().use { stream.copyTo(it) }
                        Typeface.createFromFile(file)
                    }
                } catch (_: Exception) {
                    try { ResourcesCompat.getFont(ctx, fontRes) }
                    catch (_: Exception) { null }
                }
                if (tf != null) { if (bold) cachedBold = tf else cachedRegular = tf }
                tf ?: (if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
            }
        }
    }

    /**
     * Renders [text] with the Glamour handwriting font into a Bitmap.
     * Returns null if font loading and bitmap creation both fail — callers must fall back
     * to a Text composable in that case.
     *
     * @param textSizePx  Font size in screen pixels (sp value × scaledDensity).
     * @param widthPx     Target bitmap width in pixels (matches the available column width).
     * @param isRtl       Whether the text should be right-aligned (RTL feeds).
     *
     * Renders as an ALPHA_8 (colorless) bitmap — callers apply the real color via
     * Glance's `ColorFilter.tint(ColorProvider)` on the `Image()` that displays it, not by
     * passing a color into this function. See the ALPHA_8 comment inside [render] for why.
     */
    // maxLines default (3) matches every existing caller's real usage unchanged. Focus Mode's
    // enlarged row passes a higher value explicitly (see FeedItemRow.kt) — hard-capping every
    // headline to 3 lines regardless of how much bigger the text renders defeats the point of
    // enlarging it for readability: at a high focusScale, 3 lines of much bigger text fits far
    // fewer words than 3 lines normally would, so a long real headline gets ellipsized down to
    // just its first few words instead of showing more.
    fun headline(
        context: Context,
        text: String,
        textSizePx: Float,
        widthPx: Int,
        isRtl: Boolean,
        maxLines: Int = 3,
    ): Bitmap? = render(context, text, textSizePx, widthPx, isRtl, bold = true, maxLines = maxLines)

    /**
     * Renders a short, already char-clipped body-text snippet with the same handwriting font
     * as [headline], but in the font's own regular weight — the headline is bold to stand
     * out, body text isn't. NOT for unbounded text (e.g. a fully-fetched article): a Bitmap's
     * memory cost scales with width × height × 1 byte (ALPHA_8 — see [render]), so an unbounded
     * input on a large, high-density widget could still re-risk the RemoteViews bitmap-memory
     * budget this project already hit once (see the thumbnail-resolution fix), just at a higher
     * text-length threshold than before ALPHA_8 — callers must clip [text] to a small char count
     * first, and [maxLines] should stay in the same modest range as the default. Long input just
     * ellipsizes instead of growing the bitmap further.
     */
    fun paragraph(
        context: Context,
        text: String,
        textSizePx: Float,
        widthPx: Int,
        isRtl: Boolean,
        maxLines: Int = 10,
    ): Bitmap? = render(context, text, textSizePx, widthPx, isRtl, bold = false, maxLines = maxLines)

    private fun render(
        context: Context,
        text: String,
        textSizePx: Float,
        widthPx: Int,
        isRtl: Boolean,
        bold: Boolean,
        maxLines: Int,
    ): Bitmap? {
        if (text.isBlank() || textSizePx <= 0f) return null
        val safeWidth = widthPx.coerceAtLeast(50)
        // Backstop independent of any caller's own size math (NewsFeedWidget.kt's row-memory
        // budget included) — confirmed on-device that a caller-computed textSizePx can still
        // reach a real, oversized value (Focus Mode's fontSize × focusScale compounding with
        // Font size's own up-to-3.0 range) even after that budget accounts for the current
        // maxLines correctly, because the budget is a row-COUNT throttle, not a per-bitmap size
        // ceiling: it can only ever reduce maxRowsAllowed to 1, and 1 row whose own bitmap
        // already exceeds the whole budget is still 1 unsafe bitmap. 90px keeps even the
        // 8-line headline case (see AdjustFocusScaleCallback.HEADLINE_MAX_LINES)
        // to a few MB at most, regardless of what any upstream calculation concluded.
        val safeTextSizePx = textSizePx.coerceAtMost(90f)
        // No colorArgb in the key (or the bitmap at all) any more — see the ALPHA_8 comment
        // below. One real, meaningful side effect: the same headline no longer needs a separate
        // cached bitmap per theme color/light-dark variant/per-feed accent, so switching theme
        // or scrolling past differently-accented feeds hits this cache far more often than
        // before, not just avoiding re-render cost but leaving more of the 40-entry LRU actually
        // available for genuinely different text.
        val key = "$text|$safeTextSizePx|$safeWidth|$isRtl|$bold|$maxLines"

        synchronized(cache) { cache[key] }?.let { return it }

        return try {
            val tf = getTypeface(context, bold)
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface    = tf
                textSize    = safeTextSizePx
                // Color is irrelevant here — see the ALPHA_8 Bitmap.Config below, which only
                // ever captures this paint's coverage (anti-aliased glyph shape), never its RGB.
                // Any fully-opaque color would do; solid black keeps the alpha math trivially
                // 1:1 with what gets drawn, with nothing to reason about.
                color       = android.graphics.Color.BLACK
                isAntiAlias = true
                // Playpen Sans Hebrew ships real regular/bold weights (unlike Dana Yad, a
                // single-weight font that needed fake-bold synthesis) — getTypeface(bold)
                // already loaded the correct weight's own file, so no fake-bold stroke needed.
            }

            // Force the paragraph direction explicitly instead of relying on StaticLayout's
            // default FIRSTSTRONG_LTR guess. That default already detects Hebrew text as an
            // RTL paragraph on its own, which made ALIGN_NORMAL mean "right" and ALIGN_OPPOSITE
            // mean "left" — so the old `if (isRtl) ALIGN_OPPOSITE` was backwards and left-aligned
            // every RTL headline. ALIGN_NORMAL always means "start of paragraph direction," so
            // pairing it with an explicit direction keeps the two in sync unambiguously.
            val textDirection = if (isRtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR
            // No Latin-fallback span needed — see the class doc: this font natively covers
            // both scripts in the same face, unlike every earlier Glamour font candidate.
            val renderText = text

            // Auto-shrink so no single word is ever forced to split mid-character. StaticLayout,
            // like any text engine (confirmed identically in the Feed Fidelity Check artifact's
            // CSS render at the same font/column-width combination before this fix existed),
            // has no choice but to break a word character-by-character once that one word alone
            // is wider than the available column — there's nowhere else for it to go. Allowing
            // more lines (maxLines) doesn't help: it only lets more separate lines/words fit
            // overall, it does nothing for a single word that's already too wide for even one
            // full-width empty line. Measuring the widest whitespace-delimited word at the
            // requested size and shrinking just enough for it to fit guarantees intact words at
            // any fontSize/focusScale, at the cost of the effective size self-limiting once
            // words stop fitting — reported as a real bug (mid-word breaks visible at large
            // Focus Mode sizes), not accepted as an inherent size/width tradeoff.
            val widestWordPx = renderText.split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .maxOfOrNull { paint.measureText(it) } ?: 0f
            if (widestWordPx > safeWidth) {
                // 0.90f, not just enough to cancel floating-point rounding: a browser/canvas
                // pre-check of this same algorithm (Feed Fidelity Check artifact) showed 2 of 11
                // words still measuring narrower than they actually rendered, specifically ones
                // adjacent to a smart-quote character the font may not have a native glyph for —
                // measureText() and the real glyph-drawing path can disagree when a fallback
                // glyph gets substituted at draw time. Paint.measureText() here uses the same
                // Typeface object as the actual draw call (unlike a browser, which can do
                // font-fallback substitution measureText() doesn't always see), so this specific
                // discrepancy may not reproduce — but the margin is sized to absorb it either way
                // rather than assume it can't.
                paint.textSize = (paint.textSize * safeWidth / widestWordPx * 0.90f).coerceAtLeast(6f)
            }
            // No justification: JUSTIFICATION_MODE_INTER_WORD stretches inter-word spacing so
            // every line but the true last one flushes both edges — fine for lines with many
            // words, but Hebrew headlines routinely wrap to just 2-3 words per line at these
            // font sizes, and stretching a couple of gaps to span the full column width reads
            // as broken, oversized spacing rather than justified text (reported directly:
            // "there is a lot of space between words"). ALIGN_NORMAL + explicit RTL text
            // direction already right-aligns every line correctly on its own (confirmed via
            // pixel-measuring real screenshots — every line's right edge landed within 1-2px of
            // the others, both in a full-width column and in a photo-narrowed one) — ragged-left
            // with natural word spacing is both correct and simpler than justified text ever was.
            val layout = StaticLayout.Builder
                .obtain(renderText, 0, renderText.length, paint, safeWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(textDirection)
                .setLineSpacing(0f, 1.2f)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()

            // ALPHA_8, not ARGB_8888 — a ~4x memory reduction (1 byte/pixel vs. 4) that stands
            // on its own: rendered text is a shape plus a solid color, not photographic RGB
            // content, so the color channels were always wasted bytes. Canvas.draw() onto an
            // ALPHA_8-config Bitmap captures only each pixel's coverage (the anti-aliased glyph
            // shape) into that single channel and discards whatever color the Paint had — the
            // real color gets applied at *display* time instead, via Glance's
            // ColorFilter.tint(ColorProvider) on the Image() composable that shows this bitmap
            // (see FeedItemRow.kt) — confirmed via javap on the real glance-appwidget:1.1.0 AAR
            // that this reaches a genuine RemoteViews action
            // (androidx.core.widget.RemoteViewsCompat.setImageViewColorFilter, not a
            // Compose/preview-only feature), so it works on the actual widget, not just an
            // Activity preview.
            val bmp = Bitmap.createBitmap(safeWidth, layout.height.coerceAtLeast(1), Bitmap.Config.ALPHA_8)
            layout.draw(Canvas(bmp))
            synchronized(cache) { cache[key] = bmp }
            bmp
        } catch (_: Exception) {
            null
        }
    }
}
