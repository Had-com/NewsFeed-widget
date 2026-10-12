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
    fun `http and https are treated as the same feed`() {
        val r = mergeDefaultFeeds(listOf(feed("http://a.com/rss")), listOf(feed("https://a.com/rss")))
        assertEquals(0, r.addedCount)
        assertEquals(1, r.skippedCount)
        assertEquals(listOf(feed("http://a.com/rss")), r.merged)
    }

    @Test
    fun `an old http Hindu entry and the new https default are one feed`() {
        val old = feed("http://www.thehindu.com/sci-tech/technology/internet/?service=rss", "AI News - The Hindu")
        val default = feed("https://www.thehindu.com/sci-tech/technology/internet/?service=rss", "The Hindu - Tech")
        val r = mergeDefaultFeeds(listOf(old), listOf(default))
        assertEquals(listOf(old), r.merged)
        assertEquals(0, r.addedCount)
        assertEquals(
            normalizeFeedUrl("HTTP://WWW.TheHindu.com/sci-tech/technology/internet/?service=rss"),
            normalizeFeedUrl("https://www.thehindu.com/sci-tech/technology/internet/?service=rss"),
        )
    }

    @Test
    fun `a default http twin inside the defaults collapses with its https form`() {
        val r = mergeDefaultFeeds(emptyList(), listOf(feed("http://a.com/rss"), feed("https://a.com/rss/")))
        assertEquals(1, r.merged.size)
        assertEquals(1, r.skippedCount)
    }

    @Test
    fun `normalize leaves other schemes and different hosts apart`() {
        assertEquals(false, normalizeFeedUrl("ftp://a.com/rss") == normalizeFeedUrl("https://a.com/rss"))
        assertEquals(false, normalizeFeedUrl("http://a.com/rss") == normalizeFeedUrl("https://b.com/rss"))
        assertEquals("https://a.com:8080/x?y=1", normalizeFeedUrl("http://A.com:8080/x?y=1"))
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
