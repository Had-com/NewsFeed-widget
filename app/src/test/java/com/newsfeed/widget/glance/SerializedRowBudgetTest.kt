package com.newsfeed.widget.glance

import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.FeedConfig
import com.newsfeed.widget.data.ThreadRow
import com.newsfeed.widget.data.ThreadRowKind
import com.newsfeed.widget.data.WidgetConfig
import com.newsfeed.widget.data.layoutRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BUG-029: the serialized-size row cap (the Binder parcel limit), independent of the bitmap budget. */
class SerializedRowBudgetTest {

    private fun art(id: String, title: String = "x".repeat(TYPICAL_TITLE_CHARS), description: String = "",
                    url: String = "https://example.com/a", feed: String = "f") = ArticleItem(
        id = id, feedId = feed, feedName = "F", title = title, articleUrl = url, description = description,
        publishedAt = 1L, isRead = false,
    )

    private fun single(a: ArticleItem) = ThreadRow(a, ThreadRowKind.SINGLE, a.id)

    private fun typicalRows(n: Int) = (1..n).map { single(art("a$it")) }

    private val feeds = listOf(FeedConfig(feedId = "f", displayName = "F", displayMode = "text"))
    private val config = WidgetConfig(widgetId = 1, feeds = feeds, widgetTheme = "simple", articleLength = "medium")

    private fun sizing(
        cfg: WidgetConfig = config, expandedId: String = "", focusedId: String = "",
        fullId: String = "", fullText: String = "", fullShown: Int = 0,
    ) = RowSizing(cfg, expandedId, focusedId, fullId, fullText.length, fullShown)

    // ---- the estimator -------------------------------------------------------------------------

    @Test
    fun `a typical collapsed row costs exactly the measured base`() {
        assertEquals(ROW_BASE_BYTES, estimatedRowBytes(titleChars = TYPICAL_TITLE_CHARS, expanded = false, hasThumbnail = false))
        // and no row is ever cheaper than the base, however short its title
        assertTrue(estimatedRowBytes(titleChars = 3, expanded = false, hasThumbnail = false) >= ROW_BASE_BYTES)
        assertTrue(estimatedRowBytes(titleChars = 0, expanded = false, hasThumbnail = false) >= ROW_BASE_BYTES)
    }

    @Test
    fun `the estimator is monotonic in every input`() {
        fun e(title: Int = 100, body: Int = 0, expanded: Boolean = false, thumb: Boolean = false, badge: Boolean = false,
              url: Int = 0, id: Int = 0) = estimatedRowBytes(title, body, expanded, thumb, badge, url, id)
        var prev = 0
        for (t in 0..800 step 20) { val v = e(title = t); assertTrue(v >= prev); prev = v }
        prev = 0
        for (b in 0..4200 step 100) { val v = e(body = b, expanded = true); assertTrue(v >= prev); prev = v }
        prev = 0
        for (u in 0..600 step 50) { val v = e(url = u, expanded = true); assertTrue(v >= prev); prev = v }
        prev = 0
        for (i in 0..600 step 50) { val v = e(id = i); assertTrue(v >= prev); prev = v }
        assertTrue(e(thumb = true) > e())
        assertTrue(e(badge = true) > e())
        assertTrue(e(expanded = true) > e())
        assertTrue(e(expanded = true, thumb = true) >= e(expanded = true))
    }

    @Test
    fun `text is counted at two bytes per char beyond the typical title`() {
        val base = estimatedRowBytes(TYPICAL_TITLE_CHARS, expanded = false, hasThumbnail = false)
        assertEquals(base + 2 * 200, estimatedRowBytes(TYPICAL_TITLE_CHARS + 200, expanded = false, hasThumbnail = false))
    }

    @Test
    fun `an expanded row counts more and its body text counts at two bytes per char`() {
        val collapsed = estimatedRowBytes(TYPICAL_TITLE_CHARS, expanded = false, hasThumbnail = false)
        val expandedNoBody = estimatedRowBytes(TYPICAL_TITLE_CHARS, bodyChars = 0, expanded = true, hasThumbnail = false)
        assertEquals(collapsed + EXPANDED_EXTRA_BYTES, expandedNoBody)
        assertEquals(expandedNoBody + 2 * 400,
            estimatedRowBytes(TYPICAL_TITLE_CHARS, bodyChars = 400, expanded = true, hasThumbnail = false))
        // body chars of a collapsed row are never put into the view
        assertEquals(collapsed, estimatedRowBytes(TYPICAL_TITLE_CHARS, bodyChars = 400, expanded = false, hasThumbnail = false))
    }

