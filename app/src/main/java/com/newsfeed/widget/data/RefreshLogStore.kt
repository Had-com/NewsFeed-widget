package com.newsfeed.widget.data

import android.content.Context
import java.io.File

/**
 * File storage for the refresh log: `refresh_log.json` in filesDir, the last [REFRESH_LOG_MAX_RUNS] runs.
 * Same pattern as [CrashLogStore]: temp file + rename, so a failed write never erases the history.
 * Local only: nothing here is ever sent anywhere.
 */
object RefreshLogStore {
    private const val FILE_NAME = "refresh_log.json"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    // Synchronized: the periodic job and a one-time run can finish at the same moment.
    @Synchronized
    fun append(context: Context, entry: RefreshLogEntry) {
        writeAll(context, appendCapped(readAll(context), entry))
    }

    /** Oldest first. */
    @Synchronized
    fun readAll(context: Context): List<RefreshLogEntry> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return refreshLogFromJson(f.readText())
    }

    private fun writeAll(context: Context, entries: List<RefreshLogEntry>) {
        val target = file(context)
        val tmp = File(target.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(refreshLogToJson(entries))
        tmp.renameTo(target)
    }
}
