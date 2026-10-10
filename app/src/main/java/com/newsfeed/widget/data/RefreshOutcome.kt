package com.newsfeed.widget.data

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Honest refresh outcomes (BUG-035). Before, a feed that answered 403 / 429 / 5xx produced an empty
 * list that the merge treated as "no new articles", `lastRefreshTime` was overwritten even when every
 * feed had failed, and the worker returned success. Pure (no Android types), unit tested.
 */

/** The server answered, but not with 2xx. */
class FeedHttpException(val code: Int) : IOException("HTTP $code")

/** HTTP 200 whose body is not an RSS / Atom document (captive portal, challenge page). */
class NotAFeedException : IOException("response is not a feed")

enum class FeedFetchStatus {
    /** Fetched and parsed, at least one article. */
    OK_WITH_ARTICLES,
    /** Fetched and parsed a valid feed that has no articles: NOT a failure. */
    OK_EMPTY,
    /** Answered with a non-2xx status (403, 429, 5xx ...). */
    HTTP_ERROR,
    /** Connection refused / reset, TLS failure, unusable body or any other I/O or parse failure. */
    NETWORK_ERROR,
    /** Connect or read timed out. */
    TIMEOUT,
    /** The host name could not be resolved: offline, or DNS refused (background firewall state). */
    BLOCKED,
}

/** Classifies one feed's fetch from what it produced: [error] if it threw, else its article count. */
fun classifyFeedFetch(articleCount: Int, error: Throwable?): FeedFetchStatus = when (error) {
    null -> if (articleCount > 0) FeedFetchStatus.OK_WITH_ARTICLES else FeedFetchStatus.OK_EMPTY
    is FeedHttpException -> FeedFetchStatus.HTTP_ERROR
    is UnknownHostException -> FeedFetchStatus.BLOCKED
    is SocketTimeoutException, is InterruptedIOException -> FeedFetchStatus.TIMEOUT
    else -> FeedFetchStatus.NETWORK_ERROR
}

/** True for an RSS 2 / Atom / RDF document, even one without items. */
fun looksLikeFeed(body: String): Boolean {
    val head = body.take(4096).lowercase()
    return "<rss" in head || "<feed" in head || "<rdf:rdf" in head || "<channel" in head
}

/** Per-kind counts of one run's feeds. A failed feed keeps its previously stored articles. */
data class RefreshOutcome(
    val okWithArticles: Int = 0,
    val okEmpty: Int = 0,
    val httpError: Int = 0,
    val networkError: Int = 0,
    val timeout: Int = 0,
    val blocked: Int = 0,
) {
    val total: Int get() = okWithArticles + okEmpty + httpError + networkError + timeout + blocked
    val failed: Int get() = httpError + networkError + timeout + blocked
    val succeeded: Int get() = okWithArticles + okEmpty

    /** Every enabled feed failed (needs at least one feed). */
    val allFailed: Boolean get() = total > 0 && failed == total

    /** Some, but not all, feeds failed. */
    val partialFailure: Boolean get() = failed in 1 until total

    /**
     * The run counts as a real refresh: at least one feed was fetched, or there was nothing to fetch
     * (no enabled feed). Only then is `lastRefreshTime` advanced.
     */
    val hasSuccess: Boolean get() = total == 0 || succeeded > 0

    operator fun plus(o: RefreshOutcome) = RefreshOutcome(
        okWithArticles + o.okWithArticles, okEmpty + o.okEmpty, httpError + o.httpError,
        networkError + o.networkError, timeout + o.timeout, blocked + o.blocked,
    )

    companion object {
        fun of(statuses: List<FeedFetchStatus>) = RefreshOutcome(
            okWithArticles = statuses.count { it == FeedFetchStatus.OK_WITH_ARTICLES },
            okEmpty = statuses.count { it == FeedFetchStatus.OK_EMPTY },
            httpError = statuses.count { it == FeedFetchStatus.HTTP_ERROR },
            networkError = statuses.count { it == FeedFetchStatus.NETWORK_ERROR },
            timeout = statuses.count { it == FeedFetchStatus.TIMEOUT },
            blocked = statuses.count { it == FeedFetchStatus.BLOCKED },
        )
    }
}

enum class WorkerVerdict { SUCCESS, RETRY }

/** A run is attempted at most this many times per period (the first run plus two backed-off retries). */
const val MAX_REFRESH_ATTEMPTS = 3

/**
 * What the worker returns. Only "every feed failed" is retried (WorkManager's exponential backoff),
 * and only [MAX_REFRESH_ATTEMPTS] times: after that the run ends as a success so the next period
 * handles it and there is no endless quick retry loop. [runAttemptCount] is 0 on the first run.
 */
fun workerVerdict(outcome: RefreshOutcome, runAttemptCount: Int): WorkerVerdict =
    if (outcome.allFailed && runAttemptCount + 1 < MAX_REFRESH_ATTEMPTS) WorkerVerdict.RETRY
    else WorkerVerdict.SUCCESS
