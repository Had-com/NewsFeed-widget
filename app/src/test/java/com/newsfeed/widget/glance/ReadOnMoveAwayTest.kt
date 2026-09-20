package com.newsfeed.widget.glance

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.WidgetStateKey
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadOnMoveAwayTest {

    private fun article(id: String, isRead: Boolean = false, readAt: Long? = null) = ArticleItem(
        id = id, feedId = "f", feedName = "Feed", title = "T$id",
        publishedAt = 1L, isRead = isRead, readAt = readAt,
    )

    private fun prefsWith(vararg articles: ArticleItem, last: String? = null) = mutablePreferencesOf().also {
        it[WidgetStateKey.articles] = Json.encodeToString(articles.toList())
        if (last != null) it[WidgetStateKey.lastTappedArticleId] = last
    }

    private fun stored(prefs: androidx.datastore.preferences.core.Preferences) =
        Json.decodeFromString<List<ArticleItem>>(prefs[WidgetStateKey.articles]!!)

    @Test
    fun `first tap marks nothing but records the tapped article`() {
        val prefs = prefsWith(article("a"), article("b"))
        val result = markPreviousTappedRead(prefs, "a", now = 100L)
        assertNull(result)
        assertEquals("a", prefs[WidgetStateKey.lastTappedArticleId])
        assertTrue(stored(prefs).none { it.isRead })
    }

    @Test
    fun `tapping a different article marks the previous one read`() {
        val prefs = prefsWith(article("a"), article("b"), last = "a")
        val result = markPreviousTappedRead(prefs, "b", now = 100L)
        assertEquals("a", result)
        val list = stored(prefs)
        val a = list.first { it.id == "a" }
        val b = list.first { it.id == "b" }
        assertTrue(a.isRead)
        assertEquals(100L, a.readAt)
        assertFalse(b.isRead)
        assertNull(b.readAt)
    }

    @Test
    fun `re-tapping the same article returns null and changes nothing`() {
        val prefs = prefsWith(article("a"), last = "a")
        val before = prefs[WidgetStateKey.articles]
        val result = markPreviousTappedRead(prefs, "a", now = 100L)
        assertNull(result)
        assertEquals(before, prefs[WidgetStateKey.articles])
        assertEquals("a", prefs[WidgetStateKey.lastTappedArticleId])
    }

    @Test
    fun `previous already read is not re-stamped`() {
        val prefs = prefsWith(article("a", isRead = true, readAt = 5L), article("b"), last = "a")
        val result = markPreviousTappedRead(prefs, "b", now = 100L)
        assertNull(result)
        assertEquals(5L, stored(prefs).first { it.id == "a" }.readAt)
    }

    @Test
    fun `previous missing from the stored list returns null without crashing`() {
        val prefs = prefsWith(article("b"), last = "gone")
        val result = markPreviousTappedRead(prefs, "b", now = 100L)
        assertNull(result)
        assertEquals("b", prefs[WidgetStateKey.lastTappedArticleId])
        assertTrue(stored(prefs).none { it.isRead })
    }

    @Test
    fun `malformed articles json returns null without crashing`() {
        val prefs = mutablePreferencesOf()
        prefs[WidgetStateKey.articles] = "{not json"
        prefs[WidgetStateKey.lastTappedArticleId] = "a"
        val result = markPreviousTappedRead(prefs, "b", now = 100L)
        assertNull(result)
        assertEquals("b", prefs[WidgetStateKey.lastTappedArticleId])
        assertEquals("{not json", prefs[WidgetStateKey.articles])
    }

    @Test
    fun `absent articles json returns null without crashing`() {
        val prefs = mutablePreferencesOf()
        prefs[WidgetStateKey.lastTappedArticleId] = "a"
        val result = markPreviousTappedRead(prefs, "b", now = 100L)
        assertNull(result)
        assertNull(prefs[WidgetStateKey.articles])
        assertEquals("b", prefs[WidgetStateKey.lastTappedArticleId])
    }

    @Test
    fun `last tapped is always updated to the tapped id`() {
        val prefs = prefsWith(article("a"), article("b"), article("c"))
        markPreviousTappedRead(prefs, "a", now = 1L)
        assertEquals("a", prefs[WidgetStateKey.lastTappedArticleId])
        markPreviousTappedRead(prefs, "b", now = 2L)
        assertEquals("b", prefs[WidgetStateKey.lastTappedArticleId])
        markPreviousTappedRead(prefs, "b", now = 3L)
        assertEquals("b", prefs[WidgetStateKey.lastTappedArticleId])
    }
}
