package com.newsfeed.widget.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.releaseNotesDataStore by preferencesDataStore(name = "release_notes")
private val LAST_SEEN_ID = intPreferencesKey("last_seen_release_note_id")

/**
 * Tracks which release notes (docs/RELEASE_NOTES.md) the user has already been shown, in its
 * own small DataStore file — mirroring ConfigBackup.kt's pattern of a small dedicated store
 * rather than piggybacking on per-widget WidgetConfig. lastSeenId is a single, app-wide
 * value, not tied to any one widget instance: a device can have multiple placed widgets, but
 * there's only one "has this device's user seen the notes" answer.
 */
object ReleaseNotesStore {
    suspend fun lastSeenId(context: Context): Int =
        context.releaseNotesDataStore.data.first()[LAST_SEEN_ID] ?: 0

    suspend fun markSeen(context: Context, upToId: Int) {
        context.releaseNotesDataStore.edit { prefs -> prefs[LAST_SEEN_ID] = upToId }
    }
}
