package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class ReportFilesTest {

    @Test
    fun `file name uses the local date and time with seconds`() {
        // 1_700_001_000_000 = 2023-11-14 22:30:00 UTC = 2023-11-15 00:30:00 in Jerusalem
        assertEquals("newsfeed-report-20231114-223000.txt", reportFileName(1_700_001_000_000L, ZoneId.of("UTC")))
        assertEquals("newsfeed-report-20231115-003000.txt", reportFileName(1_700_001_000_000L, ZoneId.of("Asia/Jerusalem")))
    }

    @Test
    fun `two reports in the same minute get different names`() {
        val a = reportFileName(1_700_001_000_000L, ZoneId.of("UTC"))
        val b = reportFileName(1_700_001_000_000L + 7_000L, ZoneId.of("UTC"))
        assertEquals("newsfeed-report-20231114-223007.txt", b)
        assertTrue(a != b)
    }

    @Test
    fun `only report files older than seven days are stale`() {
        val now = 1_700_000_000_000L
        val day = TimeUnit.DAYS.toMillis(1)
        val files = listOf(
            ReportFileInfo("newsfeed-report-old.txt", now - 8 * day),
            ReportFileInfo("newsfeed-report-new.txt", now - 6 * day),
            ReportFileInfo("newsfeed-report-future.txt", now + day),
            ReportFileInfo("something-else.txt", now - 30 * day),
            ReportFileInfo("newsfeed-report-old.zip", now - 30 * day),
        )
        assertEquals(listOf("newsfeed-report-old.txt"), staleReportFiles(files, now))
    }

    @Test
    fun `share summary has three lines and no address`() {
        val input = BugReportInput(
            appVersionName = "1.0", appVersionCode = 149, packageName = "p", manufacturer = "Google", model = "Pixel 7",
            androidSdk = 34, androidRelease = "14", locale = "he-IL", timeZone = "UTC", timestampMs = 0L, userNote = "",
            includeFeedAddresses = false, widgets = emptyList(),
            status = BugReportRefreshStatus(0, 0, 2, false, 15),
            refreshLog = emptyList(), crashes = emptyList(),
        )
        val s = reportShareSummary(input)
        assertEquals(3, s.lines().size)
        assertTrue(s.contains("build 149"))
        assertTrue(s.contains("2 feed(s) failed"))
        assertFalse(s.contains("http"))
        assertTrue(reportShareSummary(input.copy(status = input.status.copy(lastRefreshFailed = true))).contains("every feed"))
        assertTrue(reportShareSummary(input.copy(status = input.status.copy(failedFeedCount = 0))).contains("No feed failed"))
    }
}
