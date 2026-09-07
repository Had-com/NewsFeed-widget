package com.newsfeed.widget.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.backupDataStore by preferencesDataStore(name = "newsfeed_config_backup")
private val backupJson = Json { ignoreUnknownKeys = true }

/**
 * A safety net around the self-update flow, not the primary mechanism for surviving an
 * update — a normal Android app update (installing a new APK over an existing one with the
 * same signing key, exactly what UpdateManager does) already preserves all app data,
 * including every widget's configured feeds, with no extra code needed. This exists for the
 * edge cases where that wouldn't hold: an update interrupted mid-install, a signing-key
 * mismatch forcing an uninstall+reinstall, or DataStore corruption. Kept in a completely
 * separate DataStore file from the live config so a live-config read/write failure can't
 * also take out the backup.
 */
object ConfigBackup {
    private fun keyFor(widgetId: Int) = stringPreferencesKey("widget_$widgetId")

    suspend fun backupAll(context: Context, widgetIds: List<Int>) {
        val store = WidgetConfigStore(context)
        context.backupDataStore.edit { prefs ->
            for (id in widgetIds) {
                val config = store.configFlow(id).first()
                // Never overwrite a good backup with an empty one — if the live config is
                // already empty (e.g. a widget mid-first-setup), the PREVIOUS backup (if any)
                // is more useful than none at all.
                if (config.feeds.isNotEmpty()) {
                    prefs[keyFor(id)] = backupJson.encodeToString(config)
                }
            }
        }
    }

    // Called on every WidgetWorker refresh cycle, not just right after an update — cheap
    // (a single extra DataStore read when feeds are already present is a no-op) and it means
    // this recovers automatically the next time this widget refreshes after ANY event that
    // wiped its feeds, not only the self-update path specifically.
    suspend fun restoreIfEmpty(context: Context, widgetId: Int, currentConfig: WidgetConfig): WidgetConfig {
        if (currentConfig.feeds.isNotEmpty()) return currentConfig
        val backedUp = context.backupDataStore.data.first()[keyFor(widgetId)]
            ?.let { runCatching { backupJson.decodeFromString<WidgetConfig>(it) }.getOrNull() }
            ?: return currentConfig
        val restored = currentConfig.copy(feeds = backedUp.feeds, feedOrder = backedUp.feedOrder)
        WidgetConfigStore(context).save(restored)
        return restored
    }
}
