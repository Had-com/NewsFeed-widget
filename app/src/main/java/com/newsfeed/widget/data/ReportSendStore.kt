package com.newsfeed.widget.data

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * What "Send to developer" remembers on the phone, in the existing `report_settings` SharedPreferences:
 *  - `client_id`: a random UUID v4 made once per install (NEVER derived from the device or the account). It only
 *    lets the server apply its per-install daily limit; it is not a device identifier.
 *  - `last_sent_ms` / `last_sent_id`: when the last report went out and its number, for the "Last sent" line and
 *    the cooldown that stops an accidental second send.
 * The storage is behind [ReportKeyValueStore] so the rules are unit tested without Android.
 */

const val REPORT_PREFS_NAME = "report_settings"
const val UPLOAD_COOLDOWN_MS = 60_000L

private const val K_CLIENT_ID = "client_id"
private const val K_LAST_SENT_MS = "last_sent_ms"
private const val K_LAST_SENT_ID = "last_sent_id"

interface ReportKeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
}

class SharedPrefsReportStore(context: Context) : ReportKeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences(REPORT_PREFS_NAME, Context.MODE_PRIVATE)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)
    override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }
}

private val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

fun isValidClientId(value: String): Boolean = UUID_V4.matches(value)

/** The install's random id: created on first use, then kept. A damaged stored value is replaced. */
fun resolveClientId(store: ReportKeyValueStore, generate: () -> String = { UUID.randomUUID().toString() }): String {
    store.getString(K_CLIENT_ID)?.let { if (isValidClientId(it)) return it }
    val fresh = generate()
    store.putString(K_CLIENT_ID, fresh)
    return fresh
}

data class LastSent(val timeMs: Long, val id: Long?)

fun readLastSent(store: ReportKeyValueStore): LastSent? {
    val t = store.getLong(K_LAST_SENT_MS, 0L)
    if (t <= 0L) return null
    val id = store.getLong(K_LAST_SENT_ID, -1L)
    return LastSent(t, id.takeIf { it >= 0 })
}

fun recordLastSent(store: ReportKeyValueStore, timeMs: Long, id: Long?) {
    store.putLong(K_LAST_SENT_ID, id ?: -1L)
    store.putLong(K_LAST_SENT_MS, timeMs)
}

/** `Last sent: Oct 10, 14:03 (#42)`. */
fun lastSentText(last: LastSent, zone: ZoneId, locale: Locale): String {
    val time = DateTimeFormatter.ofPattern("MMM d, HH:mm", locale).withZone(zone).format(Instant.ofEpochMilli(last.timeMs))
    return "Last sent: $time" + (last.id?.let { " (#$it)" } ?: "")
}

/** Milliseconds still to wait after the last successful send; 0 when free, never sent, or the clock moved back. */
fun cooldownRemainingMs(lastSentMs: Long, nowMs: Long, cooldownMs: Long = UPLOAD_COOLDOWN_MS): Long {
    if (lastSentMs <= 0L || lastSentMs > nowMs) return 0L
    return (lastSentMs + cooldownMs - nowMs).coerceIn(0L, cooldownMs)
}

fun cooldownSeconds(remainingMs: Long): Int = ((remainingMs + 999) / 1000).toInt().coerceAtLeast(1)

fun cooldownMessage(remainingMs: Long): String {
    val s = cooldownSeconds(remainingMs)
    return "You just sent a report. Please wait $s ${if (s == 1) "second" else "seconds"} before sending another, or use Share report."
}
