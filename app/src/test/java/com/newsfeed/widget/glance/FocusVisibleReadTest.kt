package com.newsfeed.widget.glance

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.FeedConfig
import com.newsfeed.widget.data.FilterMode
import com.newsfeed.widget.data.WidgetConfig
import com.newsfeed.widget.data.WidgetStateKey
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BUG-026 / BUG-027 on the tap side: moving Focus off an article only marks it read when that article
 * is actually on screen, and same-id articles from two feeds are one article for the callbacks too.
 */
class FocusVisibleReadTest {

    private val attack1 = "פצוע קשה בפיגוע ירי בבנימין, מצוד אחר המחבל"
    private val attack2 = "פצוע קשה בפיגוע ירי במעיין בבנימין, מצוד אחר המחבל - כאן 11"
    private val attack3 = "ירי בבנימין: פצוע קשה, מצוד אחר המחבל"

    private fun art(id: String, feed: String, title: String, at: Long, isRead: Boolean = false) =
        ArticleItem(id = id, feedId = feed, feedName = feed, title = title, publishedAt = at, isRead = isRead)

    private val feeds = listOf("ynet", "kan", "walla", "globes").map { FeedConfig(feedId = it, displayName = it) }

    // Thread {t3 lead, t2, t1} + singles x, y.
    private val base = listOf(
        art("t3", "walla", attack3, 3_000L),
        art("t2", "kan", attack2, 2_000L),
        art("t1", "ynet", attack1, 1_000L),
        art("x", "globes", "הבורסה בתל אביב ננעלה בירידות חדות", 4_000L),
        art("y", "globes", "תוכנית חדשה למענקי דיור לזוגות צעירים בפריפריה", 5_000L),
    )

    private fun prefs(
        articles: List<ArticleItem> = base,
        focused: String,
        group: Boolean = true,
        filter: String = FilterMode.ALL.key,
        expanded: String? = null,
        withConfig: Boolean = true,
    ) = mutablePreferencesOf().also {
        it[WidgetStateKey.articles] = Json.encodeToString(articles)
        if (withConfig) it[WidgetStateKey.configJson] =
            Json.encodeToString(WidgetConfig(widgetId = 1, groupStories = group, filter = filter, feeds = feeds, feedOrder = feeds.map { f -> f.feedId }))
        it[WidgetStateKey.focusedArticleId] = focused
        if (expanded != null) it[WidgetStateKey.expandedThreadIds] = expanded
    }

    private fun stored(p: Preferences) = Json.decodeFromString<List<ArticleItem>>(p[WidgetStateKey.articles]!!)

    @Test
    fun `a stale focus on a member hidden in a collapsed thread is not silently marked read`() {
        val p = prefs(focused = "t2")   // t2 is hidden under the collapsed t3 thread
        assertEquals(emptyList<String>(), applyFocusTapThread(p, "x", now = 100L))
        assertEquals("x", p[WidgetStateKey.focusedArticleId])
        assertTrue(stored(p).none { it.isRead })
    }

    @Test
    fun `the same member IS marked when its thread is expanded because it is on screen`() {
        val p = prefs(focused = "t2", expanded = "t3")
        val marked = applyFocusTapThread(p, "x", now = 100L)
        assertEquals(listOf("t2", "t1", "t3"), marked)
        assertEquals(3, stored(p).count { it.isRead && it.readAt == 100L })
    }

    @Test
    fun `a focus on an article the filter hides is not marked read`() {
        // Read only: the unread y is not on screen
        val p = prefs(focused = "y", filter = FilterMode.READ.key)
        assertEquals(emptyList<String>(), applyFocusTapThread(p, "x", now = 100L))
        assertTrue(stored(p).none { it.isRead })
    }

    @Test
    fun `a focus on an article that no longer exists marks nothing`() {
        val p = prefs(focused = "gone")
        assertEquals(emptyList<String>(), applyFocusTapThread(p, "x", now = 100L))
        assertEquals("x", p[WidgetStateKey.focusedArticleId])
    }

    @Test
    fun `an on-screen focused article is marked read as before, grouping off too`() {
        assertEquals(listOf("y"), applyFocusTapThread(prefs(focused = "y", group = false), "x", now = 100L))
        assertEquals(listOf("y"), applyFocusTapThread(prefs(focused = "y"), "x", now = 100L))
    }

    @Test
    fun `an unreadable config keeps the old behaviour`() {
        val p = prefs(focused = "y", withConfig = false)
        assertEquals(listOf("y"), applyFocusTapThread(p, "x", now = 100L))
    }

    // ---- BUG-027 on the callback side ----

    private val dupTitle = "הבורסה בתל אביב ננעלה בירידות חדות השבוע"
    private val dup = listOf(
        art("1001558818", "globes", dupTitle, 9_000L),
        art("1001558818", "walla", dupTitle, 9_000L),     // the same article served by a second feed
        art("x", "kan", "תוכנית חדשה למענקי דיור לזוגות צעירים בפריפריה", 4_000L),
    )

    @Test
    fun `storedThreads treats same-id articles as one single article, not a plus-one thread`() {
        val p = prefs(articles = dup, focused = "")
        val threads = storedThreads(p, dup)!!
        assertEquals(2, threads.size)
        assertTrue(threads.all { it.others.isEmpty() })
    }

    @Test
    fun `focusing a duplicated article then tapping elsewhere marks it once and reads both copies`() {
        val p = prefs(articles = dup, focused = "1001558818")
        val marked = applyFocusTapThread(p, "x", now = 100L)
        assertEquals(listOf("1001558818"), marked)
        assertEquals(2, stored(p).count { it.id == "1001558818" && it.isRead && it.readAt == 100L })
    }

    @Test
    fun `re-tapping the duplicated article clears focus and marks nothing`() {
        val p = prefs(articles = dup, focused = "1001558818")
        assertEquals(emptyList<String>(), applyFocusTapThread(p, "1001558818", now = 100L))
        assertEquals("", p[WidgetStateKey.focusedArticleId])
    }
}
