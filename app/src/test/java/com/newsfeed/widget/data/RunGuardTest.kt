package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import com.newsfeed.widget.data.RefreshTrigger as T

/** BUG-043: the periodic run, the watchdog run and taps must never fetch at the same time or back to back. */
class RunGuardTest {

    private val now = 10_000_000L
    private val background = listOf(T.PERIODIC, T.WATCHDOG, T.IDLE_ALARM, T.OTHER)

    private fun skip(t: T, active: Long = 0L, finished: Long = 0L, at: Long = now) = shouldSkipRun(t, at, active, finished)

    // -- nothing known --

    @Test
    fun `with no marks every trigger runs`() {
        for (t in T.entries) assertFalse(t.name, skip(t))
    }

    // -- an active run --

    @Test
    fun `an active run blocks every trigger`() {
        for (t in T.entries) {
            assertTrue(t.name, skip(t, active = now - 1_000))
            assertTrue(t.name, skip(t, active = now - 119_999))
        }
    }

    @Test
    fun `a stale active mark of 120 seconds or more is ignored`() {
        for (t in T.entries) {
            assertFalse(t.name, skip(t, active = now - 120_000))
            assertFalse(t.name, skip(t, active = now - 3_600_000))
        }
    }

    // -- a run that just finished --

    @Test
    fun `background triggers wait 90 seconds after any run finished`() {
        for (t in background) {
            assertTrue(t.name, skip(t, finished = now))
            assertTrue(t.name, skip(t, finished = now - 89_999))
            assertFalse(t.name, skip(t, finished = now - 90_000))
            assertFalse(t.name, skip(t, finished = now - 600_000))
        }
    }

    @Test
    fun `a tap is only held back by the 15 second floor`() {
        assertTrue(skip(T.MANUAL_TAP, finished = now - 1_000))
        assertTrue(skip(T.MANUAL_TAP, finished = now - 14_999))
        assertFalse(skip(T.MANUAL_TAP, finished = now - 15_000))
        assertFalse(skip(T.MANUAL_TAP, finished = now - 60_000))
    }

    @Test
    fun `settings save and widget added are never skipped because a run just finished`() {
        for (t in listOf(T.SETTINGS_SAVE, T.WIDGET_ADDED)) {
            assertFalse(t.name, skip(t, finished = now))
            assertFalse(t.name, skip(t, finished = now - 1_000))
        }
    }

    @Test
    fun `settings save is still skipped while a run is active`() {
        assertTrue(skip(T.SETTINGS_SAVE, active = now - 5_000))
    }

    // -- clock skew --

    @Test
    fun `marks in the future (clock moved back) are ignored`() {
        for (t in T.entries) {
            assertFalse(t.name, skip(t, active = now + 5_000))
            assertFalse(t.name, skip(t, finished = now + 5_000))
            assertFalse(t.name, skip(t, active = Long.MAX_VALUE, finished = Long.MAX_VALUE))
        }
    }

    @Test
    fun `zero and negative marks mean never`() {
        for (t in T.entries) {
            assertFalse(t.name, skip(t, active = 0L, finished = 0L))
            assertFalse(t.name, skip(t, active = -5L, finished = -5L))
        }
    }

    @Test
    fun `a mark equal to now counts as just now`() {
        assertTrue(skip(T.PERIODIC, active = now))
        assertTrue(skip(T.PERIODIC, finished = now))
    }

    // -- the idle alarm (BUG-046) uses the same guard as the periodic job --

    @Test
    fun `an idle alarm run is skipped like a periodic one`() {
        assertTrue(skip(T.IDLE_ALARM, active = now - 1_000))
        assertTrue(skip(T.IDLE_ALARM, finished = now - 89_999))
        assertFalse(skip(T.IDLE_ALARM, finished = now - 90_000))
        assertFalse(skip(T.IDLE_ALARM, active = now - 120_000))
        for (t in listOf(0L, 1L, 30_000L, 89_999L, 90_000L)) {
            assertEquals(skip(T.PERIODIC, finished = now - t), skip(T.IDLE_ALARM, finished = now - t))
        }
    }

    // -- the watchdog does not enqueue behind a run --

    @Test
    fun `the watchdog does not kick while a run is active`() {
        assertTrue(runBlocksKick(now, activeRunStartedMs = now - 30_000, lastRunFinishedMs = 0L, intervalMs = 900_000L))
        assertFalse(runBlocksKick(now, activeRunStartedMs = now - 120_000, lastRunFinishedMs = 0L, intervalMs = 900_000L))
    }