    @Test
    fun `a thumbnail adds only its node, the pixels are budgeted elsewhere`() {
        val plain = estimatedRowBytes(TYPICAL_TITLE_CHARS, expanded = false, hasThumbnail = false)
        val thumb = estimatedRowBytes(TYPICAL_TITLE_CHARS, expanded = false, hasThumbnail = true)
        assertEquals(THUMBNAIL_NODE_BYTES, thumb - plain)
        assertTrue(THUMBNAIL_NODE_BYTES < 100 * 100 * 4)
    }

    @Test
    fun `a long Telegram post body counts more than a collapsed row`() {
        val long = art("t", description = "ש".repeat(4096), url = "https://t.me/N12chat/123")
        val sz = sizing(expandedId = "t", cfg = config.copy(articleLength = "full"))
        val expandedBytes = sz.bytesFor(single(long))
        val collapsedBytes = sizing().bytesFor(single(long))
        assertTrue(expandedBytes >= collapsedBytes + 2 * 4096)
    }

    // ---- the cap over leading rows -----------------------------------------------------------

    @Test
    fun `300 typical rows are capped near budget over per-row cost`() {
        val costs = typicalRows(300).map { sizing().bytesFor(it) }
        val cap = maxRowsBySerializedSize(costs)
        assertEquals(SERIALIZED_BUDGET_BYTES / ROW_BASE_BYTES, cap)      // 36
        assertTrue(cap in 34..40)
        // and the rows it admits really fit while one more would not
        assertTrue(costs.take(cap).sum() <= SERIALIZED_BUDGET_BYTES)
        assertTrue(costs.take(cap + 1).sum() > SERIALIZED_BUDGET_BYTES)
    }

    @Test
    fun `30 typical rows are not limited, so small lists behave exactly as before`() {
        val costs = typicalRows(30).map { sizing().bytesFor(it) }
        assertEquals(30, maxRowsBySerializedSize(costs))
    }

    @Test
    fun `the cap never exceeds the number of rows and is never below one`() {
        assertEquals(5, maxRowsBySerializedSize(List(5) { ROW_BASE_BYTES }))
        assertEquals(1, maxRowsBySerializedSize(listOf(SERIALIZED_BUDGET_BYTES * 3, 10, 10)))
        assertEquals(1, maxRowsBySerializedSize(emptyList()))
        assertEquals(1, maxRowsBySerializedSize(listOf(ROW_BASE_BYTES), budgetBytes = 0))
    }

    @Test
    fun `the cap is monotonic in the budget`() {
        val costs = typicalRows(300).map { sizing().bytesFor(it) }
        var prev = 0
        for (b in 0..1_000_000 step 25_000) { val c = maxRowsBySerializedSize(costs, b); assertTrue(c >= prev); prev = c }
    }

    @Test
    fun `heavier rows lower the cap`() {
        val light = List(300) { ROW_BASE_BYTES }
        val heavy = List(300) { ROW_BASE_BYTES + 6000 }
        assertTrue(maxRowsBySerializedSize(heavy) < maxRowsBySerializedSize(light))
    }

    // ---- combining with the existing caps, pin and thread rows ---------------------------------

    @Test
    fun `serializedRowCap is the min with the existing cap and equals the prefix cap without a pin`() {
        val flat = typicalRows(300)
        val sz = sizing()
        val costs = flat.map { sz.bytesFor(it) }
        assertEquals(maxRowsBySerializedSize(costs), serializedRowCap(flat, upper = 250, pinArticleId = "", costOf = sz::bytesFor))
        // an existing cap tighter than the serialized one wins (Glamour: 26)
        assertEquals(26, serializedRowCap(flat, upper = 26, pinArticleId = "", costOf = sz::bytesFor))
        // and a short list is just its size
        assertEquals(10, serializedRowCap(typicalRows(10), upper = 250, pinArticleId = "", costOf = sz::bytesFor))
    }

    @Test
    fun `the cap counts a collapsed thread as one row and an expanded one as lead plus members`() {
        val sz = sizing()
        val collapsed = ThreadRow(art("L"), ThreadRowKind.LEAD, "L", otherCount = 3, expanded = false)
        val lead = ThreadRow(art("L"), ThreadRowKind.LEAD, "L", otherCount = 3, expanded = true)
        val m1 = ThreadRow(art("m1"), ThreadRowKind.MEMBER, "L")
        val m2 = ThreadRow(art("m2"), ThreadRowKind.MEMBER, "L")
        val m3 = ThreadRow(art("m3"), ThreadRowKind.MEMBER, "L")
        // a collapsed thread is ONE row (its badge adds a node); an expanded one is four rows
        assertEquals(sz.bytesFor(single(art("L"))) + BADGE_BYTES, sz.bytesFor(collapsed))
        val rowsCost = listOf(lead, m1, m2, m3).sumOf { sz.bytesFor(it) }
        assertTrue(rowsCost > 3 * sz.bytesFor(single(art("L"))))
    }

