package com.newsfeed.widget.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Phase 1 of the bug logger (see docs/superpowers/specs/2026-09-10-bug-logger-design.md):
 * local-only crash detection and logging. No network transport, no GitHub interaction -
 * shipping a GitHub credential inside this distributed APK would be a real security risk
 * given this repo's self-update mechanism. See the design doc's "Transport" decision.
 */
object CrashLogStore {
    private const val FILE_NAME = "crash_log.json"
    private const val MAX_ENTRIES = 50
    private const val MAX_STACK_TRACE_CHARS = 4000

    data class CrashRecord(
        val timestamp: Long,
        val versionCode: Int,
        val exceptionType: String,
        val message: String,
        val stackTrace: String,
    )

    data class BugSummary(
        val exceptionType: String,
        val message: String,
        val lastSeenAt: Long,
        val lastSeenVersionCode: Int,
        val occurrenceCount: Int,
        val isSolved: Boolean,
    )

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun record(context: Context, throwable: Throwable) {
        val record = CrashRecord(
            timestamp = System.currentTimeMillis(),
            versionCode = com.newsfeed.widget.BuildConfig.VERSION_CODE,
            exceptionType = throwable.javaClass.name,
            message = (throwable.message ?: "").take(300),
            stackTrace = Log.getStackTraceString(throwable).take(MAX_STACK_TRACE_CHARS),
        )
        val updated = (readAll(context) + record).takeLast(MAX_ENTRIES)
        writeAll(context, updated)
    }

    fun readAll(context: Context): List<CrashRecord> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CrashRecord(
                    timestamp = o.getLong("timestamp"),
                    versionCode = o.getInt("versionCode"),
                    exceptionType = o.getString("exceptionType"),
                    message = o.getString("message"),
                    stackTrace = o.getString("stackTrace"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(context: Context, records: List<CrashRecord>) {
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(JSONObject().apply {
                put("timestamp", r.timestamp)
                put("versionCode", r.versionCode)
                put("exceptionType", r.exceptionType)
                put("message", r.message)
                put("stackTrace", r.stackTrace)
            })
        }
        file(context).writeText(arr.toString())
    }

    // Groups raw records by (exceptionType, message) - repeated identical crashes are the
    // "same bug," not N separate ones - and derives Solved/Unsolved from whether the most
    // recent occurrence of that signature was logged under an older versionCode than the
    // app's current one. Needs no separately-tracked "last update" timestamp - see the
    // design doc's "Solved status" decision.
    fun summarize(records: List<CrashRecord>, currentVersionCode: Int): List<BugSummary> {
        return records
            .groupBy { it.exceptionType to it.message }
            .map { (key, group) ->
                val latest = group.maxBy { it.timestamp }
                BugSummary(
                    exceptionType = key.first,
                    message = key.second,
                    lastSeenAt = latest.timestamp,
                    lastSeenVersionCode = latest.versionCode,
                    occurrenceCount = group.size,
                    isSolved = latest.versionCode < currentVersionCode,
                )
            }
            .sortedByDescending { it.lastSeenAt }
    }
}
