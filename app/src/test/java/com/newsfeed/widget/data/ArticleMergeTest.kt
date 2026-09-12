package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ArticleMergeTest {

    private fun article(
        id: String,
        isRead: Boolean = false,
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

    @Test
    fun `a freshly-fetched article that was just marked read keeps its readAt from the existing store`() {
        val existing = listOf(article("a1", isRead = true, readAt = 12345L))
        // Freshly re-parsed from the live feed - always starts isRead=false, readAt=null,
        // regardless of what the stored copy says, until something re-applies read state.
        val fresh = listOf(article("a1", isRead = true, readAt = null))

        val result = mergeFreshArticles(fresh, existing)

        assertEquals(1, result.size)
        assertEquals(12345L, result[0].readAt)
    }

    @Test
    fun `a brand new article not seen before gets no readAt`() {
        val existing = emptyList<ArticleItem>()
        val fresh = listOf(article("new1", isRead = false, readAt = null))

        val result = mergeFreshArticles(fresh, existing)

        assertEquals(1, result.size)
        assertEquals(null, result[0].readAt)
    }

    @Test
    fun `an article only present in existing, not re-fetched this cycle, passes through unchanged`() {
        val existing = listOf(article("old1", isRead = true, readAt = 999L))
        val fresh = emptyList<ArticleItem>()

        val result = mergeFreshArticles(fresh, existing)

        assertEquals(1, result.size)
        assertEquals("old1", result[0].id)
        assertEquals(999L, result[0].readAt)
    }

    @Test
    fun `an unread article that was never read carries no readAt from either side`() {
        val existing = listOf(article("a1", isRead = false, readAt = null))
        val fresh = listOf(article("a1", isRead = false, readAt = null))

        val result = mergeFreshArticles(fresh, existing)

        assertEquals(1, result.size)
        assertEquals(null, result[0].readAt)
    }

    @Test
    fun `fresh article fields other than readAt are preserved, not the existing copy's`() {
        val existing = listOf(article("a1", isRead = true, readAt = 500L, publishedAt = 100L))
        val fresh = listOf(article("a1", isRead = true, readAt = null, publishedAt = 200L))

        val result = mergeFreshArticles(fresh, existing)

        // publishedAt (and every other field) comes from `fresh`, not `existing` - only
        // readAt is carried over.
        assertEquals(200L, result[0].publishedAt)
        assertEquals(500L, result[0].readAt)
    }
}
