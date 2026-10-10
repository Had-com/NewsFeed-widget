package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** BUG-035: the footer tells the truth about when the widget last really updated. */
class RefreshStatusTextTest {

    private val min = 60_000L
    private val interval = 15 * min
    private val t0 = 1_000_000_000_000L   // an arbitrary "last good refresh"

    private fun status(
        now: Long, lastOk: Long = t0, attempt: Long = t0, failedAll: Boolean = false,
        partial: Int = 0, intervalMs: Long = interval,
    ) = refreshStatusText(lastOk, attempt, failedAll, partial, now, intervalMs)

    // -- fresh --

    @Test
    fun `just refreshed counts down in whole minutes, rounded down`() {
        assertEquals(RefreshStatus("↻ in 15min", RefreshTone.NORMAL), status(now = t0))
        assertEquals(RefreshStatus("↻ in 7min", RefreshTone.NORMAL), status(now = t0 + 7 * min + 30_000))
        assertEquals(RefreshStatus("↻ in 1min", RefreshTone.NORMAL), status(now = t0 + 14 * min))
    }

    @Test
    fun `long intervals count down in hours`() {
        val twelveH = 12 * 60 * min
        assertEquals("↻ in 12h", status(now = t0, intervalMs = twelveH).text)
        assertEquals("↻ in 119min", status(now = t0, intervalMs = 119 * min).text)
        assertEquals("↻ in 2h", status(now = t0, intervalMs = 120 * min).text)
    }

    // -- due --

    @Test
    fun `less than a minute left, or a little late, says under one minute`() {
        assertEquals("↻ <1min", status(now = t0 + 14 * min + 30_000).text)
        assertEquals("↻ <1min", status(now = t0 + interval).text)
        assertEquals("↻ <1min", status(now = t0 + interval + REFRESH_GRACE_MS - 1).text)
        assertEquals(RefreshTone.NORMAL, status(now = t0 + interval + 1).tone)
    }

    // -- overdue --

    @Test
    fun `past the grace period it no longer promises a refresh, it says when it last updated`() {
        val s = status(now = t0 + interval + REFRESH_GRACE_MS)
        assertEquals(RefreshStatus("Updated 17m ago", RefreshTone.MUTED), s)
        assertEquals("Updated 3h ago", status(now = t0 + 3 * 60 * min + 5 * min).text)
        assertEquals("Updated 60m ago", status(now = t0 + 60 * min).text)
        assertEquals("Updated 2h ago", status(now = t0 + 125 * min).text)
        assertEquals("Updated 2d ago", status(now = t0 + 49 * 60 * min).text)
        assertEquals(RefreshTone.MUTED, status(now = t0 + 3 * 60 * min).tone)
    }

    @Test
    fun `an overdue widget never shows the old stuck under-one-minute text`() {
        for (hours in listOf(1L, 3L, 10L, 30L)) {
            assertTrue(!status(now = t0 + hours * 60 * min).text.contains("<1min"))
        }
    }

    // -- failed --

    @Test
    fun `an all-failed last attempt shows the retry text in amber`() {
        val s = status(now = t0 + 5 * min, attempt = t0 + 5 * min, failedAll = true)
        assertEquals(RefreshStatus("⚠ refresh failed — tap to retry", RefreshTone.WARNING), s)
    }

    @Test
    fun `failed with no good refresh yet is still the failure text`() {
        val s = refreshStatusText(0L, t0, true, 0, t0 + min, interval)
        assertEquals(REFRESH_FAILED_TEXT, s.text)
    }

    @Test
    fun `a failure flag from an older build without an attempt time is trusted`() {
        assertEquals(REFRESH_FAILED_TEXT, status(now = t0 + min, attempt = 0L, failedAll = true).text)
    }

    @Test
    fun `a failure older than the last good refresh is stale and ignored`() {
        val s = status(now = t0 + 5 * min, attempt = t0 - 10 * min, failedAll = true)
        assertEquals("↻ in 10min", s.text)
    }

    @Test
    fun `failure wins over overdue`() {
        assertEquals(REFRESH_FAILED_TEXT, status(now = t0 + 5 * 60 * min, attempt = t0 + 5 * 60 * min, failedAll = true).text)
    }

    // -- partial --

    @Test
    fun `some feeds failing puts a short marker in front, amber`() {
        val s = status(now = t0 + 8 * min, partial = 2)
        assertEquals(RefreshStatus("⚠ 2 failed · ↻ in 7min", RefreshTone.WARNING), s)
    }

    @Test
    fun `a partial failure stays visible when the refresh is overdue`() {
        val s = status(now = t0 + 3 * 60 * min, partial = 1)
        assertEquals(RefreshStatus("⚠ 1 failed · Updated 3h ago", RefreshTone.WARNING), s)
    }

    @Test
    fun `a huge failed count is capped`() {
        assertEquals("⚠ 99+ failed · ↻ in 15min", status(now = t0, partial = 250).text)
    }

    // -- never refreshed --

    @Test
    fun `never refreshed says now`() {
        assertEquals(RefreshStatus("↻ now", RefreshTone.NORMAL), refreshStatusText(0L, 0L, false, 0, t0, interval))
    }

    // -- width: the footer text is the flexible element, it must stay as short as the old failure text --

    @Test
    fun `no text is longer than the longest text the footer already handled`() {
        assertEquals(REFRESH_STATUS_MAX_CHARS, REFRESH_FAILED_TEXT.length)
        val intervals = listOf(15, 60, 720).map { it * min }
        for (iv in intervals) for (offset in listOf(0L, min, 10 * min, 20 * min, 3 * 60 * min, 40 * 60 * min, 400 * 60 * 60 * min)) {
            for (partial in listOf(0, 1, 12, 99, 500)) for (failed in listOf(false, true)) {
                val text = refreshStatusText(t0, t0 + offset, failed, partial, t0 + offset, iv).text
                assertTrue("'$text' is ${text.length} chars", text.length <= REFRESH_STATUS_MAX_CHARS)
            }
        }
    }
}
