package com.newsfeed.widget.data

import kotlinx.coroutines.delay
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * One retry for a feed that timed out (BUG-047). Problem reports showed single feeds timing out in a run in which the
 * rest worked, and the same feed answering normally a moment later. Pure (no Android types), unit tested; the OkHttp
 * calls stay in NewsFeedRepository.
 *
 * What is retried: a read or connect timeout ([SocketTimeoutException]) and a failed connect ([ConnectException]).
 * What is NOT: an HTTP status (4xx / 5xx), a DNS failure (the network or the firewall is the problem, a retry only
 * burns time), TLS failures, a body that is not a feed, a parse error, cancellation.
 *
 * Time bound per feed: first attempt as before (connect 15 s, read 25 s), then [FEED_RETRY_DELAY_MS], then ONE retry
 * that is cut off after [FEED_RETRY_TIMEOUT_SECONDS] in total (read timeout AND call timeout), i.e. at most
 * 25 s + 1.5 s + 10 s for the read-timeout case the report was about. Feeds are fetched in parallel, so a run is
 * delayed by the slowest feed only, and only by those 11.5 s.
 */

/** Pause before the second attempt. */
const val FEED_RETRY_DELAY_MS = 1_500L

/** Read timeout AND whole-call limit of the second attempt (the first one keeps 25 s). */
const val FEED_RETRY_TIMEOUT_SECONDS = 10L

/** How many times a feed is retried at most. */
const val FEED_MAX_RETRIES = 1

/** [attempt] = how many retries already ran (0 = the first attempt just failed). */
fun shouldRetryFeed(exception: Throwable?, attempt: Int): Boolean {
    if (attempt !in 0 until FEED_MAX_RETRIES) return false
    return exception is SocketTimeoutException || exception is ConnectException
}

/** The result of one feed's fetch and whether the second attempt ran (then [result] is the second attempt's). */
class FeedAttempt<T>(val result: Result<T>, val retried: Boolean)

/**
 * Runs [firstAttempt]; if it failed in a way [shouldRetryFeed] allows, waits [delayMs] and runs [retryAttempt] once.
 * The final outcome is returned, never an exception from the attempts. The wait is cancellable: a cancelled
 * coroutine throws [kotlinx.coroutines.CancellationException] there and the second attempt never starts.
 */
suspend fun <T> fetchWithOneRetry(
    firstAttempt: () -> T,
    retryAttempt: () -> T,
    delayMs: Long = FEED_RETRY_DELAY_MS,
): FeedAttempt<T> {
    val first = runCatching(firstAttempt)
    if (!shouldRetryFeed(first.exceptionOrNull(), attempt = 0)) return FeedAttempt(first, retried = false)
    delay(delayMs)
    return FeedAttempt(runCatching(retryAttempt), retried = true)
}

/** The refresh log's detail: prefixed `retried: ` when the second attempt ran (`retried: ok` when it worked). */
fun feedLogDetail(detail: String, retried: Boolean): String {
    if (!retried) return detail
    return ("retried: " + detail.ifEmpty { "ok" }).take(REFRESH_LOG_MAX_DETAIL)
}
