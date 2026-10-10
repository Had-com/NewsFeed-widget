package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import com.newsfeed.widget.data.FeedFetchStatus as S

/** BUG-035: a failed feed fetch must not look like "no new articles". */
class RefreshOutcomeTest {

    // -- classifying one feed's fetch --

    @Test
    fun `a fetch that returned articles is ok with articles`() {
        assertEquals(S.OK_WITH_ARTICLES, classifyFeedFetch(articleCount = 12, error = null))
    }

    @Test
    fun `a fetch that returned a valid but empty feed is ok-empty, not a failure`() {
        assertEquals(S.OK_EMPTY, classifyFeedFetch(articleCount = 0, error = null))
    }

    @Test
    fun `a non-2xx answer is an http error whatever the code`() {
        for (code in listOf(301, 403, 404, 429, 500, 503)) {
            assertEquals(S.HTTP_ERROR, classifyFeedFetch(0, FeedHttpException(code)))
        }
    }

    @Test
    fun `timeouts are classified as timeout`() {
        assertEquals(S.TIMEOUT, classifyFeedFetch(0, SocketTimeoutException("timeout")))
        // OkHttp's call timeout surfaces as an InterruptedIOException
        assertEquals(S.TIMEOUT, classifyFeedFetch(0, InterruptedIOException("timeout")))
    }

    @Test
    fun `a host that cannot be resolved is blocked (DNS refused in the background firewall state)`() {
        assertEquals(S.BLOCKED, classifyFeedFetch(0, UnknownHostException("rotter.net")))
    }

    @Test
    fun `other io and parse failures are network errors`() {
        assertEquals(S.NETWORK_ERROR, classifyFeedFetch(0, ConnectException("refused")))
        assertEquals(S.NETWORK_ERROR, classifyFeedFetch(0, SSLHandshakeException("bad cert")))
        assertEquals(S.NETWORK_ERROR, classifyFeedFetch(0, IOException("reset")))
        assertEquals(S.NETWORK_ERROR, classifyFeedFetch(0, NotAFeedException()))
        assertEquals(S.NETWORK_ERROR, classifyFeedFetch(0, IllegalStateException("parser")))
    }

    @Test
    fun `an error wins over a stale article count`() {
        assertEquals(S.HTTP_ERROR, classifyFeedFetch(articleCount = 5, error = FeedHttpException(500)))
    }

    // -- aggregating the feeds of one run --

    private fun outcome(vararg s: S) = RefreshOutcome.of(s.toList())

    @Test
    fun `all feeds ok is neither all-failed nor partial`() {
        val o = outcome(S.OK_WITH_ARTICLES, S.OK_WITH_ARTICLES, S.OK_EMPTY)
        assertFalse(o.allFailed); assertFalse(o.partialFailure); assertTrue(o.hasSuccess)
        assertEquals(0, o.failed); assertEquals(3, o.total); assertEquals(3, o.succeeded)
    }

    @Test
    fun `every feed failing is all-failed and not a success`() {
        val o = outcome(S.HTTP_ERROR, S.TIMEOUT, S.BLOCKED, S.NETWORK_ERROR)
        assertTrue(o.allFailed); assertFalse(o.partialFailure); assertFalse(o.hasSuccess)
        assertEquals(4, o.failed)
    }

    @Test
    fun `some feeds failing is a partial failure but still a success`() {
        val o = outcome(S.OK_WITH_ARTICLES, S.HTTP_ERROR, S.TIMEOUT)
        assertFalse(o.allFailed); assertTrue(o.partialFailure); assertTrue(o.hasSuccess)
        assertEquals(2, o.failed)
    }

    @Test
    fun `an ok-empty feed counts as a success, a failed feed does not`() {
        // 0 new articles is not a failure ...
        val quiet = outcome(S.OK_EMPTY, S.OK_EMPTY)
        assertFalse(quiet.allFailed); assertTrue(quiet.hasSuccess)
        // ... but a 403 / 429 must not hide as "no new articles"
        val blocked = outcome(S.HTTP_ERROR, S.HTTP_ERROR)
        assertTrue(blocked.allFailed); assertFalse(blocked.hasSuccess)
    }

