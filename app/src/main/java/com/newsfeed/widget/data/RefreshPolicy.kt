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