    @Test
    fun `a pinned focused row beyond the cut is paid for, so the slice still fits`() {
        val flat = typicalRows(300)
        // the focused row is the LAST of 300 and is expanded with a long body: its cost is large
        val focusedId = "a300"
        val big = flat.dropLast(1) + single(art(focusedId, description = "d".repeat(4096), url = "https://t.me/c/1"))
        val sz = sizing(cfg = config.copy(articleLength = "full", tapMode = "focus"), expandedId = focusedId, focusedId = focusedId)
        val cap = serializedRowCap(big, upper = 250, pinArticleId = focusedId, costOf = sz::bytesFor)
        val slice = layoutRows(big, cap, focusedId).rows
        assertTrue(slice.any { it.article.id == focusedId })
        assertTrue(slice.sumOf { sz.bytesFor(it) } <= SERIALIZED_BUDGET_BYTES)
        // without paying for it the plain prefix cap would have admitted more rows
        assertTrue(cap < maxRowsBySerializedSize(big.map { sz.bytesFor(it) }) || cap <= 36)
        assertTrue(cap >= 1)
    }

    // ---- RowSizing: what the render actually puts in the view ---------------------------------

    @Test
    fun `body chars follow the article length setting`() {
        val a = art("a", description = "d".repeat(900), url = "https://example.com/a")
        assertEquals(101, sizing(cfg = config.copy(articleLength = "short"), expandedId = "a").bodyCharsFor(a))
        assertEquals(401, sizing(cfg = config.copy(articleLength = "medium"), expandedId = "a").bodyCharsFor(a))
        val none = art("n", description = "")
        assertEquals(0, sizing(expandedId = "n").bodyCharsFor(none))
    }

    @Test
    fun `full mode - a Telegram post shows its whole description, other posts clip at 400 plus the button payload`() {
        val tg = art("t", description = "d".repeat(3000), url = "https://t.me/N12chat/5")
        val rss = art("r", description = "d".repeat(3000), url = "https://example.com/r")
        val full = config.copy(articleLength = "full")
        assertEquals(3000, sizing(cfg = full, expandedId = "t").bodyCharsFor(tg))
        // clipped description (400) + the full description again inside the Load full article button's ActionParameters
        assertEquals(400 + 3000, sizing(cfg = full, expandedId = "r").bodyCharsFor(rss))
    }

    @Test
    fun `full mode - a fetched full article counts its whole text on non-Glamour and only the shown chunks on Glamour`() {
        val a = art("a", description = "d".repeat(100), url = "https://example.com/a")
        val text = "w".repeat(20_000)
        val simple = sizing(cfg = config.copy(articleLength = "full"), expandedId = "a", fullId = "a", fullText = text, fullShown = 1200)
        assertEquals(20_000, simple.bodyCharsFor(a))
        val glamour = sizing(cfg = config.copy(articleLength = "full", widgetTheme = "glamer"), expandedId = "a", fullId = "a", fullText = text, fullShown = 1200)
        assertEquals(1200, glamour.bodyCharsFor(a))
    }

    @Test
    fun `only the expanded or focused row gets the expanded cost and image feeds add a thumbnail node`() {
        val imageCfg = config.copy(feeds = listOf(FeedConfig(feedId = "f", displayName = "F", displayMode = "image")))
        val a = art("a", description = "hello")
        val b = art("b", description = "hello")
        val sz = sizing(cfg = imageCfg, expandedId = "a")
        assertTrue(sz.bytesFor(single(a)) > sz.bytesFor(single(b)))
        assertEquals(estimatedRowBytes(TYPICAL_TITLE_CHARS, expanded = false, hasThumbnail = true), sz.bytesFor(single(b)))
        // focus: the focused row is expanded too
        val fz = sizing(cfg = imageCfg.copy(tapMode = "focus"), focusedId = "b")
        assertTrue(fz.bytesFor(single(b)) > fz.bytesFor(single(a)))
    }

    // ---- the footer condition and the Load more clamp ------------------------------------------

