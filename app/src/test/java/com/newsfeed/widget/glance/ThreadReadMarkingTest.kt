package com.newsfeed.widget.glance

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.FeedConfig
import com.newsfeed.widget.data.GraceRefreshRegistry
import com.newsfeed.widget.data.WidgetConfig
import com.newsfeed.widget.data.WidgetStateKey
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whole-thread read marking through the two existing move-away paths (Expand and Focus). */
class ThreadReadMarkingTest {

    private val attack1 = "פצוע קשה בפיגוע ירי בבנימין, מצוד אחר המחבל"
    private val attack2 = "פצוע קשה בפיגוע ירי במעיין בבנימין, מצוד אחר המחבל - כאן 11"
    private val attack3 = "ירי בבנימין: פצוע קשה, מצוד אחר המחבל"

    private fun art(id: String, feed: String, title: String, at: Long, isRead: Boolean = false, readAt: Long? = null) =
        ArticleItem(id = id, feedId = feed, feedName = feed, title = title, publishedAt = at, isRead = isRead, readAt = readAt)

    private val feeds = listOf("ynet", "kan", "walla", "globes").map { FeedConfig(feedId = it, displayName = it) }

    // Thread T = {t3 (walla, lead), t2 (kan), t1 (ynet)}; stand-alone singles x and y.
    private val base = listOf(
        art("t3", "walla", attack3, 3_000L),
        art("t2", "kan", attack2, 2_000L),
        art("t1", "ynet", attack1, 1_000L),
        art("x", "globes", "הבורסה בתל אביב ננעלה בירידות חדות", 4_000L),
        art("y", "globes", "תוכנית חדשה למענקי דיור לזוגות צעירים בפריפריה", 5_000L),
    )

    private fun prefs(
        articles: List<ArticleItem> = base,
        group: Boolean = true,
        last: String? = null,
        focused: String? = null,
        withConfig: Boolean = true,
    ) = mutablePreferencesOf().also {
        it[WidgetStateKey.articles] = Json.encodeToString(articles)
        if (withConfig) it[WidgetStateKey.configJson] = Json.encodeToString(WidgetConfig(widgetId = 1, groupStories = group, feeds = feeds, feedOrder = feeds.map { f -> f.feedId }))
        if (last != null) it[WidgetStateKey.lastTappedArticleId] = last
        if (focused != null) it[WidgetStateKey.focusedArticleId] = focused
    }

    private fun stored(p: Preferences) = Json.decodeFromString<List<ArticleItem>>(p[WidgetStateKey.articles]!!)

    // ---- Expand mode: markPreviousTappedReadThread ------------------------------------

    @Test
    fun `leaving any member marks the whole thread read with one shared stamp`() {
        val p = prefs(last = "t2")
        val marked = markPreviousTappedReadThread(p, "x", now = 100L)
        assertEquals(listOf("t2", "t1", "t3"), marked)          // previous first, then the rest by id
        val s = stored(p)
        assertTrue(listOf("t1", "t2", "t3").all { id -> s.first { it.id == id }.let { it.isRead && it.readAt == 100L } })
        assertTrue(listOf("x", "y").none { id -> s.first { it.id == id }.isRead })
    }

    @Test
    fun `moving to another member of the same thread marks nothing`() {
        val p = prefs(last = "t3")
        assertEquals(emptyList<String>(), markPreviousTappedReadThread(p, "t1", now = 100L))
        assertEquals("t1", p[WidgetStateKey.lastTappedArticleId])   // tracking still follows the tap
        assertTrue(stored(p).none { it.isRead })
        // ... and leaving the thread afterwards marks all of it
        assertEquals(setOf("t1", "t2", "t3"), markPreviousTappedReadThread(p, "x", now = 200L).toSet())
    }

    @Test
    fun `a single article outside any thread is marked alone`() {
        val p = prefs(last = "x")
        assertEquals(listOf("x"), markPreviousTappedReadThread(p, "y", now = 100L))
        assertEquals(1, stored(p).count { it.isRead })
    }

    @Test
    fun `with grouping off only the previous article is marked`() {
        val p = prefs(group = false, last = "t2")
        assertEquals(listOf("t2"), markPreviousTappedReadThread(p, "x", now = 100L))
        assertEquals(setOf("t2"), stored(p).filter { it.isRead }.map { it.id }.toSet())
    }

