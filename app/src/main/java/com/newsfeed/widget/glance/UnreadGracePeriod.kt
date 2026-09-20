package com.newsfeed.widget.glance

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.state.updateAppWidgetState
import com.newsfeed.widget.data.WidgetStateKey
import com.newsfeed.widget.data.graceRefreshDelays
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Glance never re-renders on its own between explicit update() calls, so a callback that
// marks an article read under "Unread only" also needs to trigger delayed follow-up updates:
// one per dissolve stage (the text turning into dots, see data/ArticleDissolve.kt) and a
// final one once the grace period elapses - otherwise the article would linger visible until
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
            // graceRefreshDelays() = +2.5s, +4s, +5s, each with a +100ms buffer so it fires
            // strictly after its boundary (the dissolve stage change / the filter excluding
            // the article), never before it. They share the constants the filter and the
            // dissolve stages use, so they can never silently drift out of sync. Each stage
            // is computed from that article's own readAt at render time, so one update
            // re-renders every article currently dissolving. Three updates per read article
            // at most, no polling.
            var elapsed = 0L
            for (target in graceRefreshDelays()) {
                delay(target - elapsed)
                elapsed = target
                // A widget removed during the delay (or any other transient failure updating
                // stale state) shouldn't crash this detached, fire-and-forget coroutine - it
                // runs outside Glance's own ActionCallback exception handling, unlike every
                // other update() call site in this codebase. Wrapped per iteration so one
                // failed update doesn't cancel the later ones.
                runCatching {
                    // Write a real, changed value before updating - Glance/Compose skips
                    // recomposing a widget whose observed Preferences are unchanged from last
                    // time, and these refreshes (dissolving, then excluding, an article)
                    // depend purely on wall-clock time, not on any actual Preferences change,
                    // so without a genuine write here the update() call below can be silently
                    // treated as a no-op. See WidgetStateKey.graceCheckTick.
                    updateAppWidgetState(context, glanceId) { prefs ->
                        prefs[WidgetStateKey.graceCheckTick] = System.currentTimeMillis()
                    }
                    update(context, glanceId)
                }
            }
        }
    }
}
