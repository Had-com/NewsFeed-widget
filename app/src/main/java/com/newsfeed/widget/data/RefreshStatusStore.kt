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
class RefreshStatusStore(context: Context) {
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
    }
}
