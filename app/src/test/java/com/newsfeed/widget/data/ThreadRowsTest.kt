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

    // ---- separators and rail ---------------------------------------------------------------

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

    @Test
    fun `only the lead of an expanded thread and its members show the rail`() {
        val l = layoutThreads(threads, setOf("b"), rowLimit = 100).rows // a, b, b1, b2, c, d
        assertEquals(listOf(false, true, true, true, false, false), l.map { it.inExpandedThread })
        val collapsed = layoutThreads(threads, emptySet(), rowLimit = 100).rows
        assertEquals(true, collapsed.none { it.inExpandedThread })
    }

    @Test
    fun `padding is thin only between the rows of an expanded thread`() {
        assertEquals(8 to 8, verticalPaddingDpFor(ThreadRowKind.SINGLE, isLastOfThread = true))
        assertEquals(8 to 8, verticalPaddingDpFor(ThreadRowKind.LEAD, isLastOfThread = true)) // collapsed lead
        assertEquals(8 to 4, verticalPaddingDpFor(ThreadRowKind.LEAD, isLastOfThread = false))
        assertEquals(4 to 4, verticalPaddingDpFor(ThreadRowKind.MEMBER, isLastOfThread = false))
        assertEquals(4 to 8, verticalPaddingDpFor(ThreadRowKind.MEMBER, isLastOfThread = true))
    }
}
