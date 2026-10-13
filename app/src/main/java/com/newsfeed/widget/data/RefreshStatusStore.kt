package com.newsfeed.widget.data

import android.content.Context

/**
 * Small process-wide refresh bookkeeping that broadcast receivers must read and write SYNCHRONOUSLY
 * (they cannot wait for DataStore): the shared periodic job's effective interval (BUG-034), when a
 * refresh last really fetched something, when the watchdog last kicked one (BUG-036) and which job
 * definition is enqueued. Per-widget display state (the footer) stays in the Glance state.
 *
 * Every value is a hint: if this file is lost, the worst case is one extra refresh or one extra
 * re-enqueue of the periodic job, never wrong data.
 */
class RefreshStatusStore(context: Context) : RunMarks, IdleRefreshSetting {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Effective interval of the shared periodic job, minutes. 0 = not known yet. */
    var intervalMinutes: Int
        get() = prefs.getInt(K_INTERVAL, 0)
        set(v) { prefs.edit().putInt(K_INTERVAL, v).apply() }

    /** Version of the periodic job definition last enqueued (see [WORK_SCHEMA_VERSION]). */
    var workSchemaVersion: Int
        get() = prefs.getInt(K_SCHEMA, 0)
        set(v) { prefs.edit().putInt(K_SCHEMA, v).apply() }

    /** Last time at least one feed was fetched successfully by any run. 0 = never. */
    var lastOkRefreshMs: Long
        get() = prefs.getLong(K_LAST_OK, 0L)
        set(v) { prefs.edit().putLong(K_LAST_OK, v).apply() }

    /** Last time the watchdog enqueued a catch-up refresh. 0 = never. */
    var lastKickMs: Long
        get() = prefs.getLong(K_LAST_KICK, 0L)
        set(v) { prefs.edit().putLong(K_LAST_KICK, v).apply() }

    /** Start of the run in progress (BUG-043, [RunGuard]). 0 = none. Taken and released by the worker only. */
    override var activeStartedMs: Long
        get() = prefs.getLong(K_RUN_ACTIVE, 0L)
        set(v) { prefs.edit().putLong(K_RUN_ACTIVE, v).apply() }

    /** End of the last run that completed (BUG-043, [RunGuard]). 0 = never. */
    override var lastFinishedMs: Long
        get() = prefs.getLong(K_RUN_FINISHED, 0L)
        set(v) { prefs.edit().putLong(K_RUN_FINISHED, v).apply() }

    /**
     * The Settings switch "Refresh while the phone is idle" (BUG-046), app-wide, default on. Read synchronously by
     * the alarm receiver and by every place that re-arms the alarm.
     */
    override var idleRefreshEnabled: Boolean
        get() = prefs.getBoolean(K_IDLE_ENABLED, DEFAULT_IDLE_REFRESH_ENABLED)
        set(v) { prefs.edit().putBoolean(K_IDLE_ENABLED, v).apply() }

    /**
     * When the idle alarm was last planned to fire (epoch ms), 0 = none planned (cancelled or never armed). Only for
     * the problem report: AlarmManager has no query for a pending alarm.
     */
    var idleAlarmDueMs: Long
        get() = prefs.getLong(K_IDLE_DUE, 0L)
        set(v) { prefs.edit().putLong(K_IDLE_DUE, v).apply() }

    /** Forget the job-specific values (the last widget was removed and the jobs cancelled). */
    fun clearJobState() {
        prefs.edit().remove(K_INTERVAL).remove(K_SCHEMA).apply()
    }

    private companion object {
        const val NAME = "newsfeed_refresh_status"
        const val K_INTERVAL = "interval_minutes"
        const val K_SCHEMA = "work_schema_version"
        const val K_LAST_OK = "last_ok_refresh_ms"
        const val K_LAST_KICK = "last_kick_ms"
        const val K_RUN_ACTIVE = "run_active_started_ms"
        const val K_RUN_FINISHED = "run_last_finished_ms"
        const val K_IDLE_ENABLED = "idle_refresh_enabled"
        const val K_IDLE_DUE = "idle_alarm_due_ms"
    }
}
