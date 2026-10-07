package com.newsfeed.widget.glance

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.newsfeed.widget.data.ReadStatusStore
import com.newsfeed.widget.data.WidgetStateKey
import com.newsfeed.widget.data.shouldScheduleGraceRefreshForConfig

// Focus mode only (WidgetConfig.tapMode == "focus", see FeedItemRow.kt's row scaling).
// Tapping a row sets it as the focused article (that row enlarges; every other row keeps its
// normal size); tapping the already-focused row again clears focus. The state change lives in
// applyFocusTap (TapRouting.kt). The −/+ zoom (focusScale) is a persistent per-widget preference
// and is never touched here: moving or clearing focus keeps the size the user chose.
//
// Read is flagged on the article LOSING focus, once the user has actually moved on to
// another one — not the moment an article is tapped, which was too early (reported live: the
// user hasn't read the enlarged text yet at that instant). There's no separate "step to
// another article" path anymore (the ▲/▼ step buttons were removed alongside this change,
// since every row is directly tappable), so this is the only place Focus Mode marks anything
// read.
class SetFocusArticleCallback : ActionCallback {
    companion object {
        val ARTICLE_ID_KEY = ActionParameters.Key<String>("articleId")
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val articleId = parameters[ARTICLE_ID_KEY] ?: return
        // Only set when the article losing focus was actually unread — re-tapping through
        // already-read articles shouldn't reset their readAt and restart a grace period that
        // doesn't apply to them.
        val markedAt = System.currentTimeMillis()
        var articleLosingFocusId: String? = null
        var scheduleGrace = true
        updateAppWidgetState(context, glanceId) { prefs ->
            scheduleGrace = shouldScheduleGraceRefreshForConfig(prefs[WidgetStateKey.configJson])
            articleLosingFocusId = applyFocusTap(prefs, articleId, markedAt)
        }
        articleLosingFocusId?.let { ReadStatusStore(context).markRead(it) }
        NewsFeedWidget().update(context, glanceId)
        // Dissolve/removal only exist under Show = Unread only; skip the 4 delayed renders otherwise.
        if (scheduleGrace) UnreadGracePeriod.scheduleRefresh(context, glanceId, articleLosingFocusId, markedAt) { c, g -> NewsFeedWidget().update(c, g) }
    }
}
