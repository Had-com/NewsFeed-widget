package com.newsfeed.widget.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Ids that some store still knows about but that are not placed on the home screen any more.
 *
 * SAFETY: an empty [live] set means "the live set is unknown or nothing is placed" (the system
 * can transiently report no ids, e.g. at boot, or the provider lookup can fail). That must never
 * be read as "every widget was removed", so it yields no orphans at all.
 */
internal fun orphanedIds(known: Set<Int>, live: Set<Int>): Set<Int> =
    if (live.isEmpty()) emptySet() else known - live

/** `widget_<id>` (WidgetConfigStore / ConfigBackup key) -> id, or null for any other key. */
internal fun widgetIdFromConfigKey(name: String): Int? =
    if (name.startsWith("widget_")) name.removePrefix("widget_").toIntOrNull() else null

private val STATE_FILE = Regex("""appWidget-(\d+)\.preferences_pb""")

/** `appWidget-<id>.preferences_pb` (Glance per-widget state file) -> id, or null. */
internal fun widgetIdFromStateFileName(name: String): Int? =
    STATE_FILE.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()

/**
 * Removes per-widget leftovers for widget ids that no longer exist. Two entry points:
 *  - [removeIds]: the receiver's onDeleted(); drops the saved config and its backup
 *    (Glance's own receiver already deletes the appWidget-<id> state file for these).
 *  - [sweep]: run on every WidgetWorker refresh. Covers ids that vanished WITHOUT onDeleted, most
 *    importantly the removed NewsFeed Focus widgets (their receiver no longer exists, so nothing
 *    is called for them on update). Deletes config, backup and Glance state for every id not
 *    registered to the live receiver, and cancels the removed receiver's stale clock alarm.
 *    The caller passes the union of AppWidgetManager.getAppWidgetIds(provider) and the Glance
 *    ids; if that union is empty the sweep does nothing (see [orphanedIds]).
 * Both swallow failures (except cancellation): cleanup must never fail a refresh.
 */
object OrphanCleanup {
    private const val REMOVED_RECEIVER = "com.newsfeed.widget.glance.NewsFeedFocusWidgetReceiver"
    private const val REMOVED_CLOCK_ACTION = "com.newsfeed.widget.CLOCK_TICK_FOCUS"
    private const val REMOVED_CLOCK_RC = 1002

    suspend fun removeIds(context: Context, ids: Set<Int>) {
        try {
            delete(context, ids, includeGlanceState = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    suspend fun sweep(context: Context, liveIds: Set<Int>) {
        if (liveIds.isEmpty()) return
        try {
            val known = WidgetConfigStore(context).knownWidgetIds() +
                ConfigBackup.knownIds(context) + stateFileIds(context)
            delete(context, orphanedIds(known, liveIds), includeGlanceState = true)
            cancelRemovedFocusAlarm(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun stateDir(context: Context) = File(context.filesDir, "datastore")

    private fun stateFileIds(context: Context): Set<Int> =
        stateDir(context).listFiles()?.mapNotNull { widgetIdFromStateFileName(it.name) }?.toSet()
            ?: emptySet()

    private suspend fun delete(context: Context, ids: Set<Int>, includeGlanceState: Boolean) {
        if (ids.isEmpty()) return
        val store = WidgetConfigStore(context)
        for (id in ids) {
            store.delete(id)
            ConfigBackup.delete(context, id)
            if (includeGlanceState) File(stateDir(context), "appWidget-$id.preferences_pb").delete()
        }
    }

    // The removed Focus receiver's CLOCK_TICK_FOCUS alarm (request code 1002). Its receiver is
    // gone so a firing alarm is a silent no-op that is never re-armed, but cancel it anyway.
    // FLAG_NO_CREATE: only cancels a PendingIntent that already exists, never creates one.
    private fun cancelRemovedFocusAlarm(context: Context) {
        val intent = Intent(REMOVED_CLOCK_ACTION)
            .setComponent(ComponentName(context.packageName, REMOVED_RECEIVER))
        val pi = PendingIntent.getBroadcast(
            context, REMOVED_CLOCK_RC, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
        pi.cancel()
    }
}
