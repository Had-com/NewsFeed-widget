package com.newsfeed.widget.data

/**
 * The footer's refresh text (BUG-035). It used to be a pure countdown, `lastRefreshTime + interval - now`,
 * so it kept saying "<1min" (or restarted after a run that fetched nothing) while no refresh happened.
 * Now it only counts down while a refresh really is expected, and says so when one is overdue.
 * Pure, unit tested; the Composable only maps [RefreshTone] to a colour.
 */

enum class RefreshTone {
    /** Normal accent colour. */
    NORMAL,
    /** Muted: the data is older than one interval, nothing is wrong that tapping cannot fix. */
    MUTED,
    /** Amber: the last attempt failed, or some feeds failed. */
    WARNING,
}

data class RefreshStatus(val text: String, val tone: RefreshTone)

/** How long past its due time a refresh may be before the footer stops promising it ("<1min"). */
const val REFRESH_GRACE_MS = 2 * 60_000L

/** Longest string this function can return; the footer text is the flexible element, this keeps it as short as the old failure text. */
const val REFRESH_STATUS_MAX_CHARS = 31

const val REFRESH_FAILED_TEXT = "⚠ refresh failed — tap to retry"

/**
 * @param lastOkMs      last time at least one feed was fetched OK (0 = never)
 * @param lastAttemptMs last run of any outcome (0 = unknown, e.g. state written by an older build)
 * @param failedAll     the last run failed for every feed
 * @param partialCount  feeds that failed in the last run while others worked (0 = none)
 * @param intervalMs    the interval the shared periodic job really runs at
 *
 * Examples: `↻ in 7min`, `↻ <1min`, `Updated 3h ago`, `⚠ 2 failed · ↻ in 7min`,
 * `⚠ refresh failed — tap to retry`.
 */
fun refreshStatusText(
    lastOkMs: Long,
    lastAttemptMs: Long,
    failedAll: Boolean,
    partialCount: Int,
    nowMs: Long,
    intervalMs: Long,
): RefreshStatus {
    // A failure flag older than the last good refresh is stale (state mixed by an older build).
    val failedNow = failedAll && (lastAttemptMs == 0L || lastAttemptMs >= lastOkMs)
    if (failedNow) return RefreshStatus(REFRESH_FAILED_TEXT, RefreshTone.WARNING)
    if (lastOkMs == 0L) return RefreshStatus("↻ now", RefreshTone.NORMAL)

    val leftMs = lastOkMs + intervalMs - nowMs
    val base: String
    var tone = RefreshTone.NORMAL
    when {
        leftMs >= 60_000L          -> base = "↻ in ${durationText(leftMs, "min")}"
        leftMs > -REFRESH_GRACE_MS -> base = "↻ <1min"
        else -> {
            // "m" not "min": with the partial-failure prefix the text must stay within REFRESH_STATUS_MAX_CHARS
            base = "Updated ${durationText(nowMs - lastOkMs, "m")} ago"
            tone = RefreshTone.MUTED
        }
    }
    if (partialCount <= 0) return RefreshStatus(base, tone)
    val n = if (partialCount > 99) "99+" else partialCount.toString()
    return RefreshStatus("⚠ $n failed · $base", RefreshTone.WARNING)
}

/** `7min`, `119min`, `3h`, `2d`, `99d+` (minutes are written with [minuteUnit]); rounds down, at least 1. */
internal fun durationText(ms: Long, minuteUnit: String): String {
    val minutes = (ms / 60_000L).coerceAtLeast(1L)
    return when {
        minutes < 120L  -> "$minutes$minuteUnit"
        minutes < 2880L -> "${minutes / 60L}h"
        minutes / 1440L > 99L -> "99d+"
        else            -> "${minutes / 1440L}d"
    }
}
