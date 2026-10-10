package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshPolicyTest {

    private fun cfg(id: Int, minutes: Int) = WidgetConfig(widgetId = id, refreshIntervalMinutes = minutes)

    // ── effectiveRefreshIntervalMinutes (BUG-034: the interval must survive a re-created job) ──

    @Test
    fun `no widget configs falls back to the 15 minute minimum`() {
        assertEquals(15, effectiveRefreshIntervalMinutes(emptyList()))
    }

    @Test
    fun `a single widget keeps its own saved interval`() {
        assertEquals(60, effectiveRefreshIntervalMinutes(listOf(cfg(1, 60))))
        assertEquals(720, effectiveRefreshIntervalMinutes(listOf(cfg(1, 720))))
    }

    @Test
    fun `the shared job uses the smallest interval among the widgets`() {
        assertEquals(30, effectiveRefreshIntervalMinutes(listOf(cfg(1, 60), cfg(2, 30), cfg(3, 720))))
        assertEquals(15, effectiveRefreshIntervalMinutes(listOf(cfg(1, 15), cfg(2, 360))))
    }

    @Test
    fun `an interval below the WorkManager minimum is raised to 15`() {
        assertEquals(15, effectiveRefreshIntervalMinutes(listOf(cfg(1, 5))))
        assertEquals(15, effectiveRefreshIntervalMinutes(listOf(cfg(1, 0))))
        assertEquals(15, effectiveRefreshIntervalMinutes(listOf(cfg(1, -30))))
    }

    @Test
    fun `an invalid interval does not drag a valid widget below its own value`() {
        // a corrupt 0 is raised to 15, the other widget at 60 is not what decides: min(15, 60)
        assertEquals(15, effectiveRefreshIntervalMinutes(listOf(cfg(1, 0), cfg(2, 60))))
    }

    @Test
    fun `order of the configs does not matter`() {
        val a = listOf(cfg(1, 120), cfg(2, 30), cfg(3, 60))
        assertEquals(effectiveRefreshIntervalMinutes(a), effectiveRefreshIntervalMinutes(a.reversed()))
    }

    // ── workNeedsMigration (existing installs pick up the network constraint once) ──

    @Test
    fun `a job from before the schema existed needs migrating`() {
        assertTrue(workNeedsMigration(0, 1))
        assertTrue(workNeedsMigration(0))
    }

    @Test
    fun `a job already at the current schema is left alone`() {
        assertFalse(workNeedsMigration(1, 1))
        assertFalse(workNeedsMigration(WORK_SCHEMA_VERSION))
    }

    @Test
    fun `a stored version newer than this build never downgrades`() {
        assertFalse(workNeedsMigration(5, 1))
    }
    // -- shouldKickRefresh: the watchdog behind the clock tick (BUG-036) --

    private val min = 60_000L
    private val interval = 60 * min      // job interval 1 h
    private val now = 10_000_000_000_000L

    @Test
    fun `a refresh within two intervals is healthy, no kick`() {
        assertFalse(shouldKickRefresh(now - 30 * min, now, interval, 0L))
        assertFalse(shouldKickRefresh(now - 119 * min, now, interval, 0L))
        assertFalse(shouldKickRefresh(now - 2 * interval, now, interval, 0L))   // exactly 2x is not older than 2x
    }

    @Test
    fun `no good refresh for more than two intervals kicks one`() {
        assertTrue(shouldKickRefresh(now - 2 * interval - 1, now, interval, 0L))
        assertTrue(shouldKickRefresh(now - 10 * interval, now, interval, 0L))
    }

    @Test
    fun `never refreshed kicks one`() {
        assertTrue(shouldKickRefresh(0L, now, interval, 0L))
    }

    @Test
    fun `a kick within the last interval is not repeated`() {
        val stale = now - 5 * interval
        assertFalse(shouldKickRefresh(stale, now, interval, now - 1))
        assertFalse(shouldKickRefresh(stale, now, interval, now - interval + 1))
    }

    @Test
    fun `the next kick is allowed one interval after the previous one`() {
        val stale = now - 5 * interval
        assertTrue(shouldKickRefresh(stale, now, interval, now - interval))
        assertTrue(shouldKickRefresh(stale, now, interval, now - 3 * interval))
    }

    @Test
    fun `a kick time in the future (clock moved back) does not block the watchdog forever`() {
        assertTrue(shouldKickRefresh(now - 5 * interval, now, interval, now + 10 * interval))
    }

    @Test
    fun `a last refresh time in the future (clock moved back) counts as overdue`() {
        assertTrue(shouldKickRefresh(now + 50 * interval, now, interval, 0L))
    }

    @Test
    fun `a bad interval never kicks`() {
        assertFalse(shouldKickRefresh(0L, now, 0L, 0L))
        assertFalse(shouldKickRefresh(0L, now, -1L, 0L))
    }

    @Test
    fun `bursts collapse, many ticks in a row cause one kick`() {
        // the receiver stores lastKick = now after a kick; the following ticks (every ~105 s) see it
        var lastKick = 0L
        var kicks = 0
        var t = now
        repeat(30) {   // 30 ticks span about 52 minutes, less than one 60 minute interval
            if (shouldKickRefresh(now - 9 * interval, t, interval, lastKick)) { kicks++; lastKick = t }
            t += 105_000L
        }
        assertEquals(1, kicks)
    }
}
