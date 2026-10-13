package com.newsfeed.widget.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Local refresh log (bug logger phase 1b, docs/superpowers/specs/2026-09-10-bug-logger-design.md).
 * The pure part: the model, the per-feed status mapping, the ring buffer rule, the JSON and the
 * summary for the UI. No Android types except org.json (real implementation on the unit-test classpath),
 * so everything here is unit tested. The file I/O is in [RefreshLogStore].
 *
 * The log only ever records what a refresh did; it never changes what a refresh does.
 */

/** How the run was enqueued. */
enum class RefreshTrigger(val label: String) {
    PERIODIC("periodic"),
    MANUAL_TAP("tap"),
    WATCHDOG("watchdog"),
    SETTINGS_SAVE("settings save"),
    WIDGET_ADDED("widget added"),
    /** The idle wake-up alarm started an expedited run (BUG-046). Stored by name; older logs simply have none. */
    IDLE_ALARM("idle alarm"),
    OTHER("other");

    companion object {
        fun fromName(name: String?): RefreshTrigger = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/** What happened to one feed in one run. */
enum class FeedLogStatus {
    OK,
    /** A valid feed without items: not a failure. */
    OK_EMPTY,
    HTTP_ERROR,
    TIMEOUT,
    /** Unknown host (detail starts with "dns"), refused connection, TLS failure ... */
    NETWORK_ERROR,
    /** Reserved: a feed known to be blocked by the OS firewall. Not produced yet (no cheap signal). */
    BLOCKED,
    /** HTTP 200 whose body is not an RSS / Atom document. */
    NOT_A_FEED,
    PARSE_ERROR,
    OTHER;

    val isFailure: Boolean get() = this != OK && this != OK_EMPTY
}

data class FeedLogEntry(
    val label: String,
    /** Stored in full; the report builder decides whether it is shown. */
    val url: String,
    val status: FeedLogStatus,
    val httpCode: Int? = null,
    val articleCount: Int = 0,
    val elapsedMs: Long = 0L,
    val detail: String = "",
    /** The widget whose config contained this feed (0 = unknown). */
    val widgetId: Int = 0,
)

data class RefreshLogEntry(
    val timestamp: Long,
    val trigger: RefreshTrigger,
    /** WorkManager's runAttemptCount: 0 on the first run. */
    val attempt: Int,
    val durationMs: Long,
    val widgetCount: Int,
    /** "success" or "retry". */
    val verdict: String,
    val feeds: List<FeedLogEntry>,
)

const val REFRESH_LOG_MAX_RUNS = 50
const val REFRESH_LOG_MAX_FEEDS_PER_RUN = 100
const val REFRESH_LOG_MAX_LABEL = 80
const val REFRESH_LOG_MAX_URL = 500
const val REFRESH_LOG_MAX_DETAIL = 120

/** The status of one feed's fetch, with the extra facts the log keeps. */
data class FeedLogClassification(val status: FeedLogStatus, val httpCode: Int?, val detail: String)

/** Classifies one feed's fetch like [classifyFeedFetch], but keeps WHY: the status, the HTTP code, the exception. */
fun classifyFeedLog(articleCount: Int, error: Throwable?): FeedLogClassification {
    if (error == null) {
        return FeedLogClassification(if (articleCount > 0) FeedLogStatus.OK else FeedLogStatus.OK_EMPTY, null, "")
    }
    return when (error) {
        is FeedHttpException -> FeedLogClassification(FeedLogStatus.HTTP_ERROR, error.code, "HTTP ${error.code}")
        is NotAFeedException -> FeedLogClassification(FeedLogStatus.NOT_A_FEED, null, describeError(error))
        is UnknownHostException ->
            FeedLogClassification(FeedLogStatus.NETWORK_ERROR, null, "dns: " + describeError(error))
        is SocketTimeoutException, is InterruptedIOException ->
            FeedLogClassification(FeedLogStatus.TIMEOUT, null, describeError(error))
        // XmlPullParserException is matched by name so this stays free of android.jar types.
        else -> when {
            error.javaClass.simpleName == "XmlPullParserException" ->
                FeedLogClassification(FeedLogStatus.PARSE_ERROR, null, describeError(error))
            error is IOException -> FeedLogClassification(FeedLogStatus.NETWORK_ERROR, null, describeError(error))
            else -> FeedLogClassification(FeedLogStatus.OTHER, null, describeError(error))
        }
    }
}

/** `SimpleName: short message`, at most [REFRESH_LOG_MAX_DETAIL] chars, on one line. */
fun describeError(t: Throwable): String {
    val name = t.javaClass.simpleName.ifEmpty { t.javaClass.name }
    val msg = t.message?.let { oneLine(it) }.orEmpty()
    return (if (msg.isEmpty()) name else "$name: $msg").take(REFRESH_LOG_MAX_DETAIL)
}

private fun oneLine(s: String): String = s.replace(Regex("\\s+"), " ").trim()

/** Applies the size bounds so a pathological value can never grow the file. */
fun RefreshLogEntry.bounded(): RefreshLogEntry = copy(
    feeds = feeds.take(REFRESH_LOG_MAX_FEEDS_PER_RUN).map { f ->
        f.copy(
            label = oneLine(f.label).take(REFRESH_LOG_MAX_LABEL),
            url = f.url.take(REFRESH_LOG_MAX_URL),
            detail = oneLine(f.detail).take(REFRESH_LOG_MAX_DETAIL),
        )
    },
)

/** Ring buffer: appends [entry] and keeps only the newest [max] runs (the oldest are evicted first). */
fun appendCapped(existing: List<RefreshLogEntry>, entry: RefreshLogEntry, max: Int = REFRESH_LOG_MAX_RUNS): List<RefreshLogEntry> =
    (existing + entry.bounded()).takeLast(max)

fun refreshLogToJson(entries: List<RefreshLogEntry>): String {
    val arr = JSONArray()
    entries.forEach { e ->
        arr.put(JSONObject().apply {
            put("timestamp", e.timestamp)
            put("trigger", e.trigger.name)
            put("attempt", e.attempt)
            put("durationMs", e.durationMs)
            put("widgetCount", e.widgetCount)
            put("verdict", e.verdict)
            put("feeds", JSONArray().also { fa ->
                e.feeds.forEach { f ->
                    fa.put(JSONObject().apply {
                        put("label", f.label)
                        put("url", f.url)
                        put("status", f.status.name)
                        if (f.httpCode != null) put("httpCode", f.httpCode)
                        put("articleCount", f.articleCount)
                        put("elapsedMs", f.elapsedMs)
                        put("detail", f.detail)
                        put("widgetId", f.widgetId)
                    })
                }
            })
        })
    }
    return arr.toString()
}

/** Never throws: a damaged file reads as an empty log; a damaged run is skipped. */
fun refreshLogFromJson(json: String): List<RefreshLogEntry> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).mapNotNull { i ->
        runCatching {
            val o = arr.getJSONObject(i)
            val fa = o.optJSONArray("feeds") ?: JSONArray()
            RefreshLogEntry(
                timestamp = o.getLong("timestamp"),
                trigger = RefreshTrigger.fromName(o.optString("trigger")),
                attempt = o.optInt("attempt", 0),
                durationMs = o.optLong("durationMs", 0L),
                widgetCount = o.optInt("widgetCount", 0),
                verdict = o.optString("verdict", ""),
                feeds = (0 until fa.length()).map { j ->
                    val f = fa.getJSONObject(j)
                    FeedLogEntry(
                        label = f.optString("label", ""),
                        url = f.optString("url", ""),
                        status = runCatching { FeedLogStatus.valueOf(f.optString("status")) }
                            .getOrDefault(FeedLogStatus.OTHER),
                        httpCode = if (f.has("httpCode") && !f.isNull("httpCode")) f.getInt("httpCode") else null,
                        articleCount = f.optInt("articleCount", 0),
                        elapsedMs = f.optLong("elapsedMs", 0L),
                        detail = f.optString("detail", ""),
                        widgetId = f.optInt("widgetId", 0),
                    )
                },
            )
        }.getOrNull()
    }
}.getOrDefault(emptyList())

/** Counts for one run, for the list row in Settings ("6/8 feeds OK"). */
data class RefreshLogSummary(val okFeeds: Int, val failedFeeds: Int) {
    val totalFeeds: Int get() = okFeeds + failedFeeds
    val anyFailed: Boolean get() = failedFeeds > 0
    /** `6/8 feeds OK`, `0 feeds` for a run without feeds. */
    val text: String get() = if (totalFeeds == 0) "0 feeds" else "$okFeeds/$totalFeeds feeds OK"
}

fun summarizeRun(entry: RefreshLogEntry): RefreshLogSummary {
    val failed = entry.feeds.count { it.status.isFailure }
    return RefreshLogSummary(okFeeds = entry.feeds.size - failed, failedFeeds = failed)
}
