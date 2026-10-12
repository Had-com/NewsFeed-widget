package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The report checkbox controls the WHOLE feed list: with it off only the app's own default feeds are named, the
 * user's own feeds are "feed #1", "feed #2" ... (same number everywhere in the report) and no custom name, address
 * or host of them appears.
 */
class BugReportOwnFeedsTest {

    private val defaults = listOf(
        BugReportDefaultFeed("ynet - מבזקים", "https://www.ynet.co.il/integration/StoryRss1854.xml"),
        BugReportDefaultFeed("The Hindu - Tech", "https://www.thehindu.com/sci-tech/technology/internet/?service=rss"),
        BugReportDefaultFeed("חדשות N12", "https://t.me/s/N12_News"),
    )

    // Custom (the user's own) feeds, with Hebrew names and their own hosts.
    private val villageName = "חדשות הכפר שלי"
    private val villageUrl = "https://village-news.example.org/rss?token=S3CR3T&q=x"
    private val blogName = "הבלוג הפרטי של דנה"
    private val blogUrl = "http://dana-blog.example.net/feed"
    private val offName = "פיד כבוי"
    private val offUrl = "https://quiet.example.com/rss"

    private fun widget(id: Int, feeds: List<BugReportFeed>) = BugReportWidget(
        id = id, tapMode = "expand", filter = "all", sortOrder = "by_feed", theme = "glamer", themeVariant = "light",
        refreshIntervalMinutes = 15, retentionDays = 0, retentionMinutes = 0, groupStories = true,
        articleLength = "medium", fontSize = 1.0f, articleFontSize = 1.0f, feeds = feeds,
    )

    private val widgetA = widget(
        5,
        listOf(
            BugReportFeed("ynet - מבזקים", "https://www.ynet.co.il/integration/StoryRss1854.xml", true),
            BugReportFeed(blogName, blogUrl, true),
            // the old stored http:// Hindu default, with the user's own title for it
            BugReportFeed("AI News - The Hindu", "http://www.thehindu.com/sci-tech/technology/internet/?service=rss", true),
            BugReportFeed(villageName, villageUrl, true),
            BugReportFeed(offName, offUrl, false),
        ),
    )
    private val widgetB = widget(
        7,
        listOf(
            // a default the user renamed
            BugReportFeed("הפיד שלי לחדשות", "https://t.me/s/N12_News", true),
            BugReportFeed(villageName, villageUrl, true),
        ),
    )

    private fun feedEntry(label: String, url: String, status: FeedLogStatus, code: Int? = null, detail: String = "") =
        FeedLogEntry(label, url, status, code, 0, 120, detail, 5)

    private val failedRun = RefreshLogEntry(
        timestamp = 1_700_000_600_000L, trigger = RefreshTrigger.PERIODIC, attempt = 0, durationMs = 2300, widgetCount = 2, verdict = "success",
        feeds = listOf(
            feedEntry(villageName, villageUrl, FeedLogStatus.HTTP_ERROR, 403, "HTTP 403"),
            feedEntry("ynet - מבזקים", "https://www.ynet.co.il/integration/StoryRss1854.xml", FeedLogStatus.OK),
            feedEntry(blogName, blogUrl, FeedLogStatus.NETWORK_ERROR, null,
                "https upgrade of http feed: SSLHandshakeException: Unable to resolve host \"dana-blog.example.net\""),
            feedEntry("AI News - The Hindu", "http://www.thehindu.com/sci-tech/technology/internet/?service=rss",
                FeedLogStatus.TIMEOUT, null, "SocketTimeoutException: timeout"),
            // a feed that is only in the log (removed from every widget since)
            feedEntry("בלוג ישן", "https://old-gone.example.org/rss", FeedLogStatus.HTTP_ERROR, 404, "HTTP 404"),
        ),
    )

    private fun input(includeOwn: Boolean, widgets: List<BugReportWidget> = listOf(widgetA, widgetB), note: String = "הווידג'ט תקוע") = BugReportInput(
        appVersionName = "1.1.0", appVersionCode = 151, packageName = "com.newsfeed.widget",
        manufacturer = "Samsung", model = "Fold", androidSdk = 34, androidRelease = "14",
        locale = "he-IL", timeZone = "Asia/Jerusalem", timestampMs = 1_700_001_000_000L,
        userNote = note, includeOwnFeeds = includeOwn, widgets = widgets,
        status = BugReportRefreshStatus(1_700_000_000_000L, 1_700_000_600_000L, 3, false, 15, "ENQUEUED"),
        refreshLog = listOf(failedRun), crashes = emptyList(), defaultFeeds = defaults,
    )

    private val off get() = buildBugReport(input(false))

    // -- classification --

    @Test
    fun `default feeds are named by their default names, also an old http default and a renamed one`() {
        val r = off
        assertTrue(r, r.contains("  - ynet - מבזקים"))
        assertTrue(r, r.contains("  - The Hindu - Tech"))       // stored as http:// with the user's own title
        assertTrue(r, r.contains("  - חדשות N12"))               // renamed by the user in widget 7
        assertFalse(r, r.contains("AI News - The Hindu"))
        assertFalse(r, r.contains("הפיד שלי לחדשות"))
    }

