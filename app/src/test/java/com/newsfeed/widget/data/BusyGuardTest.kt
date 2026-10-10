package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class BusyGuardTest {

    @Test
    fun `the second acquire fails until release`() {
        val g = BusyGuard()
        assertFalse(g.isBusy)
        assertTrue(g.tryAcquire())
        assertTrue(g.isBusy)
        assertFalse(g.tryAcquire())
        g.release()
        assertFalse(g.isBusy)
        assertTrue(g.tryAcquire())
    }

    @Test
    fun `release is safe when not busy`() {
        val g = BusyGuard()
        g.release()
        assertTrue(g.tryAcquire())
    }

    @Test
    fun `runGuarded skips a second tap and always releases`() {
        val g = BusyGuard()
        var runs = 0
        // the first tap is still running (acquired, not released) when the second one arrives
        assertTrue(g.tryAcquire())
        assertFalse(g.runGuarded { runs++ })
        g.release()
        assertTrue(g.runGuarded { runs++ })
        assertEquals(1, runs)
        assertFalse(g.isBusy)
        // a throwing body still releases the guard
        try { g.runGuarded { error("boom") } } catch (_: IllegalStateException) {}
        assertFalse(g.isBusy)
    }

    @Test
    fun `only one of many concurrent taps gets in`() {
        val g = BusyGuard()
        val winners = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(8)
        repeat(8) {
            pool.execute { start.await(); if (g.tryAcquire()) winners.incrementAndGet(); done.countDown() }
        }
        start.countDown(); done.await(); pool.shutdown()
        assertEquals(1, winners.get())
    }
}

class HoldOffTest {

    @Test
    fun `free guard accepts and a running one rejects`() {
        val h = HoldOff(1500)
        assertTrue(h.tryBegin(1000))
        assertTrue(h.isBusy)
        assertFalse(h.tryBegin(1001))
    }

    @Test
    fun `after end the hold-off still rejects until it has elapsed`() {
        val h = HoldOff(1500)
        assertTrue(h.tryBegin(1000))
        h.end(1040) // startActivity returned after 40 ms, the chooser is not on screen yet
        assertFalse(h.isBusy)
        assertFalse(h.tryBegin(1041))
        assertFalse(h.tryBegin(2539))
        assertTrue(h.tryBegin(2540))
    }

    @Test
    fun `end without a hold releases at once`() {
        val h = HoldOff(1500)
        assertTrue(h.tryBegin(1000))
        h.end(1010, hold = false)
        assertTrue(h.tryBegin(1011))
    }

    @Test
    fun `a rejected tap does not extend the hold-off`() {
        val h = HoldOff(1500)
        h.tryBegin(0); h.end(0)
        assertFalse(h.tryBegin(1000))
        assertTrue(h.tryBegin(1500))
    }

    @Test
    fun `a clock that moved back cannot lock the guard for long`() {
        val h = HoldOff(1500)
        h.tryBegin(10_000); h.end(10_000)
        assertTrue(h.tryBegin(2_000))
    }

    @Test
    fun `only one of many concurrent taps gets in`() {
        val h = HoldOff(1500)
        val winners = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(8)
        repeat(8) {
            pool.execute { start.await(); if (h.tryBegin(5_000)) winners.incrementAndGet(); done.countDown() }
        }
        start.countDown(); done.await(); pool.shutdown()
        assertEquals(1, winners.get())
    }
}