    @Test
    fun `when the serialized cap binds the existing footer condition fires and Load more disappears`() {
        val flat = typicalRows(173)
        val sz = sizing()
        val maxRowsAllowed = 250                       // the bitmap cap (non-Glamour, thumbnails only)
        val cap = serializedRowCap(flat, maxRowsAllowed, "", sz::bytesFor)
        val effective = effectiveRowCap(maxRowsAllowed, cap, minOf(maxRowsAllowed, flat.size))
        assertEquals(cap, effective)
        val visible = 130                               // what the taps accumulated
        val layout = layoutRows(flat, visible.coerceAtMost(effective), "")
        val canLoadMore = visible < effective && visible < layout.availableRows
        val memoryCapReached = layout.rows.size >= effective && layout.rows.size < layout.availableRows
        assertFalse(canLoadMore)
        assertTrue(memoryCapReached)
        assertEquals(cap, layout.rows.size)
    }

    @Test
    fun `when the serialized cap does not bind the effective cap is the old one unchanged`() {
        val flat = typicalRows(20)
        val sz = sizing()
        val cap = serializedRowCap(flat, 250, "", sz::bytesFor)
        assertEquals(250, effectiveRowCap(250, cap, minOf(250, flat.size)))
        assertEquals(26, effectiveRowCap(26, serializedRowCap(typicalRows(300), 26, "", sz::bytesFor), 26))
    }

    @Test
    fun `Load more increments by a chunk but never past the cap`() {
        val chunk = 10
        assertEquals(20, nextVisibleCount(current = 10, chunk = chunk, cap = 36))
        assertEquals(30, nextVisibleCount(current = 20, chunk = chunk, cap = 36))
        assertEquals(36, nextVisibleCount(current = 30, chunk = chunk, cap = 36))     // clamped
        assertEquals(36, nextVisibleCount(current = 36, chunk = chunk, cap = 36))     // a tap past the cap is a no-op
        assertEquals(36, nextVisibleCount(current = 130, chunk = chunk, cap = 36))    // a stale accumulated value is pulled back
        assertEquals(40, nextVisibleCount(current = 30, chunk = chunk, cap = null))   // cap unknown: old behaviour
        assertEquals(1, nextVisibleCount(current = 0, chunk = 0, cap = 0))            // never below one
    }

    // ---- the truthful footer ------------------------------------------------------------------

    @Test
    fun `the cap reason is serialized when only the serialized cap binds`() {
        // non-Glamour: bitmap cap 250, 173 rows, serialized cap 36
        assertEquals(CapReason.SERIALIZED, capReason(maxRowsAllowed = 250, serializedCap = 36, available = 173))
    }

    @Test
    fun `the cap reason is memory when only the bitmap cap binds`() {
        // Glamour: bitmap cap 26 (serialized cap is limited to it by upper), 173 rows
        assertEquals(CapReason.MEMORY, capReason(maxRowsAllowed = 26, serializedCap = 26, available = 173))
    }

    @Test
    fun `the cap reason is serialized when both bind`() {
        assertEquals(CapReason.SERIALIZED, capReason(maxRowsAllowed = 100, serializedCap = 36, available = 173))
    }

    @Test
    fun `the cap reason is none when everything fits`() {
        assertEquals(CapReason.NONE, capReason(maxRowsAllowed = 250, serializedCap = 20, available = 20))
        assertEquals(CapReason.NONE, capReason(maxRowsAllowed = 26, serializedCap = 20, available = 20))
    }

    @Test
    fun `the reason follows the real caps end to end`() {
        val sz = sizing()
        val flat = typicalRows(173)
        assertEquals(CapReason.SERIALIZED, capReason(250, serializedRowCap(flat, 250, "", sz::bytesFor), flat.size))
        assertEquals(CapReason.MEMORY, capReason(26, serializedRowCap(flat, 26, "", sz::bytesFor), flat.size))
        assertEquals(CapReason.NONE, capReason(250, serializedRowCap(typicalRows(20), 250, "", sz::bytesFor), 20))
    }

    @Test
    fun `footer wording - serialized never suggests a smaller font, memory does`() {
        assertEquals("Showing 36 of 173 · the widget can't show more at once",
            capFooterText(CapReason.SERIALIZED, 36, 173, atFontFloor = false))
        assertEquals("Showing 36 of 173 · the widget can't show more at once",
            capFooterText(CapReason.SERIALIZED, 36, 173, atFontFloor = true))
        assertEquals("Showing 26 of 173 · reduce font size to see more",
            capFooterText(CapReason.MEMORY, 26, 173, atFontFloor = false))
        assertEquals("Showing 26 of 173 · this device can't show more at once",
            capFooterText(CapReason.MEMORY, 26, 173, atFontFloor = true))
        assertEquals(null, capFooterText(CapReason.NONE, 20, 20, atFontFloor = false))
    }
}
