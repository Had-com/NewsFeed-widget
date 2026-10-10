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
