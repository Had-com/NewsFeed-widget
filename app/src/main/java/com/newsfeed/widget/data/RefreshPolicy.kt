package com.newsfeed.widget.data

/**
 * Pure refresh-scheduling rules (no Android types), so they are unit tested on the JVM.
 * The WorkManager / AlarmManager code in glance/WidgetWorker.kt and glance/NewsFeedWidget.kt only
 * applies what these functions decide.
 */

/** WorkManager never runs a periodic job more often than this. */
const val MIN_REFRESH_INTERVAL_MINUTES = 15

/**
 * Version of the WorkManager job definition (its constraints). Raise it whenever the request built
 * by `WidgetWorker.periodicRequest` changes in a way an already-enqueued job would not pick up by
 * itself; app start then re-enqueues it once (UPDATE policy) instead of waiting for a reboot.
 * 1 = periodic job requires a connected network (BUG-034).
 */
const val WORK_SCHEMA_VERSION = 1

/**
 * The interval of the ONE shared periodic refresh job (BUG-034). Every widget has its own
 * "Refresh every" setting but there is a single job, so it runs at the smallest interval any
 * widget asks for (a widget set to 15 min must not be starved by another set to 12 h). Never
 * below [MIN_REFRESH_INTERVAL_MINUTES]; with no widget config at all, the default (the minimum).
 */
fun effectiveRefreshIntervalMinutes(configs: Collection<WidgetConfig>): Int =
    configs.minOfOrNull { it.refreshIntervalMinutes.coerceAtLeast(MIN_REFRESH_INTERVAL_MINUTES) }
        ?: MIN_REFRESH_INTERVAL_MINUTES

/** True when the job stored by an older build must be re-enqueued with the current definition. */
fun workNeedsMigration(storedVersion: Int, currentVersion: Int = WORK_SCHEMA_VERSION): Boolean =
    storedVersion < currentVersion

/**
 * Watchdog behind the clock tick (BUG-036). The periodic job can be missed for reasons the app cannot
 * see (Doze, a force-stop that wiped it, a blocked network window). True when no refresh has fetched
 * anything for more than TWO intervals and no catch-up was enqueued during the last interval, so a
 * burst of ticks causes one catch-up and a permanently failing network causes at most one per interval.
 * Clock changes: a "last" time in the future counts as overdue / as expired respectively.
 */
fun shouldKickRefresh(lastOkRefreshMs: Long, nowMs: Long, intervalMs: Long, lastKickMs: Long): Boolean {
    if (intervalMs <= 0L) return false
    val staleForMs = if (lastOkRefreshMs > nowMs) Long.MAX_VALUE else nowMs - lastOkRefreshMs
    if (staleForMs <= 2 * intervalMs) return false
    val kickedWithinInterval = lastKickMs in 1..nowMs && nowMs - lastKickMs < intervalMs
    return !kickedWithinInterval
}

const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"

/**
 * Which system broadcasts make BootReceiver re-arm the refresh machinery (BUG-036). BOOT_COMPLETED (after
 * a reboot the alarm chain is gone) and MY_PACKAGE_REPLACED (an app update cancels the alarms and the
 * widget is not necessarily re-created). LOCKED_BOOT_COMPLETED is deliberately NOT handled: the receiver
 * is not directBootAware, and the saved configs live in credential-protected storage (DataStore), which
 * is not readable before the first unlock; BOOT_COMPLETED arrives after it.
 */
fun shouldRearmOnBroadcast(action: String?): Boolean =
    action == ACTION_BOOT_COMPLETED || action == ACTION_MY_PACKAGE_REPLACED
