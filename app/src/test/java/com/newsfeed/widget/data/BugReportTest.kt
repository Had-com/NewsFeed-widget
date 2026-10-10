package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BugReportTest {

    // -- redactUrl --

    @Test
    fun `user info is removed`() {
        assertEquals("https://example.com/feed.xml", redactUrl("https://user:secret@example.com/feed.xml"))
        assertEquals("http://example.com/x", redactUrl("http://token@example.com/x"))
    }

    @Test
    fun `values of sensitive query keys are redacted and other params stay`() {
        assertEquals(
            "https://e.com/rss?token=REDACTED&lang=he",
            redactUrl("https://e.com/rss?token=abc123&lang=he"),
        )
        assertEquals(
            "https://e.com/rss?access_token=REDACTED&X-Amz-Signature=REDACTED&apikey=REDACTED&api_key=REDACTED&SESSIONID=REDACTED&Auth=REDACTED&code=REDACTED&pass=REDACTED",
            redactUrl("https://e.com/rss?access_token=1&X-Amz-Signature=2&apikey=3&api_key=4&SESSIONID=5&Auth=6&code=7&pass=8"),
        )
    }

    @Test
    fun `repeated params are all redacted`() {
        assertEquals(
            "https://e.com/?key=REDACTED&a=1&key=REDACTED",
            redactUrl("https://e.com/?key=x&a=1&key=y"),
        )
    }

    @Test
    fun `host path and the google news search query stay readable`() {
        val u = "https://news.google.com/rss/search?q=israel+news&hl=he&gl=IL&ceid=IL:he"
        assertEquals(u, redactUrl(u))
    }

    @Test
    fun `hebrew percent-encoded and raw text survive`() {
        val enc = "https://news.google.com/rss/search?q=%D7%97%D7%93%D7%A9%D7%95%D7%AA&hl=he"
        assertEquals(enc, redactUrl(enc))
        val raw = "https://example.com/חדשות/rss?q=שלום&token=סוד"
        assertEquals("https://example.com/חדשות/rss?q=שלום&token=REDACTED", redactUrl(raw))
    }

    @Test
    fun `fragments keep plain text and redact key value pairs`() {
        assertEquals("https://e.com/a#section-2", redactUrl("https://e.com/a#section-2"))
        assertEquals("https://e.com/a#access_token=REDACTED&state=x", redactUrl("https://e.com/a#access_token=zzz&state=x"))
    }

    @Test
    fun `malformed input never throws`() {
        val inputs = listOf("", "   ", "not a url", "://", "http://", "https://@@@/?&&&=&#", "?token=", "%%%", "a".repeat(5000),
            "https://user:pa ss@ex ample.com/ä?x=%zz&token", "\u0000\u0001")
        for (i in inputs) {
            val r = redactUrl(i)
            assertTrue(r.length <= 520)
        }
        assertEquals("not a url", redactUrl("not a url"))
        assertEquals("?token=REDACTED", redactUrl("?token=abc"))
    }

    @Test
    fun `urls inside text are redacted too`() {
        val t = "failed https://u:p@e.com/rss?token=abc&x=1 and more"
        assertEquals("failed https://e.com/rss?token=REDACTED&x=1 and more", redactUrlsInText(t))
    }

    @Test
    fun `scrub removes urls hosts and ip literals`() {
        val t = """Unable to resolve host "feeds.example.com": No address; Failed to connect to /192.168.1.20:443; see https://other.org/a?token=1"""
        val out = scrubAddresses(t, listOf("https://feeds.example.com/rss"))
        assertFalse(out.contains("example.com"))
        assertFalse(out.contains("192.168"))
        assertFalse(out.contains("other.org"))
        assertTrue(out.contains("<host>"))
    }

    @Test
    fun `host of an address`() {
        assertEquals("example.com", hostOf("https://User:pw@Example.com:8080/a?b#c"))
        assertEquals("example.com", hostOf("example.com/path"))
        assertEquals("", hostOf(""))
    }

    // -- the report --

    private val secretUrl = "https://secret-host.example.org/private/rss?token=TOPSECRET&q=news"
    private val plainUrl = "https://plain.example.net/feed"

    private fun failedRun(ts: Long) = RefreshLogEntry(
        timestamp = ts, trigger = RefreshTrigger.MANUAL_TAP, attempt = 0, durationMs = 2300, widgetCount = 1, verdict = "success",
        feeds = listOf(
            FeedLogEntry("Good feed", plainUrl, FeedLogStatus.OK, null, 12, 200, "", 5),
            FeedLogEntry("Broken feed", secretUrl, FeedLogStatus.NETWORK_ERROR, null, 0, 90,
                "dns: UnknownHostException: Unable to resolve host \"secret-host.example.org\": No address", 5),
            FeedLogEntry("Gone feed", plainUrl + "2", FeedLogStatus.HTTP_ERROR, 404, 0, 310, "HTTP 404", 5),
        ),
    )

    private fun okRun(ts: Long) = RefreshLogEntry(
        timestamp = ts, trigger = RefreshTrigger.PERIODIC, attempt = 0, durationMs = 800, widgetCount = 1, verdict = "success",
        feeds = listOf(FeedLogEntry("Good feed", plainUrl, FeedLogStatus.OK, null, 12, 200, "", 5)),
    )

    private fun crash(ts: Long, build: Int = 10, msg: String = "boom https://secret-host.example.org/x?token=TOPSECRET") =
        CrashLogStore.CrashRecord(ts, build, "java.lang.IllegalStateException", msg,
            "java.lang.IllegalStateException: $msg\n\tat com.newsfeed.Foo.bar(Foo.kt:1)\nCaused by: java.net.UnknownHostException: secret-host.example.org")

    private fun input(
        includeAddresses: Boolean = false,
        log: List<RefreshLogEntry> = listOf(okRun(1_700_000_000_000L), failedRun(1_700_000_600_000L)),
        crashes: List<CrashLogStore.CrashRecord> = emptyList(),
        note: String = "The widget shows 2 failed",
    ) = BugReportInput(
        appVersionName = "1.0", appVersionCode = 149, packageName = "com.newsfeed.widget",
        manufacturer = "Google", model = "Pixel 7", androidSdk = 34, androidRelease = "14",
        locale = "he-IL", timeZone = "Asia/Jerusalem", timestampMs = 1_700_001_000_000L,
        userNote = note, includeFeedAddresses = includeAddresses,
        widgets = listOf(
            BugReportWidget(
                id = 5, tapMode = "expand", filter = "all", sortOrder = "by_feed", theme = "glamer", themeVariant = "light",
                refreshIntervalMinutes = 15, retentionDays = 0, retentionMinutes = 0, groupStories = true,
                articleLength = "medium", fontSize = 1.0f, articleFontSize = 1.25f,
                feeds = listOf(
                    BugReportFeed("Good feed", plainUrl, true),
                    BugReportFeed("Broken feed", secretUrl, true),
                    BugReportFeed("Off feed", "https://off.example.com/rss", false),
                ),
            ),
        ),
        status = BugReportRefreshStatus(1_700_000_000_000L, 1_700_000_600_000L, 2, false, 15, "ENQUEUED"),
        refreshLog = log, crashes = crashes,
    )

    @Test
    fun `report has the build the device the widgets and the refresh log`() {
        val r = buildBugReport(input())
        assertTrue(r.startsWith("NewsFeed problem report (schema $BUG_REPORT_SCHEMA_VERSION)"))
        assertTrue(r.contains("Version: 1.0 (build 149)"))
        assertTrue(r.contains("Package: com.newsfeed.widget"))
        assertTrue(r.contains("Device: Google Pixel 7"))
        assertTrue(r.contains("Android: 14 (SDK 34)"))
        assertTrue(r.contains("Locale: he-IL, time zone: Asia/Jerusalem"))
        assertTrue(r.contains("The widget shows 2 failed"))
        assertTrue(r.contains("Widget 5: tap=expand, filter=all, sort=by_feed, theme=glamer/light, refresh every 15 min"))
        assertTrue(r.contains("feeds: 3 (2 enabled)"))
        assertTrue(r.contains("Periodic job: ENQUEUED"))
        assertTrue(r.contains("! Broken feed [NETWORK_ERROR, 90 ms]"))
        assertTrue(r.contains("! Gone feed [HTTP_ERROR 404, 310 ms] | HTTP 404"))
        assertTrue(r.contains("1/3 feeds OK"))
        assertTrue(r.trimEnd().endsWith("-- end of report (schema $BUG_REPORT_SCHEMA_VERSION) --"))
    }

    @Test
    fun `runs without failures are summarized by their counts only`() {
        val r = buildBugReport(input(log = listOf(okRun(1_700_000_000_000L))))
        assertTrue(r.contains("1/1 feeds OK"))
        assertFalse(r.contains("  ! "))
    }

    @Test
    fun `newest run comes first`() {
        val r = buildBugReport(input())
        assertTrue(r.indexOf("tap |") < r.indexOf("periodic |"))
    }

    @Test
    fun `without the checkbox no address text appears anywhere`() {
        val r = buildBugReport(input(includeAddresses = false, crashes = listOf(crash(1_700_000_300_000L))))
        for (needle in listOf("http://", "https://", "example.org", "example.net", "example.com", "TOPSECRET", "token=", "/private/rss")) {
            assertFalse("report leaks '$needle':\n$r", r.contains(needle, ignoreCase = true))
        }
        assertTrue(r.contains("Feed addresses: not included"))
        assertTrue(r.contains("Broken feed"))
    }

    @Test
    fun `with the checkbox addresses are shown but secrets are redacted`() {
        val r = buildBugReport(input(includeAddresses = true, crashes = listOf(crash(1_700_000_300_000L))))
        assertTrue(r.contains("https://plain.example.net/feed"))
        assertTrue(r.contains("https://secret-host.example.org/private/rss?token=REDACTED&q=news"))
        assertFalse(r.contains("TOPSECRET"))
        assertTrue(r.contains("Feed addresses: included"))
    }

    @Test
    fun `empty log and no crashes are stated`() {
        val r = buildBugReport(input(log = emptyList()))
        assertTrue(r.contains("[Recent refreshes] (newest first, 0 of 0 recorded)"))
        assertTrue(r.contains("No crashes recorded."))
    }

    @Test
    fun `an empty note says so`() {
        assertTrue(buildBugReport(input(note = "   ")).contains("(no note)"))
    }

    @Test
    fun `the note is cut at 2000 chars`() {
        val r = buildBugReport(input(note = "n".repeat(5000)))
        assertTrue(r.contains("n".repeat(BUG_REPORT_MAX_NOTE_CHARS)))
        assertFalse(r.contains("n".repeat(BUG_REPORT_MAX_NOTE_CHARS + 1)))
    }

    @Test
    fun `crashes are summarized and only the last three stack traces are included`() {
        val crashes = (1..5).map { crash(1_700_000_000_000L + it * 1000, msg = "boom$it") }
        val r = buildBugReport(input(crashes = crashes))
        assertTrue(r.contains("java.lang.IllegalStateException: boom5"))
        assertEquals(3, Regex("Stack trace \\(").findAll(r).count())
        assertTrue(r.contains("boom3"))
        assertFalse(r.contains("boom2\n\tat"))
    }

    @Test
    fun `only the last 20 runs are listed`() {
        val log = (1..30).map { okRun(1_700_000_000_000L + it * 60_000L) }
        val r = buildBugReport(input(log = log))
        assertTrue(r.contains("20 of 30 recorded"))
        assertEquals(20, Regex("\\| periodic \\|").findAll(r).count())
    }

    @Test
    fun `the report is capped and the header and footer survive`() {
        val bigDetail = "d".repeat(120)
        val feeds = (1..100).map { FeedLogEntry("Feed number $it", "https://x.example.com/$it", FeedLogStatus.HTTP_ERROR, 500, 0, 10, bigDetail, 5) }
        val log = (1..20).map { RefreshLogEntry(1_700_000_000_000L + it * 60_000L, RefreshTrigger.PERIODIC, 0, 100, 1, "success", feeds) }
        val crashes = (1..3).map { crash(1_700_000_000_000L + it) }
        val r = buildBugReport(input(includeAddresses = true, log = log, crashes = crashes))
        assertTrue("size ${r.length}", r.length <= BUG_REPORT_MAX_CHARS)
        assertTrue(r.startsWith("NewsFeed problem report"))
        assertTrue(r.contains("Version: 1.0 (build 149)"))
        assertTrue(r.contains("The widget shows 2 failed"))
        assertTrue(r.contains("(... older lines left out"))
        assertTrue(r.trimEnd().endsWith("-- end of report (schema $BUG_REPORT_SCHEMA_VERSION) --"))
        // the newest run is kept, the oldest lines are the ones that went
        assertTrue(r.contains("2023-11-15 00:33:20 | periodic"))
        assertFalse(r.contains("2023-11-15 00:14:20 | periodic"))
    }

    @Test
    fun `output is deterministic`() {
        val i = input(includeAddresses = true, crashes = listOf(crash(1_700_000_300_000L)))
        assertEquals(buildBugReport(i), buildBugReport(i))
    }

    @Test
    fun `timestamps use the given time zone`() {
        val r = buildBugReport(input())
        // 1_700_001_000_000 ms = 2023-11-14 22:30:00 UTC = 2023-11-15 00:30:00 in Asia/Jerusalem (UTC+2)
        assertTrue(r.contains("Created: 2023-11-15 00:30:00 (Asia/Jerusalem)"))
    }

    @Test
    fun `an unknown time zone falls back to UTC and never throws`() {
        val r = buildBugReport(input().copy(timeZone = "Not/AZone"))
        assertTrue(r.contains("Created: 2023-11-14 22:30:00 (UTC)"))
    }
}
