package com.newsfeed.widget.glance

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.FeedConfig
import com.newsfeed.widget.data.WidgetConfig
import com.newsfeed.widget.data.WidgetStateKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadStateTest {

    private val attack1 = "פצוע קשה בפיגוע ירי בבנימין, מצוד אחר המחבל"
    private val attack2 = "פצוע קשה בפיגוע ירי במעיין בבנימין, מצוד אחר המחבל - כאן 11"

    private fun art(id: String, feed: String, title: String, at: Long) = ArticleItem(
        id = id, feedId = feed, feedName = feed, title = title, publishedAt = at, isRead = false,
    )

    // m1 (ynet) + m2 (kan) are one thread led by m2; s is an unrelated single.
    private val articles = listOf(
        art("m2", "kan", attack2, 2_000L),
        art("m1", "ynet", attack1, 1_000L),
        art("s", "walla", "הבורסה בתל אביב ננעלה בירידות חדות", 3_000L),
    )

    private fun config(group: Boolean = true) = WidgetConfig(
        widgetId = 1, groupStories = group,
        feeds = listOf("ynet", "kan", "walla").map { FeedConfig(feedId = it, displayName = it) },
    )

    private fun prefs(group: Boolean = true, withConfig: Boolean = true) = mutablePreferencesOf().also {
        it[WidgetStateKey.articles] = Json.encodeToString(articles)
        if (withConfig) it[WidgetStateKey.configJson] = Json.encodeToString(config(group))
    }

    @Test
    fun `storedThreads drops articles past the Keep-articles window like the render does`() {
        // m1 / m2 are 1 s and 2 s old (publishedAt 1_000 / 2_000 against now = 3_000): 15 minutes keeps both,
        // and an hour later (now = 3_000 + 1 h) both are expired and no thread is left.
        val cfg = config().copy(retentionMinutes = 15)
        val p = mutablePreferencesOf().also {
            it[WidgetStateKey.articles] = Json.encodeToString(articles)
            it[WidgetStateKey.configJson] = Json.encodeToString(cfg)
        }
        assertEquals(2, storedThreads(p, articles, now = 3_000L)!!.first { it.lead.id == "m2" }.size)
        assertTrue(storedThreads(p, articles, now = 3_000L + 60 * 60_000L)!!.isEmpty())
    }

    // ---- encode / parse ---------------------------------------------------------------

    @Test
    fun `ids round-trip in insertion order and blanks are dropped`() {
        assertEquals(listOf("a", "b", "c"), parseExpandedThreadIds(encodeExpandedThreadIds(listOf("a", "b", "c"))).toList())
        assertEquals(emptySet<String>(), parseExpandedThreadIds(null))
        assertEquals(emptySet<String>(), parseExpandedThreadIds(""))
        assertEquals(listOf("a", "b"), parseExpandedThreadIds("a\n\nb\n").toList())
    }

    @Test
    fun `an id that contains the separator is not stored`() {
        assertEquals("ok", encodeExpandedThreadIds(listOf("bad\nid", "ok")))
    }

    // ---- toggleThreadExpanded ---------------------------------------------------------

    @Test
    fun `toggling expands then collapses a thread`() {
        val p = prefs()
        assertTrue(toggleThreadExpanded(p, "m2"))
        assertEquals(setOf("m2"), parseExpandedThreadIds(p[WidgetStateKey.expandedThreadIds]))
        assertFalse(toggleThreadExpanded(p, "m2"))
        assertNull(p[WidgetStateKey.expandedThreadIds])   // key removed when nothing is expanded
    }

    @Test
    fun `stale ids that no longer lead a multi-article thread are pruned`() {
        val p = prefs()
        p[WidgetStateKey.expandedThreadIds] = encodeExpandedThreadIds(listOf("gone", "s", "m2"))
        toggleThreadExpanded(p, "m2")   // collapses m2 ...
        // ... "gone" (not an article), "s" (a single) and m2 (just collapsed) are all dropped
        assertNull(p[WidgetStateKey.expandedThreadIds])
        assertTrue(toggleThreadExpanded(p, "m2"))
        assertEquals(setOf("m2"), parseExpandedThreadIds(p[WidgetStateKey.expandedThreadIds]))
    }

    @Test
    fun `toggling a lead that is not a current thread lead does not stay expanded`() {
        val p = prefs()
        assertFalse(toggleThreadExpanded(p, "s"))
        assertNull(p[WidgetStateKey.expandedThreadIds])
    }

    @Test
    fun `collapsing clears an expanded or focused article that is a hidden member`() {
        val p = prefs()
        toggleThreadExpanded(p, "m2")                       // expand
        p[WidgetStateKey.expandedArticleId] = "m1"
        p[WidgetStateKey.focusedArticleId] = "m1"
        toggleThreadExpanded(p, "m2")                       // collapse
        assertNull(p[WidgetStateKey.expandedArticleId])
        assertNull(p[WidgetStateKey.focusedArticleId])
    }

    @Test
    fun `collapsing leaves the expanded or focused lead and unrelated articles alone`() {
        val p = prefs()
        toggleThreadExpanded(p, "m2")
        p[WidgetStateKey.expandedArticleId] = "s"
        p[WidgetStateKey.focusedArticleId] = "m2"
        toggleThreadExpanded(p, "m2")
        assertEquals("s", p[WidgetStateKey.expandedArticleId])
        assertEquals("m2", p[WidgetStateKey.focusedArticleId])
    }

    @Test
    fun `expanding does not touch the tap state`() {
        val p = prefs()
        p[WidgetStateKey.expandedArticleId] = "m1"
        p[WidgetStateKey.lastTappedArticleId] = "m1"
        toggleThreadExpanded(p, "m2")
        assertEquals("m1", p[WidgetStateKey.expandedArticleId])
        assertEquals("m1", p[WidgetStateKey.lastTappedArticleId])
    }

    @Test
    fun `the remembered ids are capped and the oldest drop first`() {
        val p = prefs(withConfig = false)   // no config -> no pruning, so only the cap acts
        p[WidgetStateKey.expandedThreadIds] = encodeExpandedThreadIds((1..35).map { "x$it" })
        assertTrue(toggleThreadExpanded(p, "new"))
        val kept = parseExpandedThreadIds(p[WidgetStateKey.expandedThreadIds]).toList()
        assertEquals(EXPANDED_THREADS_MAX, kept.size)
        assertEquals("x7", kept.first())
        assertEquals("new", kept.last())
    }

    @Test
    fun `without a readable config the toggle still flips the id and prunes nothing`() {
        val p = prefs(withConfig = false)
        assertTrue(toggleThreadExpanded(p, "whatever"))
        assertEquals(setOf("whatever"), parseExpandedThreadIds(p[WidgetStateKey.expandedThreadIds]))
        assertFalse(toggleThreadExpanded(p, "whatever"))
    }

    // ---- storedThreads ----------------------------------------------------------------

    @Test
    fun `storedThreads is null when grouping is off or the config is unreadable`() {
        assertNull(storedThreads(prefs(group = false), articles))
        assertNull(storedThreads(prefs(withConfig = false), articles))
        val bad = prefs(); bad[WidgetStateKey.configJson] = "{nope"
        assertNull(storedThreads(bad, articles))
    }

    @Test
    fun `storedThreads only groups articles of feeds still in the config`() {
        val p = prefs()
        val removedFeed = articles.map { if (it.id == "m1") it.copy(feedId = "removedFeed") else it }
        val threads = storedThreads(p, removedFeed)!!
        assertTrue(threads.all { it.others.isEmpty() })
        assertEquals(setOf("m2", "s"), threads.map { it.lead.id }.toSet())
    }

    // ---- interplay with the mode switch -----------------------------------------------

    @Test
    fun `switching tap mode leaves the expanded threads alone`() {
        val p = prefs()
        toggleThreadExpanded(p, "m2")
        p[WidgetStateKey.expandedArticleId] = "m1"
        resetTapState(p)
        assertNull(p[WidgetStateKey.expandedArticleId])
        assertEquals(setOf("m2"), parseExpandedThreadIds(p[WidgetStateKey.expandedThreadIds]))
    }
}
