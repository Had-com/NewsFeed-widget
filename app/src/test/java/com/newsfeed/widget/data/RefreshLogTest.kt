package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class RefreshLogTest {

    private fun feed(
        label: String = "Feed",
        status: FeedLogStatus = FeedLogStatus.OK,
        code: Int? = null,
        detail: String = "",
        url: String = "https://example.com/rss",
    ) = FeedLogEntry(label, url, status, code, articleCount = 3, elapsedMs = 120, detail = detail, widgetId = 7)

    private fun run(ts: Long, feeds: List<FeedLogEntry> = listOf(feed())) = RefreshLogEntry(
        timestamp = ts, trigger = RefreshTrigger.PERIODIC, attempt = 0, durationMs = 1500,
        widgetCount = 1, verdict = "success", feeds = feeds,
    )

    // -- status mapping --

    @Test
    fun `socket timeout maps to TIMEOUT`() {
        val c = classifyFeedLog(0, SocketTimeoutException("Read timed out"))
        assertEquals(FeedLogStatus.TIMEOUT, c.status)
        assertTrue(c.detail.startsWith("SocketTimeoutException"))
    }

    @Test
    fun `call timeout (InterruptedIOException) maps to TIMEOUT`() {
        assertEquals(FeedLogStatus.TIMEOUT, classifyFeedLog(0, InterruptedIOException("timeout")).status)
    }

    @Test
    fun `unknown host maps to NETWORK_ERROR with a dns detail`() {
        val c = classifyFeedLog(0, UnknownHostException("Unable to resolve host"))
        assertEquals(FeedLogStatus.NETWORK_ERROR, c.status)
        assertTrue(c.detail.startsWith("dns:"))
    }

    @Test
    fun `ssl and connect failures map to NETWORK_ERROR`() {
        assertEquals(FeedLogStatus.NETWORK_ERROR, classifyFeedLog(0, SSLHandshakeException("handshake")).status)
        assertEquals(FeedLogStatus.NETWORK_ERROR, classifyFeedLog(0, ConnectException("refused")).status)
    }

    @Test
    fun `http errors keep their code`() {
        for (code in listOf(404, 429, 503)) {
            val c = classifyFeedLog(0, FeedHttpException(code))
            assertEquals(FeedLogStatus.HTTP_ERROR, c.status)
            assertEquals(code, c.httpCode)
            assertEquals("HTTP $code", c.detail)
        }
    }

    @Test
    fun `an empty valid feed is OK_EMPTY and a feed with articles is OK`() {
        assertEquals(FeedLogStatus.OK_EMPTY, classifyFeedLog(0, null).status)
        assertEquals(FeedLogStatus.OK, classifyFeedLog(5, null).status)
        assertNull(classifyFeedLog(5, null).httpCode)
    }

    @Test
    fun `a non-feed 200 page is NOT_A_FEED`() {
        assertEquals(FeedLogStatus.NOT_A_FEED, classifyFeedLog(0, NotAFeedException()).status)
    }

    @Test
    fun `a parser exception maps to PARSE_ERROR and an unexpected one to OTHER`() {
        class XmlPullParserException(msg: String) : Exception(msg)
        assertEquals(FeedLogStatus.PARSE_ERROR, classifyFeedLog(0, XmlPullParserException("bad")).status)
        assertEquals(FeedLogStatus.OTHER, classifyFeedLog(0, IllegalArgumentException("bad url")).status)
        assertEquals(FeedLogStatus.NETWORK_ERROR, classifyFeedLog(0, IOException("empty response body")).status)
    }

    @Test
    fun `detail is one line and at most 120 chars`() {
        val c = classifyFeedLog(0, IOException("line one\nline two " + "x".repeat(500)))
        assertTrue(c.detail.length <= REFRESH_LOG_MAX_DETAIL)
        assertFalse(c.detail.contains('\n'))
    }

    @Test
    fun `failure flag matches the status`() {
        assertFalse(FeedLogStatus.OK.isFailure)
        assertFalse(FeedLogStatus.OK_EMPTY.isFailure)
        assertTrue(FeedLogStatus.NOT_A_FEED.isFailure)
        assertTrue(FeedLogStatus.TIMEOUT.isFailure)
    }

    // -- ring buffer --

    @Test
    fun `the 51st run evicts the oldest`() {
        var log = emptyList<RefreshLogEntry>()
        for (i in 1..51) log = appendCapped(log, run(i.toLong()))
        assertEquals(REFRESH_LOG_MAX_RUNS, log.size)
        assertEquals(2L, log.first().timestamp)
        assertEquals(51L, log.last().timestamp)
    }

    @Test
    fun `appending below the cap keeps everything in order`() {
        val log = appendCapped(appendCapped(emptyList(), run(1)), run(2))
        assertEquals(listOf(1L, 2L), log.map { it.timestamp })
    }

    // -- JSON --

    @Test
    fun `json round trip keeps every field including hebrew and special characters`() {
        val original = listOf(
            RefreshLogEntry(
                timestamp = 1_700_000_000_000L, trigger = RefreshTrigger.SETTINGS_SAVE, attempt = 2,
                durationMs = 4321, widgetCount = 3, verdict = "retry",
                feeds = listOf(
                    feed(label = "ynet חדשות \"quoted\" & <tag>", status = FeedLogStatus.HTTP_ERROR, code = 404,
                        detail = "HTTP 404 \\ back\tslash", url = "https://www.ynet.co.il/rss?q=%D7%97%D7%93%D7%A9%D7%95%D7%AA&a=1"),
                    feed(label = "OK feed"),
                ),
            ),
        )
        val back = refreshLogFromJson(refreshLogToJson(original))
        assertEquals(original, back)
        assertNull(back[0].feeds[1].httpCode)
    }

    @Test
    fun `a corrupt file reads as an empty log and a bad run is skipped`() {
        assertEquals(emptyList<RefreshLogEntry>(), refreshLogFromJson("{not json"))
        assertEquals(emptyList<RefreshLogEntry>(), refreshLogFromJson(""))
        val json = """[{"nonsense":1},{"timestamp":5,"trigger":"WHATEVER","feeds":[{"status":"???"}]}]"""
        val back = refreshLogFromJson(json)
        assertEquals(1, back.size)
        assertEquals(RefreshTrigger.OTHER, back[0].trigger)
        assertEquals(FeedLogStatus.OTHER, back[0].feeds[0].status)
    }

    // -- size bounds --

    @Test
    fun `bounds apply to label url detail and feed count`() {
        val many = (1..150).map { feed(label = "L".repeat(500), url = "https://x/" + "u".repeat(2000), detail = "d".repeat(900)) }
        val log = appendCapped(emptyList(), run(1, many))
        val e = log.single()
        assertEquals(REFRESH_LOG_MAX_FEEDS_PER_RUN, e.feeds.size)
        assertEquals(REFRESH_LOG_MAX_LABEL, e.feeds[0].label.length)
        assertEquals(REFRESH_LOG_MAX_URL, e.feeds[0].url.length)
        assertEquals(REFRESH_LOG_MAX_DETAIL, e.feeds[0].detail.length)
    }

    @Test
    fun `a full log of large runs stays small`() {
        var log = emptyList<RefreshLogEntry>()
        val many = (1..REFRESH_LOG_MAX_FEEDS_PER_RUN).map { feed(label = "L".repeat(500), url = "https://x/" + "u".repeat(2000), detail = "d".repeat(900)) }
        repeat(80) { log = appendCapped(log, run(it.toLong(), many)) }
        assertTrue(refreshLogToJson(log).length < 6_000_000)
    }

    // -- summary --

    @Test
    fun `summary counts ok and failed feeds`() {
        val e = run(1, listOf(feed(), feed(status = FeedLogStatus.OK_EMPTY), feed(status = FeedLogStatus.TIMEOUT), feed(status = FeedLogStatus.HTTP_ERROR, code = 500)))
        val s = summarizeRun(e)
        assertEquals(2, s.okFeeds)
        assertEquals(2, s.failedFeeds)
        assertTrue(s.anyFailed)
        assertEquals("2/4 feeds OK", s.text)
        assertEquals("0 feeds", summarizeRun(run(1, emptyList())).text)
        assertFalse(summarizeRun(run(1)).anyFailed)
    }

    @Test
    fun `trigger names are parsed with a safe fallback`() {
        assertEquals(RefreshTrigger.WATCHDOG, RefreshTrigger.fromName("WATCHDOG"))
        assertEquals(RefreshTrigger.OTHER, RefreshTrigger.fromName(null))
        assertEquals(RefreshTrigger.OTHER, RefreshTrigger.fromName("garbage"))
    }
}
