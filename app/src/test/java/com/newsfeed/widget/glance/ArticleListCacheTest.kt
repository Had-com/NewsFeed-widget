package com.newsfeed.widget.glance

import com.newsfeed.widget.data.ArticleItem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ArticleListCacheTest {
    private fun art(i: Int, read: Boolean = false) = ArticleItem(
        id = "id$i", feedId = "f", feedName = "F", title = "title $i", publishedAt = 1000L + i, isRead = read)

    @Test
    fun `decodes like Json and returns the same list for the same string`() {
        val list = (1..5).map { art(it) }
        val raw = Json.encodeToString(list)
        val a = ArticleListCache.decode(raw)
        assertEquals(list, a)
        assertSame(a, ArticleListCache.decode(String(raw.toCharArray())))   // equal string, different instance
    }

    @Test
    fun `a different string decodes again and an unreadable one is null without poisoning the cache`() {
        val one = Json.encodeToString(listOf(art(1)))
        val two = Json.encodeToString(listOf(art(1), art(2, read = true)))
        assertEquals(listOf(art(1)), ArticleListCache.decode(one))
        assertEquals(listOf(art(1), art(2, read = true)), ArticleListCache.decode(two))
        assertNull(ArticleListCache.decode("not json"))
        assertEquals(listOf(art(1), art(2, read = true)), ArticleListCache.decode(two))
        assertEquals(listOf(art(1)), ArticleListCache.decode(one))
    }

    @Test
    fun `remember hands the encoded list to the next decode`() {
        val list = listOf(art(7), art(8, read = true))
        val raw = Json.encodeToString(list)
        ArticleListCache.remember(raw, list)
        assertSame(list, ArticleListCache.decode(raw))
        assertEquals(list, Json.decodeFromString<List<ArticleItem>>(raw))   // what it hands over is what decoding gives
    }
}
