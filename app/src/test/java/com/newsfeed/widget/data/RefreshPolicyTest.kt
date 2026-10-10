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
}
