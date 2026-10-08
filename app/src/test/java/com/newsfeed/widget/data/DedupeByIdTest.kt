package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** BUG-027: the same article id served by two feeds is one article. */
class DedupeByIdTest {

    private fun art(id: String, feed: String, at: Long = 1_000L, isRead: Boolean = false, title: String = "T$id") =
        ArticleItem(id = id, feedId = feed, feedName = feed, title = title, publishedAt = at, isRead = isRead)

    @Test
    fun `keeps the first occurrence of an id and preserves the order`() {
        val l = listOf(art("a", "f1"), art("b", "f1"), art("a", "f2"), art("c", "f2"), art("b", "f2"))
        val r = dedupeById(l)
        assertEquals(listOf("a", "b", "c"), r.map { it.id })
        assertEquals(listOf("f1", "f1", "f2"), r.map { it.feedId })
    }

    @Test
    fun `is a no-op without duplicates`() {
        val l = listOf(art("a", "f1"), art("b", "f2"), art("c", "f1"))
        assertEquals(l, dedupeById(l))
        assertTrue(dedupeById(emptyList()).isEmpty())
    }

    @Test
    fun `keeps the read state of the kept copy`() {
        val r = dedupeById(listOf(art("a", "f1", isRead = true), art("a", "f2", isRead = false)))
        assertEquals(1, r.size)
        assertTrue(r[0].isRead)
        val r2 = dedupeById(listOf(art("a", "f1", isRead = false), art("a", "f2", isRead = true)))
        assertTrue(!r2[0].isRead)
    }

    @Test
    fun `follows the sort order - the copy that sorts first is kept`() {
        val config = WidgetConfig(widgetId = 1, sortOrder = SortOrder.NEWEST.key, feeds = listOf(FeedConfig("f1", "f1"), FeedConfig("f2", "f2")))
        val l = listOf(art("a", "f1", at = 1_000L), art("x", "f2", at = 5_000L), art("a", "f2", at = 1_000L))
        val r = dedupeById(applyFilterAndSort(l, config, 0L))
        assertEquals(listOf("x", "a"), r.map { it.id })
    }

    @Test
    fun `a duplicate-id pair is not a thread any more - one row, no plus-one badge`() {
        // The Globes case: same id, same title, two feeds. Without the dedupe this was a "+1" thread
        // whose lead and member shared an id.
        val feeds = listOf(FeedConfig("globes1", "g1"), FeedConfig("globes2", "g2"))
        val config = WidgetConfig(widgetId = 1, groupStories = true, sortOrder = SortOrder.NEWEST.key, feeds = feeds)
        val title = "הבורסה בתל אביב ננעלה בירידות חדות השבוע"
        val l = listOf(art("1001558818", "globes1", title = title), art("1001558818", "globes2", title = title))
        val threads = displayThreadsFor(l, config, 0L)
        assertEquals(1, threads.size)
        assertTrue(threads[0].others.isEmpty())
        val layout = layoutThreads(threads, emptySet(), 10)
        assertEquals(listOf(ThreadRowKind.SINGLE), layout.rows.map { it.kind })
        assertEquals(1, layout.articleCount)
        assertEquals(1, layout.unreadCount)
        assertEquals(1, layout.availableRows)
    }

    @Test
    fun `articles of feeds no longer configured are dropped before the dedupe`() {
        val config = WidgetConfig(widgetId = 1, groupStories = false, sortOrder = SortOrder.NEWEST.key, feeds = listOf(FeedConfig("f2", "f2")))
        val threads = displayThreadsFor(listOf(art("a", "f1"), art("a", "f2")), config, 0L)
        assertEquals(listOf("f2"), threads.map { it.lead.feedId })
    }
}