    @Test
    fun `the widget line counts default and own feeds`() {
        val r = off
        assertTrue(r, r.contains("feeds: 5 (4 enabled; 2 default, 3 yours)"))
        assertTrue(r, r.contains("feeds: 2 (2 enabled; 1 default, 1 yours)"))
    }

    @Test
    fun `without any default list everything counts as yours`() {
        val r = buildBugReport(input(false).copy(defaultFeeds = emptyList()))
        assertTrue(r, r.contains("feeds: 5 (4 enabled; 0 default, 5 yours)"))
        assertFalse(r, r.contains("ynet"))
    }

    // -- numbering --

    @Test
    fun `own feeds are numbered in order of first appearance and keep the number across widgets`() {
        val r = off
        // widget 5 lists the blog, then the village feed, then the off feed; widget 7 repeats the village feed
        assertTrue(r, r.contains("  - feed #1\n  - The Hindu - Tech\n  - feed #2\n  - feed #3 (off)"))
        assertTrue(r, r.contains("  - חדשות N12\n  - feed #2"))
    }

    @Test
    fun `refresh failure lines use the same numbers and the default names`() {
        val r = off
        val log = r.substringAfter("[Recent refreshes]")
        assertTrue(log, log.contains("  ! feed #2 [HTTP_ERROR 403, 120 ms] | HTTP 403"))
        assertTrue(log, log.contains("  ! feed #1 [NETWORK_ERROR, 120 ms]"))
        assertTrue(log, log.contains("  ! The Hindu - Tech [TIMEOUT, 120 ms] | SocketTimeoutException: timeout"))
        // a feed that is only in the log gets the next free number
        assertTrue(log, log.contains("  ! feed #4 [HTTP_ERROR 404, 120 ms] | HTTP 404"))
    }

    @Test
    fun `an http and an https address of the same own feed share one number`() {
        val w = widget(1, listOf(BugReportFeed("x", "http://same.example.org/rss", true), BugReportFeed("y", "https://same.example.org/rss/", true)))
        val r = buildBugReport(input(false, widgets = listOf(w)).copy(refreshLog = emptyList()))
        assertTrue(r, r.contains("  - feed #1\n  - feed #1"))
        assertFalse(r, r.contains("feed #2"))
    }

    // -- nothing of the user's own feeds is printed --

    @Test
    fun `with the box off no own name, address, host or token appears anywhere`() {
        val r = off
        for (needle in listOf(
            villageName, blogName, offName, "בלוג ישן", "הבלוג", "הכפר",
            "village-news", "dana-blog", "quiet.example", "old-gone", "example.org", "example.net", "example.com",
            "S3CR3T", "token", "http://", "https://",
        )) assertFalse("report leaks '$needle':\n$r", r.contains(needle, ignoreCase = true))
    }

    @Test
    fun `a custom host inside a failure detail is still scrubbed`() {
        val r = off
        assertTrue(r, r.contains("https upgrade of http feed: SSLHandshakeException: Unable to resolve host \"<host>\""))
    }

    @Test
    fun `a custom host or address in the note and in a crash is still scrubbed`() {
        val r = buildBugReport(input(false, note = "dana-blog.example.net לא עובד https://village-news.example.org/rss?token=S3CR3T"))
        assertFalse(r, r.contains("dana-blog"))
        assertFalse(r, r.contains("village-news"))
        assertFalse(r, r.contains("S3CR3T"))
    }

    // -- with the box on --

    @Test
    fun `with the box on names and redacted addresses are shown as before`() {
        val r = buildBugReport(input(true))
        assertTrue(r, r.contains("feeds: 5 (4 enabled)"))
        assertFalse(r, r.contains("yours"))
        assertFalse(r, r.contains("feed #"))
        assertTrue(r, r.contains("  - $blogName http://dana-blog.example.net/feed"))
        assertTrue(r, r.contains("  - $villageName https://village-news.example.org/rss?token=REDACTED&q=x"))
        assertTrue(r, r.contains("  - AI News - The Hindu http://www.thehindu.com/sci-tech/technology/internet/?service=rss"))
        assertTrue(r, r.contains("  - $offName (off) https://quiet.example.com/rss"))
        assertTrue(r, r.contains("  ! $villageName https://village-news.example.org/rss?token=REDACTED&q=x [HTTP_ERROR 403, 120 ms] | HTTP 403"))
        assertFalse(r, r.contains("S3CR3T"))
    }

    @Test
    fun `with the box on the output does not depend on the default list`() {
        assertEquals(buildBugReport(input(true)), buildBugReport(input(true).copy(defaultFeeds = emptyList())))
    }

    // -- header --

    @Test
    fun `the header says whether the user's own feeds are included`() {
        assertTrue(off.contains("Your own feeds: not included (named feed #1, #2 …)"))
        assertTrue(buildBugReport(input(true)).contains("Your own feeds: included (passwords and tokens removed)"))
        assertFalse(off.contains("Feed addresses:"))
    }

    @Test
    fun `output is deterministic`() {
        assertEquals(off, off)
        assertEquals(buildBugReport(input(true)), buildBugReport(input(true)))
    }
}