    @Test
    fun `without a readable config the old single-article behaviour is kept`() {
        val p = prefs(withConfig = false, last = "t2")
        assertEquals(listOf("t2"), markPreviousTappedReadThread(p, "x", now = 100L))
        assertEquals("t2", markPreviousTappedRead(prefs(withConfig = false, last = "t2"), "x", now = 100L))
    }

    @Test
    fun `a later unread article joining the story forms its own new thread and the read ones stay read`() {
        val read = base.map { if (it.id.startsWith("t")) it.copy(isRead = true, readAt = 50L) else it }
        val newcomer = art("t4", "globes", "ירי בבנימין: פצוע קשה במעיין, מצוד אחר המחבל", 6_000L)
        val p = prefs(articles = read + newcomer, last = "t4")
        // leaving the newcomer marks ONLY the newcomer (no unread partner), readAt untouched elsewhere
        assertEquals(listOf("t4"), markPreviousTappedReadThread(p, "x", now = 100L))
        val s = stored(p)
        assertEquals(50L, s.first { it.id == "t1" }.readAt)
        assertEquals(100L, s.first { it.id == "t4" }.readAt)
    }

    @Test
    fun `the legacy single-id wrapper returns the previous article`() {
        assertEquals("t2", markPreviousTappedRead(prefs(last = "t2"), "x", now = 100L))
        assertNull(markPreviousTappedRead(prefs(last = "t2"), "t1", now = 100L))   // same thread
    }

    // ---- Focus mode: applyFocusTapThread ----------------------------------------------

    @Test
    fun `focus moving off a member to another article marks the whole thread`() {
        val p = prefs(focused = "t3")
        val marked = applyFocusTapThread(p, "x", now = 100L)
        assertEquals(listOf("t3", "t1", "t2"), marked)
        assertEquals("x", p[WidgetStateKey.focusedArticleId])
        assertEquals(3, stored(p).count { it.isRead && it.readAt == 100L })
    }

    @Test
    fun `focus moving between members of one thread marks nothing`() {
        val p = prefs(focused = "t3")
        assertEquals(emptyList<String>(), applyFocusTapThread(p, "t2", now = 100L))
        assertEquals("t2", p[WidgetStateKey.focusedArticleId])
        assertTrue(stored(p).none { it.isRead })
    }

    @Test
    fun `clearing focus by re-tapping marks nothing`() {
        val p = prefs(focused = "t3")
        assertEquals(emptyList<String>(), applyFocusTapThread(p, "t3", now = 100L))
        assertEquals("", p[WidgetStateKey.focusedArticleId])
    }

    @Test
    fun `focus with grouping off marks only the article that lost focus`() {
        val p = prefs(group = false, focused = "t3")
        assertEquals(listOf("t3"), applyFocusTapThread(p, "x", now = 100L))
    }

    @Test
    fun `the legacy applyFocusTap wrapper still returns the article that lost focus`() {
        assertEquals("t3", applyFocusTap(prefs(focused = "t3"), "x", now = 100L))
    }

    // ---- dissolve / grace schedule key ------------------------------------------------

    @Test
    fun `a thread is scheduled under one representative id`() {
        assertEquals("t1", GraceRefreshRegistry.representativeId(listOf("t3", "t1", "t2")))
        assertEquals("only", GraceRefreshRegistry.representativeId(listOf("only")))
        assertNull(GraceRefreshRegistry.representativeId(emptyList()))
        // the tap callback and the render-time resume see the same members, hence the same key
        val fromCallback = GraceRefreshRegistry.representativeId(listOf("t2", "t1", "t3"))
        val fromResume = GraceRefreshRegistry.representativeId(listOf("t3", "t2", "t1"))
        assertEquals(fromCallback, fromResume)
    }

    @Test
    fun `members marked read together form one read class and so one thread afterwards`() {
        val p = prefs(last = "t2")
        markPreviousTappedReadThread(p, "x", now = 100L)
        val threads = storedThreads(p, stored(p))!!
        val readThread = threads.first { it.lead.isRead }
        assertEquals(setOf("t1", "t2", "t3"), readThread.members.map { it.id }.toSet())
        assertFalse(threads.first { it.lead.id == "x" }.lead.isRead)
    }
}
