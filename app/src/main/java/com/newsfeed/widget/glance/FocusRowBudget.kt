package com.newsfeed.widget.glance

import kotlin.math.max

/**
 * The widget's row cap: how many list rows may be rendered at once without exceeding RemoteViews'
 * total bitmap-memory budget for one widget update ("RemoteViews for widget update exceeds maximum
 * bitmap memory usage" / "Can't show content"). Pure and unit-tested; NewsFeedWidget feeds it the
 * device-dependent inputs. (Extracted from the inline computation in NewsFeedWidget.kt, see the
 * history in docs/BUGS.md.)
 *
 * Per-row worst case: a Glamour headline bitmap at AdjustFocusScaleCallback.HEADLINE_MAX_LINES lines
 * (ALPHA_8 = 1 byte/pixel) plus a small ARGB_8888 100x100 thumbnail. Themes without a bitmap
 * headline cost only the thumbnail. Deliberately overestimates.
 *
 * The list's rows are at 1.0x, except in Focus mode ONE row (the focused one) which renders at
 * focusScale x (never below 1). Therefore:
 *  - no focused row (or not a Focus widget): the historical bound, UNCHANGED - every row budgeted at
 *    the same worst-case scale (max(focusScale, 1) for a Focus widget, 1 otherwise): n = B / cost(s);
 *  - a focused row on screen: the real worst case, (n - 1) rows at cost(1) plus one at cost(focusScale):
 *    n = floor((B - extra) / cost(1)), extra = cost(focusScale) - cost(1).
 *
 * Memory-safety proof for the focused case. Let c1 = cost(1), cF = cost(max(focusScale,1)),
 * extra = cF - c1 >= 0, B = ROW_BUDGET_BYTES, n = floor((B - extra) / c1) (before the clamp). The
 * rendered total is (n-1)*c1 + cF = n*c1 + extra <= (B - extra) + extra = B: within the same budget the
 * historical formula respected (it guaranteed n_old * cF <= B). When the clamp raises n to 1 the single
 * row is the same unavoidable 1-row floor the old formula had (TextBitmapHelper's textSizePx cap is the
 * backstop for that). The ceiling clamp only lowers n. So the focused-case total never exceeds B
 * whenever the old one did not, and it never exceeds the old uniform bound n_old * cF either:
 * n_old*cF <= B, and the new total is also <= B, so memory safety is preserved, with n_new >= n_old
 * whenever one focused row fits (B >= cF). Also n_new <= B / c1 = the no-focus-at-1x row count.
 */
// History of the numbers below (moved here verbatim in spirit from NewsFeedWidget.kt):
//  - 1 byte/pixel, not 4: TextBitmapHelper renders headline bitmaps as ALPHA_8 (a colorless coverage
//    mask tinted at display time). The formula predates that and was left at the old multiplier,
//    making the budget ~4x too conservative (confirmed on-device: Font size 3.0 x focus scale 2.5
//    still capped at "Showing 1 of 300" after the ALPHA_8 switch).
//  - The thumbnail is a real downloaded photo (ARGB_8888), so it stays 4 bytes/pixel.
//  - The line count is AdjustFocusScaleCallback.HEADLINE_MAX_LINES, ONE shared cap for focused and
//    unfocused rows (two hardcoded numbers once drifted apart and caused a real "Can't show content").
//  - 10MB here (rebalanced from 7.5MB/9MB against FeedItemRow's full-article chunk budget of 6.5MB,
//    ~16.5MB in total): the list is what is visible and memory-constrained all the time, the
//    full-article chunk budget only matters for the one expanded article.
//  - Floor 1, not 5: at Font size 3.0 + focus scale 2.5 a single row's worst case exceeds the whole
//    budget ("Can't show content" confirmed on-device); demanding 5 such rows is backwards. The
//    textSizePx cap in TextBitmapHelper.render() is the real backstop for one row.
//  - Ceiling 60 for Glamour (its bitmap cost can grow large and unpredictable), 300 (the whole
//    store) for every other theme: their headline is plain Text, so the cost is thumbnails only.
internal const val ROW_BUDGET_BYTES = 10_000_000f
/** A real downloaded photo thumbnail (ARGB_8888 100x100), not a text bitmap. */
internal const val THUMBNAIL_BYTES = 100f * 100f * 4f

/** Worst-case bytes of ONE row rendered at [rowScale] x [fontSize]. */
internal fun rowCostBytes(
    fontSize: Float,
    rowScale: Float,
    isGlamour: Boolean,
    widthPx: Int,
    scaledDensity: Float,
): Float {
    val headlineLineHeightPx = 13f * fontSize * rowScale * scaledDensity * 1.2f
    val headlineBytes = if (isGlamour)
        (widthPx * (AdjustFocusScaleCallback.HEADLINE_MAX_LINES * headlineLineHeightPx) * 1f) else 0f
    return (headlineBytes + THUMBNAIL_BYTES).coerceAtLeast(1f)
}

/**
 * Max list rows (see the class-level notes above). [focusActive] = a focused article is actually
 * among the rows being rendered; it only matters when [isFocusWidget].
 */
internal fun maxRowsAllowedFor(
    fontSize: Float,
    isGlamour: Boolean,
    widthPx: Int,
    scaledDensity: Float,
    isFocusWidget: Boolean,
    focusScale: Float,
    focusActive: Boolean,
): Int {
    val ceiling = if (isGlamour) 60 else 300
    val worstScale = if (isFocusWidget) max(focusScale, 1f) else 1f
    if (!(isFocusWidget && focusActive)) {
        val perRow = rowCostBytes(fontSize, worstScale, isGlamour, widthPx, scaledDensity)
        return (ROW_BUDGET_BYTES / perRow).toInt().coerceIn(1, ceiling)
    }
    val c1 = rowCostBytes(fontSize, 1f, isGlamour, widthPx, scaledDensity)
    val cFocused = rowCostBytes(fontSize, worstScale, isGlamour, widthPx, scaledDensity)
    val extra = (cFocused - c1).coerceAtLeast(0f)
    return ((ROW_BUDGET_BYTES - extra) / c1).toInt().coerceIn(1, ceiling)
}
