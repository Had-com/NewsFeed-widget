package com.newsfeed.widget.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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

    suspend fun delete(widgetId: Int) {
        context.dataStore.edit { prefs ->
            prefs.remove(keyFor(widgetId))
        }
    }
}
