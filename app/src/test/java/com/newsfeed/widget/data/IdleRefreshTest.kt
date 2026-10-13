package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BUG-046: the wake-up alarm that refreshes the widget while the phone is idle. */
class IdleRefreshTest {

    private val now = 100_000_000_000L
    private val min = 60_000L

    // -- delay alignment --

    @Test
    fun `with no run known the next alarm is one full interval away`() {
        assertEquals(15 * min, idleRefreshDelayMs(15, now, lastRunFinishedMs = 0L))
        assertEquals(60 * min, idleRefreshDelayMs(60, now, lastRunFinishedMs = 0L))
    }

    @Test
    fun `the alarm stays aligned with the last run (interval minus the time since it finished)`() {
        assertEquals(15 * min, idleRefreshDelayMs(15, now, lastRunFinishedMs = now))
        assertEquals(10 * min, idleRefreshDelayMs(15, now, lastRunFinishedMs = now - 5 * min))
        assertEquals(1 * min + 1, idleRefreshDelayMs(15, now, lastRunFinishedMs = now - 14 * min + 1))
        assertEquals(40 * min, idleRefreshDelayMs(60, now, lastRunFinishedMs = now - 20 * min))
    }

    @Test
    fun `the delay is never shorter than 60 seconds, even for an overdue run`() {
        assertEquals(60_000L, idleRefreshDelayMs(15, now, lastRunFinishedMs = now - 14 * min - 30_000L))
        assertEquals(60_000L, idleRefreshDelayMs(15, now, lastRunFinishedMs = now - 15 * min))
        assertEquals(60_000L, idleRefreshDelayMs(15, now, lastRunFinishedMs = now - 3 * 60 * min))
        assertEquals(60_000L, idleRefreshDelayMs(15, now, lastRunFinishedMs = 1L))
    }

    @Test
    fun `the delay never exceeds the interval, also when the clock moved back`() {
        assertEquals(15 * min, idleRefreshDelayMs(15, now, lastRunFinishedMs = now + 5 * min))
        assertEquals(15 * min, idleRefreshDelayMs(15, now, lastRunFinishedMs = Long.MAX_VALUE))
        assertEquals(15 * min, idleRefreshDelayMs(15, now, lastRunFinishedMs = -5L))
    }

    @Test
    fun `an interval below the 15 minute minimum counts as 15 minutes`() {
        assertEquals(15 * min, idleRefreshDelayMs(1, now, 0L))
        assertEquals(15 * min, idleRefreshDelayMs(0, now, 0L))
        assertEquals(15 * min, idleRefreshDelayMs(-30, now, 0L))
        assertEquals(10 * min, idleRefreshDelayMs(5, now, now - 5 * min))
    }

    @Test
    fun `the delay always lies between 60 seconds and the interval`() {
        for (interval in listOf(15, 30, 60, 360, 1440)) {
            val intervalMs = interval * min
            for (k in listOf(-1L, 0L, 1L, 59_999L, 60_000L, intervalMs - 60_001L, intervalMs - 1L, intervalMs, intervalMs + 1L, 10 * intervalMs)) {
                val d = idleRefreshDelayMs(interval, now, now - k)
                assertTrue("interval=$interval k=$k d=$d", d >= IDLE_ALARM_MIN_DELAY_MS)
                assertTrue("interval=$interval k=$k d=$d", d <= intervalMs)
            }
        }
    }

    @Test
    fun `a very long interval does not overflow`() {
        val d = idleRefreshDelayMs(Int.MAX_VALUE, now, 0L)
        assertEquals(Int.MAX_VALUE * min, d)
    }

    // -- when to arm --

    @Test
    fun `the alarm is armed only when the switch is on and a widget exists`() {
        assertTrue(shouldArmIdleAlarm(enabled = true, widgetsExist = true))
        assertFalse(shouldArmIdleAlarm(enabled = true, widgetsExist = false))
        assertFalse(shouldArmIdleAlarm(enabled = false, widgetsExist = true))
        assertFalse(shouldArmIdleAlarm(enabled = false, widgetsExist = false))
    }

    // -- self-heal from the clock tick --

    @Test
    fun `the tick re-arms only an alarm that looks lost`() {
        val interval = 15 * min
        assertFalse("switch off", idleAlarmNeedsRearm(false, 0L, now, interval))
        assertTrue("never planned", idleAlarmNeedsRearm(true, 0L, now, interval))
        assertFalse("planned ahead", idleAlarmNeedsRearm(true, now + 10 * min, now, interval))
        assertFalse("just due", idleAlarmNeedsRearm(true, now - 1, now, interval))
        assertFalse("Doze delayed it by 14 minutes", idleAlarmNeedsRearm(true, now - 14 * min, now, interval))
        assertFalse("overdue by exactly one interval", idleAlarmNeedsRearm(true, now - interval, now, interval))
        assertTrue("overdue by more than an interval", idleAlarmNeedsRearm(true, now - interval - 1, now, interval))
        assertTrue("clock moved back", idleAlarmNeedsRearm(true, now + interval + 1, now, interval))
    }

    // -- the Settings switch --

    private class FakeSetting(override var idleRefreshEnabled: Boolean = DEFAULT_IDLE_REFRESH_ENABLED) : IdleRefreshSetting

    @Test
    fun `the switch is on by default`() {
        assertTrue(DEFAULT_IDLE_REFRESH_ENABLED)
        assertTrue(FakeSetting().idleRefreshEnabled)
    }

    @Test
    fun `toggling saves at once and then arms or cancels`() {
        val store = FakeSetting()
        val seenWhenCalled = mutableListOf<Boolean>()
        applyIdleRefreshToggle(store, false) { seenWhenCalled += store.idleRefreshEnabled }
        assertFalse(store.idleRefreshEnabled)
        applyIdleRefreshToggle(store, true) { seenWhenCalled += store.idleRefreshEnabled }
        assertTrue(store.idleRefreshEnabled)
        // The callback saw the NEW value each time: the choice is saved before the alarm logic reads it.
        assertEquals(listOf(false, true), seenWhenCalled)
    }

    @Test
    fun `a failing arm step does not undo the saved choice`() {
        val store = FakeSetting()
        applyIdleRefreshToggle(store, false) { throw IllegalStateException("alarm service gone") }
        assertFalse(store.idleRefreshEnabled)
    }

    // -- the report lines --

    @Test
    fun `the report says off, on and not armed, or on and when the alarm is planned`() {
        val at = { ms: Long -> "T$ms" }
        assertEquals(listOf("Idle refresh alarm: off"), idleRefreshReportLines(false, 0L, now, at))
        // Off wins even if a stale due time is stored.
        assertEquals(listOf("Idle refresh alarm: off"), idleRefreshReportLines(false, now + 5, now, at))
        assertEquals(
            listOf("Idle refresh alarm: on", "Idle alarm planned for: not armed"),
            idleRefreshReportLines(true, 0L, now, at),
        )
        assertEquals(
            listOf("Idle refresh alarm: on", "Idle alarm planned for: T${now + 1000}"),
            idleRefreshReportLines(true, now + 1000, now, at),
        )
        assertEquals(
            listOf("Idle refresh alarm: on", "Idle alarm planned for: T${now - 1000} (already due)"),
            idleRefreshReportLines(true, now - 1000, now, at),
        )
    }
}
