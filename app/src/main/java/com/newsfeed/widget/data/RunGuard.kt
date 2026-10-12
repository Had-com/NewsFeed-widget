package com.newsfeed.widget.data

/**
 * Cross-run guard (BUG-043): the periodic job, the watchdog's one-time run and a tap are three separate
 * WorkManager jobs (different unique names), so nothing stopped them from fetching every feed at the same
 * moment, which doubled the requests (problem report #17: 7 of 20 runs started in the same second, 22 of 22
 * on another phone). The worker takes a mark when it starts and releases it when it ends; a run that finds
 * another one active, or one that has just finished, ends at once without fetching.
 *
 * Pure (no Android types), unit tested. The marks live in SharedPreferences ([RefreshStatusStore]) so a
 * restarted process still sees them; the atomic take is the in-process lock in [RunGuard].
 */

/** An active mark older than this is a crashed run's leftover and is ignored. */
const val RUN_ACTIVE_STALE_MS = 120_000L

/** Background runs (periodic, watchdog, other) wait this long after any run finished. */
const val RUN_BACKGROUND_QUIET_MS = 90_000L

/** A tap is held back for this long after a run finished, so rapid taps collapse into one. */
const val RUN_TAP_FLOOR_MS = 15_000L

private fun withinWindow(nowMs: Long, markMs: Long, windowMs: Long): Boolean =
    markMs > 0L && nowMs - markMs in 0L until windowMs

/** A run started less than [RUN_ACTIVE_STALE_MS] ago and has not ended. A mark in the future (clock moved back) is ignored. */
fun isRunActive(nowMs: Long, activeRunStartedMs: Long): Boolean =
    withinWindow(nowMs, activeRunStartedMs, RUN_ACTIVE_STALE_MS)

/**
 * Whether a run must end at once without fetching.
 *  - PERIODIC, WATCHDOG and OTHER: skipped while another run is active, or when any run finished within the
 *    last [RUN_BACKGROUND_QUIET_MS].
 *  - MANUAL_TAP: skipped while a run is active, or within [RUN_TAP_FLOOR_MS] of the last finish.
 *  - SETTINGS_SAVE and WIDGET_ADDED: skipped only while a run is active (the user just changed the feeds or
 *    placed the widget: a run that merely finished a moment ago must not swallow that refresh).
 * `0` means "never". A mark in the future counts as not set.
 */
fun shouldSkipRun(trigger: RefreshTrigger, nowMs: Long, activeRunStartedMs: Long, lastRunFinishedMs: Long): Boolean {
    if (isRunActive(nowMs, activeRunStartedMs)) return true
    return when (trigger) {
        RefreshTrigger.PERIODIC, RefreshTrigger.WATCHDOG, RefreshTrigger.OTHER ->
            withinWindow(nowMs, lastRunFinishedMs, RUN_BACKGROUND_QUIET_MS)
        RefreshTrigger.MANUAL_TAP -> withinWindow(nowMs, lastRunFinishedMs, RUN_TAP_FLOOR_MS)
        RefreshTrigger.SETTINGS_SAVE, RefreshTrigger.WIDGET_ADDED -> false
    }
}

/** The watchdog does not enqueue a catch-up while a run is active or after a run finished within the last interval. */
fun runBlocksKick(nowMs: Long, activeRunStartedMs: Long, lastRunFinishedMs: Long, intervalMs: Long): Boolean =
    isRunActive(nowMs, activeRunStartedMs) || (intervalMs > 0L && withinWindow(nowMs, lastRunFinishedMs, intervalMs))

/** Where the two marks are kept. */
interface RunMarks {
    /** Start time of the run in progress, 0 = none. */
    var activeStartedMs: Long
    /** End time of the last run that completed, 0 = never. */
    var lastFinishedMs: Long
}

/** The marks plus the lock that makes check-and-take atomic. */
class RunGuard(private val marks: RunMarks, private val lock: Any = GLOBAL_LOCK) {

    /**
     * Takes the active mark unless [shouldSkipRun] says to skip; true = the caller owns the run and MUST call
     * [end] in a `finally`. [ignoreRecentFinish] is for WorkManager's backed-off retry of a run that failed
     * everywhere: it starts about 30 s after that run ended and must not be refused for it (it is still refused
     * while another run is active).
     */
    fun tryBegin(trigger: RefreshTrigger, nowMs: Long, ignoreRecentFinish: Boolean): Boolean = synchronized(lock) {
        val finished = if (ignoreRecentFinish) 0L else marks.lastFinishedMs
        if (shouldSkipRun(trigger, nowMs, marks.activeStartedMs, finished)) return false
        marks.activeStartedMs = nowMs
        true
    }

    /** Releases the mark. [completed] = the run reached its end (not cancelled), which starts the quiet period. */
    fun end(nowMs: Long, completed: Boolean) = synchronized(lock) {
        marks.activeStartedMs = 0L
        if (completed) marks.lastFinishedMs = nowMs
    }

    fun blocksKick(nowMs: Long, intervalMs: Long): Boolean = synchronized(lock) {
        runBlocksKick(nowMs, marks.activeStartedMs, marks.lastFinishedMs, intervalMs)
    }

    private companion object {
        val GLOBAL_LOCK = Any()
    }
}
