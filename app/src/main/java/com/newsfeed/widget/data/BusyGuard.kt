package com.newsfeed.widget.data

import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-at-a-time guard for the report buttons (Preview, Share, Send). The flag is taken synchronously
 * BEFORE the work is launched, so a double tap cannot slip a second run past the check (BUG-039: the old
 * code tested the flag before `scope.launch` but set it inside the coroutine, after the second tap had passed).
 * Pure, so the rule is unit tested.
 */
class BusyGuard {
    private val flag = AtomicBoolean(false)

    val isBusy: Boolean get() = flag.get()

    /** True when the caller now owns the guard; false when something is already running. */
    fun tryAcquire(): Boolean = flag.compareAndSet(false, true)

    fun release() = flag.set(false)

    /** Runs [block] only when the guard was free, and always frees it afterwards. Returns whether it ran. */
    inline fun runGuarded(block: () -> Unit): Boolean {
        if (!tryAcquire()) return false
        try { block() } finally { release() }
        return true
    }
}

/**
 * [BusyGuard] plus a hold-off: after [end] the guard keeps rejecting for [durationMs] more (BUG-041). Needed for
 * the share sheet: `startActivity(chooser)` returns after tens of milliseconds but the chooser appears a few hundred
 * milliseconds later, so a second tap right after the first one used to find the guard free and stack a second
 * chooser. [now] is any monotonic clock in milliseconds (the caller passes SystemClock.elapsedRealtime()).
 */
class HoldOff(private val durationMs: Long) {
    private var busy = false
    private var holdUntil = Long.MIN_VALUE

    val isBusy: Boolean @Synchronized get() = busy

    /** True when the caller now owns the guard: nothing is running and the hold-off has elapsed. */
    @Synchronized
    fun tryBegin(now: Long): Boolean {
        // A hold-off further away than its length means the clock moved back: do not stay locked.
        if (holdUntil - now > durationMs) holdUntil = Long.MIN_VALUE
        if (busy || now < holdUntil) return false
        busy = true
        return true
    }

    /** Releases the guard; with [hold] (default) new taps stay rejected for [durationMs] after [now]. */
    @Synchronized
    fun end(now: Long, hold: Boolean = true) {
        busy = false
        holdUntil = if (hold) now + durationMs else Long.MIN_VALUE
    }
}
