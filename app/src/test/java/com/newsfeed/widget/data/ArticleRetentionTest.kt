package com.newsfeed.widget.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleRetentionTest {

    private val minute = 60_000L
    private val day = 24L * 60 * minute
    private val now = 1_800_000_000_000L

    private fun art(id: String, feed: String, ageMs: Long) = ArticleItem(
        id = id, feedId = feed, feedName = feed, title = "title $id",
        publishedAt = now - ageMs, isRead = false,
    )

    private fun cfg(days: Int = 0, minutes: Int = 0) =
        WidgetConfig(widgetId = 1, retentionDays = days, retentionMinutes = minutes)

    // ---- the option list ---------------------------------------------------------------

    @Test
    fun `the option list starts with 15 minutes and runs shortest to longest with Forever last`() {
        assertEquals("15 minutes", RETENTION_OPTIONS.first().label)
        assertEquals(15, RETENTION_OPTIONS.first().minutes)
        assertEquals(0, RETENTION_OPTIONS.first().days)
        assertEquals(
            listOf("15 minutes", "1 day", "3 days", "1 week", "2 weeks", "1 month", "Forever"),
            RETENTION_OPTIONS.map { it.label },
        )
        val windows = RETENTION_OPTIONS.map { retentionWindowMs(cfg(it.days, it.minutes)) }
        assertEquals(windows.dropLast(1).sorted(), windows.dropLast(1))            // ascending ...
        assertTrue(windows.dropLast(1).all { it > 0 })
        assertEquals(0L, windows.last())                                            // ... then unlimited
    }

    @Test
    fun `the day options keep their old stored values`() {
        assertEquals(
            listOf(1, 3, 7, 14, 30),
            RETENTION_OPTIONS.filter { it.minutes == 0 && it.days > 0 }.map { it.days },
        )
        assertEquals(0, RETENTION_OPTIONS.last().days)   // Forever = retentionDays 0, as before
        assertEquals(0, RETENTION_OPTIONS.last().minutes)
    }

    @Test
    fun `an option is applied to and read back from a config`() {
        for (o in RETENTION_OPTIONS) {
            val c = WidgetConfig(widgetId = 1, retentionDays = 14, retentionMinutes = 15).withRetention(o)
            assertEquals(o.label, c.retentionOption().label)
        }
        // switching from 15 minutes to 3 days drops the minutes
        val c = cfg(minutes = 15).withRetention(RETENTION_OPTIONS.first { it.days == 3 })
        assertEquals(0, c.retentionMinutes)
        assertEquals(3, c.retentionDays)
    }

    @Test
    fun `an unknown stored value still shows a label`() {
        assertEquals("5 days", cfg(days = 5).retentionOption().label)
        assertEquals("20 minutes", cfg(minutes = 20).retentionOption().label)
    }

    // ---- the saved config --------------------------------------------------------------

    @Test
    fun `config JSON saved before retentionMinutes existed decodes to the same retention`() {
        // Default Json (no ignoreUnknownKeys), exactly what WidgetContent uses.
        val three = Json.decodeFromString<WidgetConfig>("""{"widgetId":7,"retentionDays":3,"tapMode":"focus"}""")
        assertEquals(3, three.retentionDays)
        assertEquals(0, three.retentionMinutes)
        assertEquals(3 * day, retentionWindowMs(three))
        val forever = Json.decodeFromString<WidgetConfig>("""{"widgetId":7}""")
        assertEquals(0L, retentionWindowMs(forever))                 // the default is unchanged: Forever
        assertEquals("Forever", forever.retentionOption().label)
        val month = Json.decodeFromString<WidgetConfig>("""{"widgetId":7,"retentionDays":30}""")
        assertEquals(30 * day, retentionWindowMs(month))
    }

    @Test
    fun `the default config does not write the field and 15 minutes survives a round trip`() {
        assertFalse(Json.encodeToString(WidgetConfig(widgetId = 3)).contains("retentionMinutes"))
        val json = Json.encodeToString(cfg(minutes = 15))
        assertTrue(json.contains("\"retentionMinutes\":15"))
        assertEquals(15, Json.decodeFromString<WidgetConfig>(json).retentionMinutes)
        val lenient = Json { ignoreUnknownKeys = true }
        assertEquals(15, lenient.decodeFromString<WidgetConfig>(json).retentionMinutes)
    }

    @Test
    fun `a day option saved by the settings screen writes no minutes`() {
        val c = WidgetConfig(widgetId = 3).withRetention(RETENTION_OPTIONS.first { it.days == 7 })
        val json = Json.encodeToString(c)
        assertTrue(json.contains("\"retentionDays\":7"))
        assertFalse(json.contains("retentionMinutes"))
    }

    @Test
    fun `minutes win over days when both are present`() {
        assertEquals(15 * minute, retentionWindowMs(cfg(days = 3, minutes = 15)))
    }

    // ---- the cutoff --------------------------------------------------------------------

    @Test
    fun `15 minutes keeps an article of 14 minutes 59 seconds and drops one of 15 minutes 1 second`() {
        val c = cfg(minutes = 15)
        val kept = art("a", "f", 14 * minute + 59_000)
        val edge = art("b", "f", 15 * minute)                       // exactly 15:00: kept (inclusive, as before)
        val dropped = art("c", "f", 15 * minute + 1_000)
        val fresh = art("d", "f", 0)
        assertEquals(listOf("a", "b", "d"), applyRetention(listOf(kept, edge, dropped, fresh), c, now).map { it.id })
    }

    @Test
    fun `day options use whole days and keep the old boundary`() {
        val c = cfg(days = 1)
        assertEquals(now - day, retentionCutoff(c, now))
        assertEquals(listOf("in"), applyRetention(listOf(art("in", "f", day - 1), art("out", "f", day + 1)), c, now).map { it.id })
    }

    @Test
    fun `forever applies no cutoff at all`() {
        val all = listOf(art("old", "f", 400 * day), art("new", "f", 0))
        assertEquals(0L, retentionCutoff(cfg(), now))
        assertEquals(all, applyRetention(all, cfg(), now))
    }

    @Test
    fun `the cutoff depends only on the config and the given time`() {
        val c = cfg(minutes = 15)
        assertEquals(retentionCutoff(c, now), retentionCutoff(c, now))
        assertEquals(now - 15 * minute, retentionCutoff(c, now))
        assertEquals(now + 7 - 15 * minute, retentionCutoff(c, now + 7))
    }

    // ---- the per-feed guarantee --------------------------------------------------------

    @Test
    fun `the per-feed guarantee still protects a quiet feed inside the 300 cap after the age filter`() {
        // 320 fresh articles from a busy feed + 3 fresh from a quiet one, all within 15 minutes:
        // the cap trims the busy feed but the quiet feed keeps its (up to 10) newest.
        val busy = (0 until 320).map { art("b$it", "busy", 1_000L + it) }
        val quiet = (0 until 3).map { art("q$it", "quiet", 14 * minute + it) }
        val kept = applyRetention(busy + quiet, cfg(minutes = 15), now)
        assertEquals(323, kept.size)
        val out = retainWithPerFeedGuarantee(kept, cap = 300, minPerFeed = 10)
        assertEquals(300, out.size)
        assertEquals(setOf("q0", "q1", "q2"), out.filter { it.feedId == "quiet" }.map { it.id }.toSet())
    }

    @Test
    fun `a feed whose newest article is older than the window is empty - the guarantee only protects the cap`() {
        val c = cfg(minutes = 15)
        val articles = listOf(art("f1", "fast", 60_000), art("s1", "slow", 20 * minute), art("s2", "slow", 3 * 60 * minute))
        val out = retainWithPerFeedGuarantee(applyRetention(articles, c, now), cap = 300, minPerFeed = 10)
        assertEquals(listOf("f1"), out.map { it.id })
    }

    // ---- the render-time filter --------------------------------------------------------

    @Test
    fun `displayThreadsFor hides articles older than the window without waiting for a refresh`() {
        val feeds = listOf(FeedConfig(feedId = "f", displayName = "f"))
        val articles = listOf(art("fresh", "f", 5 * minute), art("old", "f", 16 * minute), art("older", "f", 3 * day))
        val c = WidgetConfig(widgetId = 1, sortOrder = SortOrder.NEWEST.key, feeds = feeds, retentionMinutes = 15)
        assertEquals(listOf("fresh"), displayThreadsFor(articles, c, now).map { it.lead.id })
        // 12 minutes later the same stored list shows nothing (the worker has not run yet)
        assertEquals(emptyList<String>(), displayThreadsFor(articles, c, now + 12 * minute).map { it.lead.id })
        // Forever shows everything
        val forever = c.copy(retentionMinutes = 0)
        assertEquals(3, displayThreadsFor(articles, forever, now).size)
    }
}
