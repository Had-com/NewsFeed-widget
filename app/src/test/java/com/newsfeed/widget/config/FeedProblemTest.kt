package com.newsfeed.widget.config

import com.newsfeed.widget.data.FeedLogEntry
import com.newsfeed.widget.data.FeedLogStatus
import com.newsfeed.widget.data.RefreshLogEntry
import com.newsfeed.widget.data.RefreshTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The amber "Not working: ..." line under a feed in the settings list. */
class FeedProblemTest {

    private val url = "https://dead.example.com/feed"

    private fun feed(
        status: FeedLogStatus, u: String = url, code: Int? = null, detail: String = "", widgetId: Int = 1,
    ) = FeedLogEntry(label = "x", url = u, status = status, httpCode = code, detail = detail, widgetId = widgetId)

    private fun run(ts: Long, vararg feeds: FeedLogEntry) =
        RefreshLogEntry(ts, RefreshTrigger.PERIODIC, 0, 100L, 1, "success", feeds.toList())

    private fun ok(u: String = url) = feed(FeedLogStatus.OK, u)
    private fun bad(u: String = url, code: Int = 403) = feed(FeedLogStatus.HTTP_ERROR, u, code)

    @Test
    fun `failed in both of the last two runs shows the reason`() {
        val log = listOf(run(1, bad()), run(2, bad()))
        assertEquals("Not working: HTTP 403", feedProblemText(log, url))
    }

    @Test
    fun `the order of the entries does not matter, the newest runs decide`() {
        val newestFirst = listOf(run(3, bad(code = 404)), run(2, bad(code = 404)), run(1, ok()))
        assertEquals("Not working: HTTP 404", feedProblemText(newestFirst, url))
        assertEquals("Not working: HTTP 404", feedProblemText(newestFirst.reversed(), url))
    }

    @Test
    fun `one failure is not enough`() {
        assertNull(feedProblemText(listOf(run(1, ok()), run(2, bad())), url))
        assertNull(feedProblemText(listOf(run(1, bad())), url))
    }

    @Test
    fun `a success in the latest run clears it`() {
        assertNull(feedProblemText(listOf(run(1, bad()), run(2, bad()), run(3, ok())), url))
    }

    @Test
    fun `a success in the previous run means it is not a repeated failure`() {
        assertNull(feedProblemText(listOf(run(1, bad()), run(2, ok()), run(3, bad())), url))
    }

    @Test
    fun `an old failure streak behind a success is ignored`() {
        assertNull(feedProblemText(listOf(run(1, bad()), run(2, bad()), run(3, ok()), run(4, ok())), url))
    }

    @Test
    fun `no history shows nothing`() {
        assertNull(feedProblemText(emptyList(), url))
        assertNull(feedProblemText(listOf(run(1), run(2)), url))
        assertNull(feedProblemText(listOf(run(1, bad("https://other.example.com/rss")), run(2, bad("https://other.example.com/rss"))), url))
    }

    @Test
    fun `an empty ok feed is not a problem`() {
        val empty = feed(FeedLogStatus.OK_EMPTY)
        assertNull(feedProblemText(listOf(run(1, empty), run(2, empty)), url))
    }

    @Test
    fun `runs that do not mention the feed are skipped`() {
        val log = listOf(run(1, bad()), run(2, ok("https://other.example.com/rss")), run(3, bad()))
        assertEquals("Not working: HTTP 403", feedProblemText(log, url))
    }

    @Test
    fun `feeds are matched by address, ignoring scheme case, trailing slash and http versus https`() {
        val stored = feed(FeedLogStatus.TIMEOUT, "http://Dead.Example.com/feed/")
        val log = listOf(run(1, stored), run(2, stored))
        assertEquals("Not working: timed out", feedProblemText(log, "https://dead.example.com/feed"))
        assertEquals("Not working: timed out", feedProblemText(log, "http://dead.example.com/feed"))
    }

    @Test
    fun `a feed that works in one widget is not reported`() {
        val log = listOf(
            run(1, bad(), ok()),
            run(2, bad(), ok()),
        )
        assertNull(feedProblemText(log, url))
        val allBad = listOf(run(1, bad(), bad()), run(2, bad(), bad()))
        assertEquals("Not working: HTTP 403", feedProblemText(allBad, url))
    }

    @Test
    fun `every status maps to a short reason`() {
        fun text(f: FeedLogEntry) = feedProblemText(listOf(run(1, f), run(2, f)), url)
        assertEquals("Not working: HTTP 503", text(feed(FeedLogStatus.HTTP_ERROR, code = 503)))
        assertEquals("Not working: HTTP error", text(feed(FeedLogStatus.HTTP_ERROR, code = null)))
        assertEquals("Not working: timed out", text(feed(FeedLogStatus.TIMEOUT)))
        assertEquals("Not working: can't connect", text(feed(FeedLogStatus.NETWORK_ERROR, detail = "SSLHandshakeException: x")))
        assertEquals("Not working: not a feed", text(feed(FeedLogStatus.NOT_A_FEED)))
        assertEquals("Not working: can't read it", text(feed(FeedLogStatus.PARSE_ERROR)))
        assertEquals("Not working: blocked", text(feed(FeedLogStatus.BLOCKED)))
        assertEquals("Not working: failed", text(feed(FeedLogStatus.OTHER)))
    }

    @Test
    fun `a cleartext failure and an https upgrade failure are named`() {
        fun text(d: String) = feedProblemText(
            listOf(run(1, feed(FeedLogStatus.NETWORK_ERROR, detail = d)), run(2, feed(FeedLogStatus.NETWORK_ERROR, detail = d))), url,
        )
        assertEquals("Not working: cleartext blocked",
            text("UnknownServiceException: CLEARTEXT communication to x not permitted by network security policy"))
        assertEquals("Not working: no https version",
            text("https upgrade of http feed: SSLHandshakeException: x"))
    }

    @Test
    fun `the latest run decides which reason is shown`() {
        val log = listOf(run(1, feed(FeedLogStatus.TIMEOUT)), run(2, bad(code = 500)))
        assertEquals("Not working: HTTP 500", feedProblemText(log, url))
    }

    @Test
    fun `the line is never longer than 40 characters`() {
        for (s in FeedLogStatus.entries) {
            val f = feed(s, code = 123456789, detail = "x".repeat(300))
            val t = feedProblemText(listOf(run(1, f), run(2, f)), url)
            if (t != null) assertTrue(t, t.length <= 40)
        }
    }

    @Test
    fun `a blank address has no problem`() {
        assertNull(feedProblemText(listOf(run(1, bad("")), run(2, bad(""))), ""))
        assertNull(feedProblemText(listOf(run(1, bad()), run(2, bad())), "   "))
    }
}
