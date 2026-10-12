package com.newsfeed.widget.config

import com.newsfeed.widget.data.FeedLogEntry
import com.newsfeed.widget.data.FeedLogStatus
import com.newsfeed.widget.data.RefreshLogEntry

private const val PROBLEM_PREFIX = "Not working: "
private const val PROBLEM_MAX_CHARS = 40

/**
 * The amber line under a feed's name in the Settings feed list (report #17: a dead default feed kept the footer at
 * `⚠ 2 failed` and nothing said which feeds). Non-null only when the feed failed in EACH of its last two recorded
 * runs (the two newest runs that contain it), so one flaky refresh stays silent and a success clears the line.
 * Entries may come in any order (newest first or oldest first). Feeds are matched by address with
 * [normalizeFeedUrl]. A feed that appears in a run more than once (placed in several widgets) failed in that run only
 * if every occurrence failed. Pure, unit tested.
 */
fun feedProblemText(entries: List<RefreshLogEntry>, feedUrl: String): String? {
    val key = normalizeFeedUrl(feedUrl)
    if (key.isBlank()) return null
    val lastTwo = entries.sortedByDescending { it.timestamp }
        .mapNotNull { run -> run.feeds.filter { normalizeFeedUrl(it.url) == key }.takeIf { it.isNotEmpty() } }
        .take(2)
    if (lastTwo.size < 2) return null
    if (!lastTwo.all { occurrences -> occurrences.all { it.status.isFailure } }) return null
    return (PROBLEM_PREFIX + problemReason(lastTwo.first().first())).take(PROBLEM_MAX_CHARS)
}

private fun problemReason(f: FeedLogEntry): String = when (f.status) {
    FeedLogStatus.HTTP_ERROR -> f.httpCode?.let { "HTTP $it" } ?: "HTTP error"
    FeedLogStatus.TIMEOUT -> "timed out"
    FeedLogStatus.NETWORK_ERROR -> when {
        f.detail.contains("CLEARTEXT", ignoreCase = true) -> "cleartext blocked"
        f.detail.startsWith("https upgrade of http feed") -> "no https version"
        else -> "can't connect"
    }
    FeedLogStatus.NOT_A_FEED -> "not a feed"
    FeedLogStatus.PARSE_ERROR -> "can't read it"
    FeedLogStatus.BLOCKED -> "blocked"
    FeedLogStatus.OTHER -> "failed"
    FeedLogStatus.OK, FeedLogStatus.OK_EMPTY -> "failed"
}
