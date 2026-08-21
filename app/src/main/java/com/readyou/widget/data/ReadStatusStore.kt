package com.readyou.widget.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.readStatusDataStore by preferencesDataStore(name = "readyou_widget_read_status")
private val READ_IDS_KEY = stringPreferencesKey("read_article_ids")
private const val MAX_STORED = 500   // oldest entries are dropped beyond this

class ReadStatusStore(private val context: Context) {

    fun readIdsFlow(): Flow<Set<String>> =
        context.readStatusDataStore.data.map { prefs ->
            prefs[READ_IDS_KEY]
                ?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrDefault(emptyList()) }
                ?.toSet()
                ?: emptySet()
        }

    suspend fun markRead(articleId: String) {
        context.readStatusDataStore.edit { prefs ->
            val current: List<String> = prefs[READ_IDS_KEY]
                ?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrDefault(emptyList()) }
                ?: emptyList()
            val updated = (current + articleId).distinct().takeLast(MAX_STORED)
            prefs[READ_IDS_KEY] = Json.encodeToString(updated)
        }
    }
}
