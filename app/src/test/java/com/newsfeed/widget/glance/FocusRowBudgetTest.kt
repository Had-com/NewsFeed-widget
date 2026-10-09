package com.newsfeed.widget.glance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** BUG-026: the Focus row cap (pure maxRowsAllowedFor) and its memory-safety guarantees. */
class FocusRowBudgetTest {

    /**
     * The formula exactly as it stood inline in NewsFeedWidget.kt before the extraction (commit e7d5fca),
     * copied verbatim apart from taking its inputs as parameters. The no-focus path must equal this.
     */
    private fun legacyMaxRowsAllowed(
        fontSize: Float, widgetTheme: String, widthPx2: Int, scaledDensity2: Float,
        isFocusWidget: Boolean, focusScale: Float,
    ): Int {
        val worstCaseRowScale = if (isFocusWidget) maxOf(focusScale, 1f) else 1f
        val headlineLineHeightPx = 13f * fontSize * worstCaseRowScale * scaledDensity2 * 1.2f
        val worstCaseHeadlineLines = AdjustFocusScaleCallback.HEADLINE_MAX_LINES
        val headlineBytes = if (widgetTheme == "glamer")
            (widthPx2 * (worstCaseHeadlineLines * headlineLineHeightPx) * 1f) else 0f
        val thumbnailBytes = 100f * 100f * 4f
        val bytesPerRow = (headlineBytes + thumbnailBytes).coerceAtLeast(1f)
        val rowBudgetBytes = 10_000_000f
        val maxRowsCeiling = if (widgetTheme == "glamer") 60 else 300
        return (rowBudgetBytes / bytesPerRow).toInt().coerceIn(1, maxRowsCeiling)
    }

    private val fonts = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
    private val scales = listOf(0.75f, 1.0f, 1.25f, 1.4f, 2.0f, 2.5f)
    private val themes = listOf("glamer", "auto", "dark")
    // (widthPx, scaledDensity): a ~350dp wide widget on 2.75x and 3.5x screens, and a narrow one.
    private val screens = listOf(937 to 2.75f, 1193 to 3.5f, 300 to 2.0f)

    @Test
    fun `no focused row in a Focus widget is the 1x cap an Expand widget gets (BUG-032)`() {
        for (f in fonts) for (s in scales) for (t in themes) for ((w, d) in screens) {
            assertEquals(
                "font=$f scale=$s theme=$t w=$w d=$d",
                legacyMaxRowsAllowed(f, t, w, d, isFocusWidget = false, focusScale = s),
                maxRowsAllowedFor(f, t == "glamer", w, d, isFocusWidget = true, focusScale = s, focusActive = false),
            )
        }
    }

    @Test
    fun `the cap is continuous across a focus tap - focusing never grows it and drops at most ceil(extra over cost1) rows (BUG-032)`() {
        for (f in fonts) for (s in scales) for (t in themes) for ((w, d) in screens) {
            val glamour = t == "glamer"
            val none = maxRowsAllowedFor(f, glamour, w, d, true, s, focusActive = false)
            val focused = maxRowsAllowedFor(f, glamour, w, d, true, s, focusActive = true)
            val c1 = rowCostBytes(f, 1f, glamour, w, d)
            val extra = (rowCostBytes(f, maxOf(s, 1f), glamour, w, d) - c1).coerceAtLeast(0f)
            val maxDrop = kotlin.math.ceil((extra / c1).toDouble()).toInt() + 1   // + the floor() of the division
            assertTrue("font=$f scale=$s theme=$t w=$w d=$d none=$none focused=$focused", focused <= none)
            assertTrue("font=$f scale=$s theme=$t w=$w d=$d drop ${none - focused} > $maxDrop", none - focused <= maxDrop)
        }
    }

    @Test
    fun `glamour font 1_0 at 937px 2_75 density and scale 2_5 - 27 rows without focus, 26 with (the no-focus cap was 11)`() {
        assertEquals(26, maxRowsAllowedFor(1.0f, true, 937, 2.75f, true, 2.5f, focusActive = true))
        assertEquals(27, maxRowsAllowedFor(1.0f, true, 937, 2.75f, true, 2.5f, focusActive = false))
        assertEquals(11, legacyMaxRowsAllowed(1.0f, "glamer", 937, 2.75f, true, 2.5f))
    }

