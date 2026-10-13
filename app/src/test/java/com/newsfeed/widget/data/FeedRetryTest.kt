package com.newsfeed.widget.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/** BUG-047: a feed that times out is retried once, nothing else is. */
class FeedRetryTest {

    // -- which failures are retried --

    @Test
    fun `a read or connect timeout is retried once`() {
        assertTrue(shouldRetryFeed(SocketTimeoutException("timeout"), attempt = 0))
        assertTrue(shouldRetryFeed(SocketTimeoutException("failed to connect to host after 15000ms"), attempt = 0))
    }

    @Test
    fun `a connect failure is retried once`() {
        assertTrue(shouldRetryFeed(ConnectException("Connection refused"), attempt = 0))
        assertTrue(shouldRetryFeed(ConnectException("Failed to connect to /10.0.0.1:443"), attempt = 0))
    }

    @Test
    fun `there is never a second retry`() {
        assertFalse(shouldRetryFeed(SocketTimeoutException("timeout"), attempt = 1))
        assertFalse(shouldRetryFeed(ConnectException("refused"), attempt = 1))
        assertFalse(shouldRetryFeed(SocketTimeoutException("timeout"), attempt = 5))
        assertFalse(shouldRetryFeed(SocketTimeoutException("timeout"), attempt = Int.MAX_VALUE))
    }

    @Test
    fun `http errors, dns, ssl, a body that is not a feed and every other failure are not retried`() {
        for (code in listOf(400, 401, 403, 404, 410, 429, 500, 502, 503, 504)) {
            assertFalse("HTTP $code", shouldRetryFeed(FeedHttpException(code), attempt = 0))
        }
        assertFalse(shouldRetryFeed(UnknownHostException("feeds.example.com"), attempt = 0))
        assertFalse(shouldRetryFeed(SSLHandshakeException("handshake failed"), attempt = 0))
        assertFalse(shouldRetryFeed(SSLException("closed"), attempt = 0))
        assertFalse(shouldRetryFeed(NotAFeedException(), attempt = 0))
        assertFalse(shouldRetryFeed(IOException("empty response body"), attempt = 0))
        assertFalse(shouldRetryFeed(SocketException("Connection reset"), attempt = 0))
        assertFalse(shouldRetryFeed(NoRouteToHostException("no route"), attempt = 0))
        assertFalse(shouldRetryFeed(IllegalStateException("parser"), attempt = 0))
        assertFalse(shouldRetryFeed(RuntimeException(), attempt = 0))
    }

    @Test
    fun `no failure and cancellation are never retried`() {
        assertFalse(shouldRetryFeed(null, attempt = 0))
        assertFalse(shouldRetryFeed(CancellationException("cancelled"), attempt = 0))
    }

    @Test
    fun `a negative attempt number is not a retry licence`() {
        assertFalse(shouldRetryFeed(SocketTimeoutException(), attempt = -1))
    }

    // -- the one retry --

    private class Counter(private val results: List<() -> List<String>>) {
        var calls = 0
        fun next(): List<String> = results[calls++]()
    }

    @Test
    fun `a first success is final and the retry never runs`() = runBlocking {
        var retryCalls = 0
        val a = fetchWithOneRetry({ listOf("a") }, { retryCalls++; listOf("b") }, delayMs = 0)
        assertEquals(listOf("a"), a.result.getOrNull())
        assertFalse(a.retried)
        assertEquals(0, retryCalls)
    }

    @Test
    fun `a timeout then a success returns the second result flagged as retried`() = runBlocking {
        val c = Counter(listOf({ throw SocketTimeoutException("timeout") }, { listOf("ok") }))
        var firstCalls = 0
        var secondCalls = 0
        val a = fetchWithOneRetry({ firstCalls++; c.next() }, { secondCalls++; c.next() }, delayMs = 0)
        assertEquals(listOf("ok"), a.result.getOrNull())
        assertTrue(a.retried)
        assertEquals(1, firstCalls)
        assertEquals(1, secondCalls)
    }

    @Test
    fun `two timeouts give the final failure after exactly two attempts`() = runBlocking {
        var calls = 0
        val second = SocketTimeoutException("second")
        val a = fetchWithOneRetry<List<String>>(
            { calls++; throw SocketTimeoutException("first") },
            { calls++; throw second },
            delayMs = 0,
        )
        assertSame("the FINAL outcome is the second attempt's", second, a.result.exceptionOrNull())
        assertTrue(a.retried)
        assertEquals(2, calls)
    }

