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
}
