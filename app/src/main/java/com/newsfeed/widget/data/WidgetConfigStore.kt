package com.newsfeed.widget.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "newsfeed_config")

private val json = Json { ignoreUnknownKeys = true }

class WidgetConfigStore(private val context: Context) {

    private fun keyFor(widgetId: Int) = stringPreferencesKey("widget_$widgetId")

    fun configFlow(widgetId: Int): Flow<WidgetConfig> =
        context.dataStore.data.map { prefs ->
            prefs[keyFor(widgetId)]
                ?.let { runCatching { json.decodeFromString<WidgetConfig>(it) }.getOrNull() }
                ?: WidgetConfig(widgetId = widgetId)
        }

    suspend fun save(config: WidgetConfig) {
        context.dataStore.edit { prefs ->
            prefs[keyFor(config.widgetId)] = json.encodeToString(config)
        }
    }

    /** Every widget id that currently has a saved config entry (keys named widget_<id>). */
    suspend fun knownWidgetIds(): Set<Int> =
        context.dataStore.data.first().asMap().keys
            .mapNotNull { widgetIdFromConfigKey(it.name) }.toSet()

    /**
     * The saved configs (entries that really exist; a widget with no saved entry is skipped, not
     * defaulted) of [onlyIds], or of every widget when [onlyIds] is null. Used to derive the one
     * interval the shared periodic job runs at (effectiveRefreshIntervalMinutes).
     */
    suspend fun savedConfigs(onlyIds: Set<Int>? = null): List<WidgetConfig> =
        context.dataStore.data.first().asMap().mapNotNull { (key, value) ->
            val id = widgetIdFromConfigKey(key.name) ?: return@mapNotNull null
            if (onlyIds != null && id !in onlyIds) return@mapNotNull null
            (value as? String)?.let { runCatching { json.decodeFromString<WidgetConfig>(it) }.getOrNull() }
        }

    suspend fun delete(widgetId: Int) {
        context.dataStore.edit { prefs ->
            prefs.remove(keyFor(widgetId))
        }
    }
}