    @Test
    fun `a timeout then a different failure reports the different failure`() = runBlocking {
        val a = fetchWithOneRetry<List<String>>(
            { throw SocketTimeoutException("first") },
            { throw FeedHttpException(503) },
            delayMs = 0,
        )
        assertTrue(a.result.exceptionOrNull() is FeedHttpException)
        assertTrue(a.retried)
    }

    @Test
    fun `an http error and a dns failure are not retried`() = runBlocking {
        for (e in listOf<Exception>(FeedHttpException(404), UnknownHostException("x"), NotAFeedException(), SSLException("x"))) {
            var retryCalls = 0
            val a = fetchWithOneRetry<List<String>>({ throw e }, { retryCalls++; listOf("b") }, delayMs = 0)
            assertSame(e, a.result.exceptionOrNull())
            assertFalse(a.retried)
            assertEquals(0, retryCalls)
        }
    }

    @Test
    fun `cancelling during the wait stops without the second attempt and without a result`() = runBlocking {
        var retryCalls = 0
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            fetchWithOneRetry<List<String>>({ throw SocketTimeoutException("first") }, { retryCalls++; listOf("b") }, delayMs = 60_000)
        }
        // The first attempt has failed and the coroutine is now waiting out the 60 s delay.
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(0, retryCalls)
        var thrown: Throwable? = null
        try { job.await() } catch (t: Throwable) { thrown = t }
        assertTrue(thrown is CancellationException)
    }

    @Test
    fun `a cancelled coroutine launched around the fetch does not start the retry`() = runBlocking {
        var retryCalls = 0
        val j = launch(start = CoroutineStart.UNDISPATCHED) {
            fetchWithOneRetry<List<String>>({ throw ConnectException("refused") }, { retryCalls++; listOf("b") }, delayMs = 60_000)
        }
        j.cancelAndJoin()
        assertEquals(0, retryCalls)
    }

    // -- the log entry --

    @Test
    fun `the log detail is prefixed only when the second attempt ran`() {
        assertEquals("SocketTimeoutException: timeout", feedLogDetail("SocketTimeoutException: timeout", retried = false))
        assertEquals("", feedLogDetail("", retried = false))
        assertEquals("retried: SocketTimeoutException: timeout", feedLogDetail("SocketTimeoutException: timeout", retried = true))
        assertEquals("retried: HTTP 503", feedLogDetail("HTTP 503", retried = true))
        // A retry that worked has no failure text of its own.
        assertEquals("retried: ok", feedLogDetail("", retried = true))
    }

    @Test
    fun `the prefixed detail still respects the log's length bound`() {
        val long = "x".repeat(500)
        assertEquals(REFRESH_LOG_MAX_DETAIL, feedLogDetail(long, retried = true).length)
        assertTrue(feedLogDetail(long, retried = true).startsWith("retried: "))
    }

    @Test
    fun `the final outcome is what is classified and logged`() {
        val first = classifyFeedLog(0, SocketTimeoutException("first"))
        val final = classifyFeedLog(0, SocketTimeoutException("second"))
        assertEquals(FeedLogStatus.TIMEOUT, first.status)
        assertEquals("retried: SocketTimeoutException: second", feedLogDetail(final.detail, retried = true))
        val ok = classifyFeedLog(7, null)
        assertEquals(FeedLogStatus.OK, ok.status)
        assertNull(ok.httpCode)
    }

    // -- the time bound --

    @Test
    fun `the retry attempt is bounded and the retry adds little to the worst case`() {
        assertEquals(1_500L, FEED_RETRY_DELAY_MS)
        assertEquals(10L, FEED_RETRY_TIMEOUT_SECONDS)
        // First attempt: the existing 25 s read timeout; plus the pause and the shorter retry.
        val firstAttemptSeconds = 25L
        val worstCaseMs = firstAttemptSeconds * 1000 + FEED_RETRY_DELAY_MS + FEED_RETRY_TIMEOUT_SECONDS * 1000
        assertEquals(36_500L, worstCaseMs)
        assertTrue(FEED_RETRY_TIMEOUT_SECONDS < firstAttemptSeconds)
    }
}