    @Test
    fun `a non-Focus widget is exactly the historical value and ignores focus state and scale`() {
        for (f in fonts) for (s in scales) for (t in themes) for ((w, d) in screens) {
            val old = legacyMaxRowsAllowed(f, t, w, d, isFocusWidget = false, focusScale = s)
            assertEquals(old, maxRowsAllowedFor(f, t == "glamer", w, d, false, s, focusActive = false))
            assertEquals(old, maxRowsAllowedFor(f, t == "glamer", w, d, false, s, focusActive = true))
        }
    }

    @Test
    fun `with a focused row the cap is never below the old uniform cap and never above the 1x cap`() {
        for (f in fonts) for (s in scales) for (t in themes) for ((w, d) in screens) {
            val glamour = t == "glamer"
            val old = legacyMaxRowsAllowed(f, t, w, d, true, s)
            val focused = maxRowsAllowedFor(f, glamour, w, d, true, s, focusActive = true)
            val at1x = legacyMaxRowsAllowed(f, t, w, d, false, 1f)
            assertTrue("font=$f scale=$s theme=$t: focused $focused < old $old", focused >= old)
            assertTrue("font=$f scale=$s theme=$t: focused $focused > 1x $at1x", focused <= at1x)
            assertTrue(focused >= 1)
        }
    }

    @Test
    fun `memory safety - n-1 rows at 1x plus one focused row stays within the same budget the old formula respected`() {
        for (f in fonts) for (s in scales) for ((w, d) in screens) {
            val n = maxRowsAllowedFor(f, true, w, d, true, s, focusActive = true)
            val c1 = rowCostBytes(f, 1f, true, w, d)
            val cF = rowCostBytes(f, maxOf(s, 1f), true, w, d)
            if (n > 1) {
                val total = (n - 1) * c1 + cF
                assertTrue("font=$f scale=$s: total $total > budget", total <= ROW_BUDGET_BYTES * 1.000001f)
            }
            // and never more than the old uniform bound's own guarantee: old total <= budget
            val old = legacyMaxRowsAllowed(f, "glamer", w, d, true, s)
            if (old > 1) assertTrue(old * cF <= ROW_BUDGET_BYTES * 1.000001f)
        }
    }

    @Test
    fun `a focus scale at or below 1 changes nothing because no row is enlarged`() {
        for (f in fonts) for ((w, d) in screens) {
            for (s in listOf(0.75f, 1.0f)) {
                assertEquals(
                    maxRowsAllowedFor(f, true, w, d, true, s, focusActive = false),
                    maxRowsAllowedFor(f, true, w, d, true, s, focusActive = true),
                )
            }
        }
    }

    @Test
    fun `non-Glamour themes have no bitmap headline so the focus scale costs nothing`() {
        // thumbnails only: 10MB / 40KB = 250 rows, whatever the font or focus scale
        for (s in scales) {
            assertEquals(250, maxRowsAllowedFor(1.0f, false, 937, 2.75f, true, s, focusActive = true))
            assertEquals(250, maxRowsAllowedFor(1.0f, false, 937, 2.75f, true, s, focusActive = false))
        }
    }

    @Test
    fun `glamour font 1_0 at 937px 2_75 density - rows allowed before and after`() {
        // old (uniform) / new (one focused row) at the focus scales of interest
        fun pair(s: Float) = legacyMaxRowsAllowed(1.0f, "glamer", 937, 2.75f, true, s) to
            maxRowsAllowedFor(1.0f, true, 937, 2.75f, true, s, focusActive = true)
        val at1x = legacyMaxRowsAllowed(1.0f, "glamer", 937, 2.75f, false, 1f)
        println("FOCUSBUDGET 1x=$at1x 1.25=${pair(1.25f)} 1.4=${pair(1.4f)} 2.5=${pair(2.5f)}")
        // the new cap is within one row of the 1x cap minus the focused row's extra cost
        assertTrue(pair(1.4f).second >= at1x - 2)
        assertTrue(pair(1.4f).second > pair(1.4f).first)
    }

    @Test
    fun `the cap never drops below one row`() {
        // an absurd density makes even one row exceed the whole budget: the floor is still one row
        assertEquals(1, maxRowsAllowedFor(3.0f, true, 1193, 12f, true, 2.5f, focusActive = true))
        assertEquals(1, maxRowsAllowedFor(3.0f, true, 1193, 12f, true, 2.5f, focusActive = false))
        assertEquals(1, maxRowsAllowedFor(3.0f, true, 1193, 12f, false, 2.5f, focusActive = false))
    }
}
