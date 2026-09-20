package com.newsfeed.widget.config

import com.newsfeed.widget.data.FeedConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultFeedsMergeTest {

    private fun feed(url: String, name: String = url) =
        FeedConfig(feedId = url, displayName = name, feedUrl = url)

    @Test
    fun `empty existing gets all defaults`() {
        val defaults = listOf(feed("https://a.com/rss"), feed("https://b.com/rss"))
        val r = mergeDefaultFeeds(emptyList(), defaults)
        assertEquals(defaults, r.merged)
        assertEquals(2, r.addedCount)
        assertEquals(0, r.skippedCount)
    }

    @Test
    fun `same URL is not duplicated`() {
        val existing = listOf(feed("https://a.com/rss"))
        val r = mergeDefaultFeeds(existing, listOf(feed("https://a.com/rss")))
        assertEquals(existing, r.merged)
        assertEquals(0, r.addedCount)
        assertEquals(1, r.skippedCount)
    }

    @Test
    fun `comparison ignores case of scheme and host, whitespace and trailing slash`() {
        val existing = listOf(feed("https://A.com/rss/"))
        val r = mergeDefaultFeeds(existing, listOf(feed("  HTTPS://a.COM/rss  ")))
        assertEquals(0, r.addedCount)
        assertEquals(1, r.skippedCount)
    }

    @Test
    fun `path case still matters`() {
        assertEquals(false, normalizeFeedUrl("https://a.com/Feed") == normalizeFeedUrl("https://a.com/feed"))
    }

    @Test
    fun `http and https are treated as different feeds`() {
        val r = mergeDefaultFeeds(listOf(feed("http://a.com/rss")), listOf(feed("https://a.com/rss")))
        assertEquals(1, r.addedCount)
    }

    @Test
    fun `telegram forms are the same channel`() {
        val existing = listOf(feed("https://t.me/s/N12_News"))
        for (form in listOf("https://t.me/s/N12_News", "https://t.me/N12_News", "t.me/n12_news", "@N12_News", "https://t.me/s/n12_news/")) {
            val r = mergeDefaultFeeds(existing, listOf(feed(form)))
            assertEquals(form, 0, r.addedCount)
        }
    }

    @Test
    fun `adds only missing and appends in defaults order after existing order`() {
        val e1 = feed("https://z.com/rss"); val e2 = feed("https://a.com/rss")
        val d = listOf(feed("https://a.com/rss"), feed("https://c.com/rss"), feed("https://b.com/rss"))
        val r = mergeDefaultFeeds(listOf(e1, e2), d)
        assertEquals(listOf(e1, e2, d[1], d[2]), r.merged)
        assertEquals(2, r.addedCount)
        assertEquals(1, r.skippedCount)
    }

    @Test
    fun `second merge is idempotent`() {
        val d = listOf(feed("https://a.com/rss"), feed("https://b.com/rss"))
        val first = mergeDefaultFeeds(listOf(feed("https://x.com/rss")), d)
        val second = mergeDefaultFeeds(first.merged, d)
        assertEquals(first.merged, second.merged)
        assertEquals(0, second.addedCount)
        assertEquals(2, second.skippedCount)
    }

    @Test
    fun `duplicates within defaults collapse`() {
        val d = listOf(feed("https://a.com/rss"), feed("https://A.com/rss/"), feed("@X"), feed("https://t.me/s/x"))
        val r = mergeDefaultFeeds(emptyList(), d)
        assertEquals(2, r.merged.size)
        assertEquals(2, r.addedCount)
        assertEquals(2, r.skippedCount)
    }
}