    @Test
    fun `zero enabled feeds is a success with nothing to fetch`() {
        val o = outcome()
        assertEquals(0, o.total)
        assertFalse(o.allFailed); assertFalse(o.partialFailure); assertTrue(o.hasSuccess)
    }

    @Test
    fun `counts are kept per kind`() {
        val o = outcome(S.OK_WITH_ARTICLES, S.OK_EMPTY, S.HTTP_ERROR, S.NETWORK_ERROR, S.TIMEOUT, S.BLOCKED, S.BLOCKED)
        assertEquals(1, o.okWithArticles); assertEquals(1, o.okEmpty); assertEquals(1, o.httpError)
        assertEquals(1, o.networkError); assertEquals(1, o.timeout); assertEquals(2, o.blocked)
    }

    @Test
    fun `outcomes of several widgets add up`() {
        val a = outcome(S.HTTP_ERROR, S.HTTP_ERROR)               // widget 1: everything failed
        val b = outcome(S.OK_WITH_ARTICLES)                       // widget 2: fine
        val sum = a + b
        assertEquals(3, sum.total); assertEquals(2, sum.failed)
        assertTrue(sum.partialFailure); assertTrue(sum.hasSuccess); assertFalse(sum.allFailed)
        assertEquals(a, a + RefreshOutcome.of(emptyList()))
    }

    // -- what the worker returns to WorkManager --

    @Test
    fun `all feeds failed is retried with backoff for the first attempts`() {
        val o = outcome(S.BLOCKED, S.BLOCKED)
        assertEquals(WorkerVerdict.RETRY, workerVerdict(o, runAttemptCount = 0))
        assertEquals(WorkerVerdict.RETRY, workerVerdict(o, runAttemptCount = 1))
    }

    @Test
    fun `retries are bounded, the third attempt gives up until the next period`() {
        val o = outcome(S.BLOCKED, S.BLOCKED)
        assertEquals(WorkerVerdict.SUCCESS, workerVerdict(o, runAttemptCount = 2))
        assertEquals(WorkerVerdict.SUCCESS, workerVerdict(o, runAttemptCount = 3))
        assertEquals(WorkerVerdict.SUCCESS, workerVerdict(o, runAttemptCount = 100))
        assertEquals(3, MAX_REFRESH_ATTEMPTS)
    }

    @Test
    fun `partial failure, full success and no feeds never retry`() {
        assertEquals(WorkerVerdict.SUCCESS, workerVerdict(outcome(S.OK_WITH_ARTICLES, S.HTTP_ERROR), 0))
        assertEquals(WorkerVerdict.SUCCESS, workerVerdict(outcome(S.OK_EMPTY), 0))
        assertEquals(WorkerVerdict.SUCCESS, workerVerdict(outcome(), 0))
    }

    // -- a 200 page that is not a feed (captive portal, challenge page) --

    @Test
    fun `rss atom and rdf documents look like feeds`() {
        assertTrue(looksLikeFeed("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel></channel></rss>"))
        assertTrue(looksLikeFeed("<feed xmlns=\"http://www.w3.org/2005/Atom\"></feed>"))
        assertTrue(looksLikeFeed("<rdf:RDF xmlns:rdf=\"x\"></rdf:RDF>"))
        assertTrue(looksLikeFeed("<RSS><CHANNEL/></RSS>"))
    }

    @Test
    fun `an html page or an empty body is not a feed`() {
        assertFalse(looksLikeFeed("<html><body>Please log in to the Wi-Fi</body></html>"))
        assertFalse(looksLikeFeed("<!DOCTYPE html><title>Just a moment...</title>"))
        assertFalse(looksLikeFeed(""))
    }
}