    @Test
    fun `the watchdog does not kick when a run finished within the last interval`() {
        val interval = 900_000L
        assertTrue(runBlocksKick(now, 0L, now - 1, interval))
        assertTrue(runBlocksKick(now, 0L, now - interval + 1, interval))
        assertFalse(runBlocksKick(now, 0L, now - interval, interval))
        assertFalse(runBlocksKick(now, 0L, 0L, interval))
    }

    @Test
    fun `the watchdog ignores marks from the future and a bad interval`() {
        assertFalse(runBlocksKick(now, now + 1_000, now + 1_000, 900_000L))
        assertFalse(runBlocksKick(now, 0L, now - 10, 0L))
    }

    // -- the guard's compare-and-set --

    private class FakeMarks : RunMarks {
        @Volatile override var activeStartedMs = 0L
        @Volatile override var lastFinishedMs = 0L
    }

    @Test
    fun `tryBegin takes the mark once and the second caller is refused`() {
        val marks = FakeMarks()
        val g = RunGuard(marks)
        assertTrue(g.tryBegin(T.PERIODIC, now, ignoreRecentFinish = false))
        assertEquals(now, marks.activeStartedMs)
        assertFalse(g.tryBegin(T.WATCHDOG, now + 1, ignoreRecentFinish = false))
        assertFalse(g.tryBegin(T.MANUAL_TAP, now + 1, ignoreRecentFinish = false))
        assertEquals(now, marks.activeStartedMs)
    }

    @Test
    fun `end clears the mark and records the finish only for a completed run`() {
        val marks = FakeMarks()
        val g = RunGuard(marks)
        g.tryBegin(T.PERIODIC, now, false)
        g.end(now + 5_000, completed = true)
        assertEquals(0L, marks.activeStartedMs)
        assertEquals(now + 5_000, marks.lastFinishedMs)

        val marks2 = FakeMarks()
        val g2 = RunGuard(marks2)
        g2.tryBegin(T.PERIODIC, now, false)
        g2.end(now + 5_000, completed = false)   // cancelled
        assertEquals(0L, marks2.activeStartedMs)
        assertEquals(0L, marks2.lastFinishedMs)
        assertTrue(g2.tryBegin(T.PERIODIC, now + 6_000, false))
    }

    @Test
    fun `a background run right after a finished one is refused, a retry attempt is not`() {
        val g = RunGuard(FakeMarks())
        g.tryBegin(T.PERIODIC, now, false); g.end(now + 2_000, completed = true)
        assertFalse(g.tryBegin(T.WATCHDOG, now + 10_000, ignoreRecentFinish = false))
        // WorkManager's backed-off retry of the run that just finished must still go ahead
        assertTrue(g.tryBegin(T.PERIODIC, now + 32_000, ignoreRecentFinish = true))
    }

    @Test
    fun `a retry attempt is still refused while another run is active`() {
        val marks = FakeMarks()
        val g = RunGuard(marks)
        marks.activeStartedMs = now
        assertFalse(g.tryBegin(T.PERIODIC, now + 1_000, ignoreRecentFinish = true))
    }

    @Test
    fun `a stale mark does not block and is replaced`() {
        val marks = FakeMarks().also { it.activeStartedMs = now - 500_000 }
        val g = RunGuard(marks)
        assertTrue(g.tryBegin(T.PERIODIC, now, false))
        assertEquals(now, marks.activeStartedMs)
    }

    @Test
    fun `end is safe without begin`() {
        val marks = FakeMarks()
        RunGuard(marks).end(now, completed = false)
        assertEquals(0L, marks.activeStartedMs)
    }

    @Test
    fun `eight simultaneous starters give exactly one winner`() {
        val g = RunGuard(FakeMarks())
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val wins = AtomicInteger()
        val done = CountDownLatch(8)
        repeat(8) { i ->
            pool.execute {
                start.await()
                if (g.tryBegin(if (i % 2 == 0) T.PERIODIC else T.WATCHDOG, now, false)) wins.incrementAndGet()
                done.countDown()
            }
        }
        start.countDown(); done.await(); pool.shutdown()
        assertEquals(1, wins.get())
    }

    @Test
    fun `blocksKick reflects the marks`() {
        val marks = FakeMarks()
        val g = RunGuard(marks)
        assertFalse(g.blocksKick(now, 900_000L))
        g.tryBegin(T.PERIODIC, now - 10_000, false)
        assertTrue(g.blocksKick(now, 900_000L))
        g.end(now - 5_000, completed = true)
        assertTrue(g.blocksKick(now, 900_000L))
        assertFalse(g.blocksKick(now + 900_000L, 900_000L))
    }
}
