package com.newsfeed.widget.glance

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.state.updateAppWidgetState
import com.newsfeed.widget.data.UNREAD_GRACE_PERIOD_MS
import com.newsfeed.widget.data.WidgetStateKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Glance never re-renders on its own between explicit update() calls, so a callback that
// marks an article read under "Unread only" also needs to trigger one delayed follow-up
// update once the grace period elapses - otherwise the article would linger visible until
// some unrelated future refresh instead of actually disappearing after 5 seconds.
object UnreadGracePeriod {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // No-op if nothing was actually marked read this action (markedArticleId is null) - e.g.
    // a toggle-off in Focus Mode, or an already-expanded/already-focused article being
    // re-tapped, or an article that was already read before this tap.
    fun scheduleRefresh(
        context: Context,
        glanceId: GlanceId,
        markedArticleId: String?,
        update: suspend (Context, GlanceId) -> Unit,
    ) {
        if (markedArticleId == null) return
        scope.launch {
            // +100ms buffer past ArticleSorting.UNREAD_GRACE_PERIOD_MS's own window, so this
            // fires strictly after the filter would exclude the article, never before it -
            // shares the same constant rather than a second hardcoded number, so the two can
            // never silently drift out of sync.
            delay(UNREAD_GRACE_PERIOD_MS + 100L)
            // A widget removed during the delay (or any other transient failure updating
            // stale state) shouldn't crash this detached, fire-and-forget coroutine - it
            // runs outside Glance's own ActionCallback exception handling, unlike every
            // other update() call site in this codebase.
            runCatching {
                // Write a real, changed value before updating - Glance/Compose skips
                // recomposing a widget whose observed Preferences are unchanged from last
                // time, and this refresh's whole purpose (excluding an article whose grace
                // period just elapsed) depends purely on wall-clock time, not on any actual
                // Preferences change, so without a genuine write here the update() call below
                // can be silently treated as a no-op. See WidgetStateKey.graceCheckTick.
                updateAppWidgetState(context, glanceId) { prefs ->
                    prefs[WidgetStateKey.graceCheckTick] = System.currentTimeMillis()
                }
                update(context, glanceId)
            }
        }
    }
}
