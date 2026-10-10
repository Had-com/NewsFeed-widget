package com.newsfeed.widget.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Pure helpers around the report FILE that Settings writes and shares (see BugReport.kt for its content).
 * No Android types, unit tested.
 */

/** Report files in the cache are deleted after this long. */
val REPORT_FILE_MAX_AGE_MS: Long = TimeUnit.DAYS.toMillis(7)

/** `newsfeed-report-20261010-140352.txt` (local time of [zone], with seconds so two reports in one minute do not overwrite each other). */
fun reportFileName(nowMs: Long, zone: ZoneId): String =
    "newsfeed-report-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(zone)
        .format(Instant.ofEpochMilli(nowMs)) + ".txt"

/** A file in the reports folder: its name and last-modified time. */
data class ReportFileInfo(val name: String, val lastModifiedMs: Long)

/**
 * Which report files to delete: only `newsfeed-report-*.txt` files older than [maxAgeMs]. A file dated in the
 * future (clock change) is kept. Anything else in the folder is never touched.
 */
fun staleReportFiles(files: List<ReportFileInfo>, nowMs: Long, maxAgeMs: Long = REPORT_FILE_MAX_AGE_MS): List<String> =
    files.filter {
        it.name.startsWith("newsfeed-report-") && it.name.endsWith(".txt") &&
            nowMs - it.lastModifiedMs > maxAgeMs
    }.map { it.name }

/** The three-line text that accompanies the attached report (the share sheet's message body). */
fun reportShareSummary(input: BugReportInput): String {
    val s = input.status
    val failedLine = when {
        s.lastRefreshFailed -> "The last refresh failed for every feed."
        s.failedFeedCount > 0 -> "${s.failedFeedCount} feed(s) failed in the last refresh."
        else -> "No feed failed in the last refresh."
    }
    return "NewsFeed ${input.appVersionName} (build ${input.appVersionCode}) on ${input.manufacturer} ${input.model}, " +
        "Android ${input.androidRelease}\n$failedLine\nThe full report is attached as a text file."
}
