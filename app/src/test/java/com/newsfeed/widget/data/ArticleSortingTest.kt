package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ArticleSortingTest {

    private fun article(
        id: String,
        isRead: Boolean,
        readAt: Long? = null,
        publishedAt: Long = 0L,
    ) = ArticleItem(
        id = id,
        feedId = "feed1",
        feedName = "Feed",
        title = "Title $id",
        publishedAt = publishedAt,
        isRead = isRead,
        readAt = readAt,
    )

    private fun unreadOnlyConfig() = WidgetConfig(widgetId = 1, filter = FilterMode.UNREAD.key, sortOrder = SortOrder.NEWEST.key)

    @Test
    fun `unread filter includes an article that was never read`() {
        val articles = listOf(article("a1", isRead = false))
        val result = applyFilterAndSort(articles, unreadOnlyConfig())
        assertEquals(listOf("a1"), result.map { it.id })
    }

    @Test
    fun `unread filter includes an article read within the last 5 seconds`() {
        val now = System.currentTimeMillis()
        val articles = listOf(article("a1", isRead = true, readAt = now - 1_000L))
        val result = applyFilterAndSort(articles, unreadOnlyConfig())
        assertEquals(listOf("a1"), result.map { it.id })
    }

    @Test
    fun `unread filter excludes an article read more than 5 seconds ago`() {
        val now = System.currentTimeMillis()
        val articles = listOf(article("a1", isRead = true, readAt = now - 6_000L))
        val result = applyFilterAndSort(articles, unreadOnlyConfig())
        assertEquals(emptyList<String>(), result.map { it.id })
    }

    @Test
    fun `unread filter excludes an already-read article with no readAt timestamp`() {
        val articles = listOf(article("a1", isRead = true, readAt = null))
        val result = applyFilterAndSort(articles, unreadOnlyConfig())
        assertEquals(emptyList<String>(), result.map { it.id })
    }

    @Test
    fun `unread filter excludes a read article right at the grace period boundary`() {
        val now = System.currentTimeMillis()
        // By the time applyFilterAndSort computes its own `now`, at least
        // UNREAD_GRACE_PERIOD_MS will have elapsed since this readAt - the filter's
        // strict less-than comparison must exclude it, not include it.
        val articles = listOf(article("a1", isRead = true, readAt = now - UNREAD_GRACE_PERIOD_MS))
        val result = applyFilterAndSort(articles, unreadOnlyConfig())
        assertEquals(emptyList<String>(), result.map { it.id })
    }

    @Test
    fun `all filter includes read and unread articles regardless of readAt`() {
        val now = System.currentTimeMillis()
        val articles = listOf(
            article("a1", isRead = false),
            article("a2", isRead = true, readAt = now - 100_000L),
        )
        val config = WidgetConfig(widgetId = 1, filter = FilterMode.ALL.key, sortOrder = SortOrder.NEWEST.key)
        val result = applyFilterAndSort(articles, config)
        assertEquals(setOf("a1", "a2"), result.map { it.id }.toSet())
    }

    @Test
    fun `read filter is unaffected by the grace period`() {
        val now = System.currentTimeMillis()
        val articles = listOf(
            article("a1", isRead = true, readAt = now - 1_000L),
            article("a2", isRead = false),
        )
        val config = WidgetConfig(widgetId = 1, filter = FilterMode.READ.key, sortOrder = SortOrder.NEWEST.key)
        val result = applyFilterAndSort(articles, config)
        assertEquals(listOf("a1"), result.map { it.id })
    }
}
