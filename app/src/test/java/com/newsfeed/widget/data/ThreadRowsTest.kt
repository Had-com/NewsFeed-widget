package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadRowsTest {

    private fun art(id: String, isRead: Boolean = false) = ArticleItem(
        id = id, feedId = "f$id", feedName = "F", title = "T$id",
        publishedAt = 1L, isRead = isRead,
    )

    private fun single(id: String) = StoryThread(art(id))
    private fun thread(lead: String, vararg others: String) = StoryThread(art(lead), others.map { art(it) })

    private val threads = listOf(single("a"), thread("b", "b1", "b2"), single("c"), thread("d", "d1"))

    @Test
    fun `collapsed threads cost one row and show their member count`() {
        val l = layoutThreads(threads, emptySet(), rowLimit = 100)
        assertEquals(listOf("a", "b", "c", "d"), l.rows.map { it.article.id })
        assertEquals(listOf(ThreadRowKind.SINGLE, ThreadRowKind.LEAD, ThreadRowKind.SINGLE, ThreadRowKind.LEAD), l.rows.map { it.kind })
        assertEquals(listOf(0, 2, 0, 1), l.rows.map { it.otherCount })
        assertEquals(4, l.availableRows)
        // the header counts ARTICLES: 1 + (1+2) + 1 + (1+1)
        assertEquals(7, l.articleCount)
        assertEquals(7, l.unreadCount)
    }

    @Test
    fun `an expanded thread lists lead then members and counts every row`() {
        val l = layoutThreads(threads, setOf("b"), rowLimit = 100)
        assertEquals(listOf("a", "b", "b1", "b2", "c", "d"), l.rows.map { it.article.id })
        assertEquals(
            listOf(ThreadRowKind.SINGLE, ThreadRowKind.LEAD, ThreadRowKind.MEMBER, ThreadRowKind.MEMBER, ThreadRowKind.SINGLE, ThreadRowKind.LEAD),
            l.rows.map { it.kind },
        )
        assertEquals(true, l.rows[1].expanded)
        assertEquals("b", l.rows[2].leadId)
        assertEquals(6, l.availableRows)
        assertEquals(7, l.articleCount)   // still 7 articles: 1 + 3 (all shown) + 1 + 2 (d collapsed)
    }

    @Test
    fun `an unknown or stale expanded id changes nothing`() {
        val l = layoutThreads(threads, setOf("zzz", "a"), rowLimit = 100)   // "a" is a single, not a thread lead
        assertEquals(listOf("a", "b", "c", "d"), l.rows.map { it.article.id })
    }

    @Test
    fun `the row limit counts rows, so an expanded thread can be cut and Load more finishes it`() {
        val l = layoutThreads(threads, setOf("b"), rowLimit = 3)
        assertEquals(listOf("a", "b", "b1"), l.rows.map { it.article.id })
        assertEquals(6, l.availableRows)
        // b is expanded (its members are rows, not hidden), so only rows are counted
        assertEquals(3, l.articleCount)
    }

    @Test
    fun `a limit of one row still shows the first row and a collapsed lead counts its hidden articles`() {
        val l = layoutThreads(listOf(thread("x", "x1", "x2")), emptySet(), rowLimit = 1)
        assertEquals(1, l.rows.size)
        assertEquals(3, l.articleCount)
    }

    @Test
    fun `unread count includes unread hidden members only`() {
        val t = StoryThread(art("u"), listOf(art("m1"), art("m2", isRead = true)))   // mixed only for the arithmetic
        val l = layoutThreads(listOf(t), emptySet(), rowLimit = 10)
        assertEquals(3, l.articleCount)
        assertEquals(2, l.unreadCount)   // lead + m1
    }

    @Test
    fun `with grouping off (ungrouped threads) the layout is the plain list`() {
        val arts = listOf(art("a"), art("b", isRead = true), art("c"))
        val l = layoutThreads(ungroupedStories(arts), setOf("a"), rowLimit = 2)
        assertEquals(listOf("a", "b"), l.rows.map { it.article.id })
        assertEquals(true, l.rows.all { it.kind == ThreadRowKind.SINGLE })
        assertEquals(3, l.availableRows)
        assertEquals(2, l.articleCount)
        assertEquals(1, l.unreadCount)
    }

    @Test
    fun `non-positive limit gives no rows`() {
        val l = layoutThreads(threads, emptySet(), rowLimit = 0)
        assertEquals(0, l.rows.size)
        assertEquals(0, l.articleCount)
        assertEquals(4, l.availableRows)
    }

    // ---- the footer speaks the header's unit (articles), not rows ----

    @Test
    fun `availableArticles counts every article, hidden members of collapsed threads included`() {
        // a(1) + b(1+2) + c(1) + d(1+1) = 7, whatever is expanded and however many rows exist
        assertEquals(7, layoutThreads(threads, emptySet(), rowLimit = 2).availableArticles)
        assertEquals(7, layoutThreads(threads, setOf("b"), rowLimit = 2).availableArticles)
        assertEquals(7, layoutThreads(threads, setOf("b", "d"), rowLimit = 100).availableArticles)
        assertEquals(7, layoutThreads(threads, emptySet(), rowLimit = 0).availableArticles)
        // while the row count is what Load more has always counted
        assertEquals(4, layoutThreads(threads, emptySet(), rowLimit = 2).availableRows)
        assertEquals(6, layoutThreads(threads, setOf("b"), rowLimit = 2).availableRows)
    }

    @Test
    fun `the footer X (articleCount) is the header total at every limit, collapsed or expanded, and never above Y`() {
        for (expanded in listOf(emptySet(), setOf("b"), setOf("d"), setOf("b", "d"))) {
            for (limit in 0..8) {
                val l = layoutThreads(threads, expanded, limit)
                // the header badge and the footer's X are the same number by construction
                assertEquals(l.rows.size + l.rows.filter { it.kind == ThreadRowKind.LEAD && !it.expanded }.sumOf { it.otherCount }, l.articleCount)
                assertEquals(true, l.articleCount <= l.availableArticles)
                // fewer rows than exist => fewer articles shown than exist (so a "Showing X of Y" line is never X == Y)
                if (l.rows.size < l.availableRows) assertEquals(true, l.articleCount < l.availableArticles)
            }
        }
    }

    @Test
    fun `an expanded thread cut at the limit - the footer counts what is shown, the total counts all members`() {
        // 40-style case at small scale: limit 3 shows a, b, b1 (b expanded, b2 cut); c and d (+1 hidden) are beyond
        val l = layoutThreads(threads, setOf("b"), rowLimit = 3)
        assertEquals(3, l.articleCount)
        assertEquals(7, l.availableArticles)
    }

    @Test
    fun `with a pinned row the footer counts the pinned slice`() {
        val l = layoutThreads(threads, emptySet(), rowLimit = 2, pinArticleId = "d")   // a, d (+1 hidden)
        assertEquals(3, l.articleCount)
        assertEquals(7, l.availableArticles)
    }

    @Test
    fun `grouping off - articles equal rows as before`() {
        val arts = listOf(art("a"), art("b", isRead = true), art("c"))
        val l = layoutThreads(ungroupedStories(arts), emptySet(), rowLimit = 2)
        assertEquals(l.availableRows, l.availableArticles)
        assertEquals(2, l.articleCount)
        assertEquals(l.rows.size, l.articleCount)
    }

    @Test
    fun `a fully shown list has X equal to Y`() {
        val l = layoutThreads(threads, setOf("b"), rowLimit = 100)
        assertEquals(l.availableArticles, l.articleCount)
        val c = layoutThreads(threads, emptySet(), rowLimit = 100)
        assertEquals(c.availableArticles, c.articleCount)
    }

    // ---- resumeKeys --------------------------------------------------------------------

    private fun read(id: String, readAt: Long) =
        art(id, isRead = true).copy(readAt = readAt)

    @Test
    fun `resumeKeys gives one key per readAt with the smallest id as representative`() {
        val keys = resumeKeys(listOf(read("c", 100L), read("a", 100L), read("z", 200L), read("b", 100L)))
        assertEquals(listOf(100L to "a", 200L to "z"), keys)
    }

    @Test
    fun `resumeKeys of nothing is empty`() {
        assertEquals(emptyList<Pair<Long, String?>>(), resumeKeys(emptyList()))
    }

    // ---- thread badge label --------------------------------------------------------------

    @Test
    fun `collapsed badge shows plus and the member count`() {
        assertEquals("+3", threadBadgeLabel(3, expanded = false))
        assertEquals("+1", threadBadgeLabel(1, expanded = false))
    }

    @Test
    fun `expanded badge shows a minus sign without a number`() {
        assertEquals("−", threadBadgeLabel(3, expanded = true))
        assertEquals("−", threadBadgeLabel(1, expanded = true))
    }

    @Test
    fun `no members means no badge`() {
        assertEquals("", threadBadgeLabel(0, expanded = false))
        assertEquals("", threadBadgeLabel(0, expanded = true))
    }

    // ---- separators ---------------------------------------------------------------

    @Test
    fun `single rows and the last row of a thread keep the normal separator`() {
        assertEquals(Separator.NORMAL, separatorStyleFor(ThreadRowKind.SINGLE, isLastOfThread = true))
        assertEquals(Separator.NORMAL, separatorStyleFor(ThreadRowKind.LEAD, isLastOfThread = true)) // collapsed lead
        assertEquals(Separator.NORMAL, separatorStyleFor(ThreadRowKind.MEMBER, isLastOfThread = true))
    }

    @Test
    fun `rows inside an expanded thread get the thin separator`() {
        assertEquals(Separator.THIN, separatorStyleFor(ThreadRowKind.LEAD, isLastOfThread = false))
        assertEquals(Separator.THIN, separatorStyleFor(ThreadRowKind.MEMBER, isLastOfThread = false))
    }

    @Test
    fun `the last row of the list has no separator`() {
        assertEquals(Separator.NONE, separatorStyleFor(ThreadRowKind.SINGLE, isLastOfThread = true, isLastRow = true))
        assertEquals(Separator.NONE, separatorStyleFor(ThreadRowKind.MEMBER, isLastOfThread = true, isLastRow = true))
    }

    @Test
    fun `a row is last of its thread unless a member follows`() {
        val l = layoutThreads(threads, setOf("b"), rowLimit = 100).rows // a, b, b1, b2, c, d
        val last = l.indices.map { isLastOfThread(l[it], l.getOrNull(it + 1)) }
        assertEquals(listOf(true, false, false, true, true, true), last)
    }

    @Test
    fun `a collapsed lead is last of its thread`() {
        val l = layoutThreads(threads, emptySet(), rowLimit = 100).rows
        assertEquals(true, l.indices.all { isLastOfThread(l[it], l.getOrNull(it + 1)) })
    }

    @Test
    fun `a thread cut mid-way ends at its last visible member`() {
        val l = layoutThreads(threads, setOf("b"), rowLimit = 3).rows // a, b, b1
        assertEquals(true, isLastOfThread(l[2], null))
    }

    // ---- timeline rail (right side) --------------------------------------------------------

    private fun railOf(rows: List<ThreadRow>) =
        rows.indices.map { railPartFor(rows[it], isLastOfThread(rows[it], rows.getOrNull(it + 1))) }

    @Test
    fun `single rows have no rail`() {
        val l = layoutThreads(threads, emptySet(), rowLimit = 100).rows // a, b, c, d
        assertEquals(RailPart.NONE, railPartFor(l[0], isLastOfThread = true))
        assertEquals(RailSpec(DotKind.NONE, lineAbove = false, lineBelow = false), railSpecFor(RailPart.NONE))
    }

    @Test
    fun `a collapsed lead shows only a hollow dot`() {
        val l = layoutThreads(threads, emptySet(), rowLimit = 100).rows
        assertEquals(listOf(RailPart.NONE, RailPart.COLLAPSED_LEAD, RailPart.NONE, RailPart.COLLAPSED_LEAD), railOf(l))
        assertEquals(RailSpec(DotKind.HOLLOW, lineAbove = false, lineBelow = false), railSpecFor(RailPart.COLLAPSED_LEAD))
    }

    @Test
    fun `an expanded thread is a hollow lead dot then filled member dots joined by a line`() {
        val l = layoutThreads(threads, setOf("b"), rowLimit = 100).rows // a, b, b1, b2, c, d
        assertEquals(
            listOf(RailPart.NONE, RailPart.LEAD_OPEN, RailPart.MIDDLE, RailPart.LAST, RailPart.NONE, RailPart.COLLAPSED_LEAD),
            railOf(l),
        )
        assertEquals(RailSpec(DotKind.HOLLOW, lineAbove = false, lineBelow = true), railSpecFor(RailPart.LEAD_OPEN))
        assertEquals(RailSpec(DotKind.FILLED, lineAbove = true, lineBelow = true), railSpecFor(RailPart.MIDDLE))
        // the last member's line stops at its dot: nothing below it
        assertEquals(RailSpec(DotKind.FILLED, lineAbove = true, lineBelow = false), railSpecFor(RailPart.LAST))
    }

    @Test
    fun `an expanded lead whose members were all cut by the row budget has no line below`() {
        val l = layoutThreads(threads, setOf("b"), rowLimit = 2).rows // a, b (members cut)
        assertEquals(true, l[1].expanded)
        assertEquals(RailPart.LEAD_CUT, railPartFor(l[1], isLastOfThread(l[1], null)))
        assertEquals(RailSpec(DotKind.HOLLOW, lineAbove = false, lineBelow = false), railSpecFor(RailPart.LEAD_CUT))
    }

    @Test
    fun `a thread cut after its first member ends its line at that member`() {
        val l = layoutThreads(threads, setOf("b"), rowLimit = 3).rows // a, b, b1
        assertEquals(listOf(RailPart.NONE, RailPart.LEAD_OPEN, RailPart.LAST), railOf(l))
    }

    @Test
    fun `the rail dot sits on the middle of the meta line`() {
        // plain row at font 1.0: the 14 dp feed circle is the tallest item, centre 7 dp below the padding
        assertEquals(8f + 7f, railDotCenterDpFor(paddingTopDp = 8, metaScale = 1f, hasBadge = false), 0.001f)
        // a lead's +N badge (12 dp text + 8 dp padding) is taller than the circle at font 1.0
        assertEquals(8f + 10f, railDotCenterDpFor(paddingTopDp = 8, metaScale = 1f, hasBadge = true), 0.001f)
        // thin top padding of a member row
        assertEquals(4f + 7f, railDotCenterDpFor(paddingTopDp = 4, metaScale = 1f, hasBadge = false), 0.001f)
        // largest font: the circle grows with the font, so does the whole meta line
        assertEquals(8f + 21f, railDotCenterDpFor(paddingTopDp = 8, metaScale = 3f, hasBadge = false), 0.001f)
        assertEquals(8f + 22f, railDotCenterDpFor(paddingTopDp = 8, metaScale = 3f, hasBadge = true), 0.001f)
    }

    @Test
    fun `padding is thin only between the rows of an expanded thread`() {
        assertEquals(8 to 8, verticalPaddingDpFor(ThreadRowKind.SINGLE, isLastOfThread = true))
        assertEquals(8 to 8, verticalPaddingDpFor(ThreadRowKind.LEAD, isLastOfThread = true)) // collapsed lead
        assertEquals(8 to 4, verticalPaddingDpFor(ThreadRowKind.LEAD, isLastOfThread = false))
        assertEquals(4 to 4, verticalPaddingDpFor(ThreadRowKind.MEMBER, isLastOfThread = false))
        assertEquals(4 to 8, verticalPaddingDpFor(ThreadRowKind.MEMBER, isLastOfThread = true))
    }
    // ---- BUG-026: the pinned (focused) row is never cut off ----

    private val later = listOf(single("x"), single("y"), single("z"), thread("b", "b1", "b2"))

    private fun ids(l: ThreadLayout) = l.rows.map { it.article.id }

    @Test
    fun `a pinned single beyond the cut replaces the last row`() {
        val l = layoutThreads(threads, setOf("b"), rowLimit = 3, pinArticleId = "c")   // a b b1 | b2 c d
        assertEquals(listOf("a", "b", "c"), ids(l))
        assertEquals(6, l.availableRows)
    }

    @Test
    fun `a pinned collapsed lead beyond the cut replaces the last row and counts its hidden articles`() {
        val l = layoutThreads(threads, emptySet(), rowLimit = 2, pinArticleId = "d")   // a b | c d
        assertEquals(listOf("a", "d"), ids(l))
        assertEquals(3, l.articleCount)   // a + (d + 1 hidden)
    }

    @Test
    fun `a pin that is already inside the cut or unknown or blank changes nothing`() {
        val plain = ids(layoutThreads(threads, emptySet(), rowLimit = 3))
        assertEquals(plain, ids(layoutThreads(threads, emptySet(), 3, pinArticleId = "b")))
        assertEquals(plain, ids(layoutThreads(threads, emptySet(), 3, pinArticleId = "nope")))
        assertEquals(plain, ids(layoutThreads(threads, emptySet(), 3, pinArticleId = "")))
        // a collapsed thread's HIDDEN member is not a row, so it cannot be pinned
        assertEquals(plain, ids(layoutThreads(threads, emptySet(), 3, pinArticleId = "b1")))
    }

    @Test
    fun `a pinned member whose lead is in the slice replaces the last row`() {
        val l = layoutThreads(threads, setOf("b"), rowLimit = 3, pinArticleId = "b2")  // a b b1 | b2
        assertEquals(listOf("a", "b", "b2"), ids(l))
    }

    @Test
    fun `a pinned member whose lead is beyond the cut brings its lead along`() {
        val l = layoutThreads(later, setOf("b"), rowLimit = 3, pinArticleId = "b2")    // x y z | b b1 b2
        assertEquals(listOf("x", "b", "b2"), ids(l))
        assertEquals(ThreadRowKind.LEAD, l.rows[1].kind)
        assertEquals(ThreadRowKind.MEMBER, l.rows[2].kind)
        assertEquals("b", l.rows[2].leadId)
    }

    @Test
    fun `a lead sitting exactly at the cut edge is kept with its pinned member`() {
        val l = layoutThreads(later, setOf("b"), rowLimit = 4, pinArticleId = "b2")    // x y z b | b1 b2
        assertEquals(listOf("x", "y", "b", "b2"), ids(l))
    }

    @Test
    fun `a one row limit shows just the pinned row and the slice never exceeds the limit`() {
        assertEquals(listOf("b2"), ids(layoutThreads(later, setOf("b"), rowLimit = 1, pinArticleId = "b2")))
        assertEquals(listOf("z"), ids(layoutThreads(later, setOf("b"), rowLimit = 1, pinArticleId = "z")))
        for (limit in 0..8) for (pin in listOf("", "x", "z", "b", "b1", "b2", "nope")) {
            assertEquals(true, layoutThreads(later, setOf("b"), limit, pin).rows.size <= limit)
        }
        assertEquals(emptyList<String>(), ids(layoutThreads(later, setOf("b"), rowLimit = 0, pinArticleId = "b2")))
    }

    @Test
    fun `layoutThreads without a pin is exactly the plain first-N slice`() {
        for (limit in 0..8) {
            assertEquals(
                flattenThreads(later, setOf("b")).take(limit).map { it.article.id },
                ids(layoutThreads(later, setOf("b"), limit)),
            )
        }
    }
}
