package com.newsfeed.widget.glance

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.newsfeed.widget.data.ReadStatusStore
import com.newsfeed.widget.data.WidgetStateKey
import com.newsfeed.widget.data.shouldScheduleGraceRefreshForConfig

// Expand mode only. Tapping a row expands it inline (only one article expanded at a time);
// tapping the expanded row again collapses it.
//
// Read is flagged on the previously tapped article once the user taps a DIFFERENT one - not the
// moment an article is pressed, which was too early (reported live). The last-tapped tracking is
// shared with NoOpTapFeedbackCallback (see markPreviousTappedRead), so it works the same whether
// or not the articles involved expand. Nothing is marked on the first press or on collapsing.
// The external "Open article" button uses actionStartActivity(), which can't run a suspend
// body, so it marks nothing itself.
class ToggleExpandCallback : ActionCallback {
    companion object {
        val ARTICLE_ID_KEY = ActionParameters.Key<String>("articleId")
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val articleId = parameters[ARTICLE_ID_KEY] ?: return
        // Only set when the previously tapped article was actually unread - this is what
        // genuinely flips isRead false -> true, so it is both what ReadStatusStore should be
        // told about and what should get a grace-period refresh scheduled.
        // Stamped into readAt AND used as the base for the dissolve/removal refresh delays.
        val markedAt = System.currentTimeMillis()
        var markedReadId: String? = null
        var scheduleGrace = true
        updateAppWidgetState(context, glanceId) { prefs ->
            val current = prefs[WidgetStateKey.expandedArticleId] ?: ""
            prefs[WidgetStateKey.expandedArticleId] = if (current != articleId) articleId else ""
            markedReadId = markPreviousTappedRead(prefs, articleId, markedAt)
            scheduleGrace = shouldScheduleGraceRefreshForConfig(prefs[WidgetStateKey.configJson])
        }
        markedReadId?.let { ReadStatusStore(context).markRead(it) }
        NewsFeedWidget().update(context, glanceId)
        // Dissolve/removal only exist under Show = Unread only; skip the 4 delayed renders otherwise.
        if (scheduleGrace) UnreadGracePeriod.scheduleRefresh(context, glanceId, markedReadId, markedAt) { c, g -> NewsFeedWidget().update(c, g) }
    }
}
