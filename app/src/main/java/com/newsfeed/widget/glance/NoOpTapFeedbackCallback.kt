package com.newsfeed.widget.glance

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.newsfeed.widget.data.ReadStatusStore

// For articles whose RSS provides no <description> at all (e.g. ynet's "מבזקים" flash
// ticker, rotter.net's forum feed — see NewsFeedRepository.kt) there is nothing to expand
// into: no teaser text, and per the user's request, no "Open article" link either. Rather than
// leaving the row non-clickable (which drops Android's built-in press ripple entirely, so a
// tap would look and feel unresponsive), this is wired to the row's .clickable() instead of
// ToggleExpandCallback — the tap still gets the native ripple/press feedback, it just doesn't
// expand anything or navigate anywhere.
//
// It participates in the same last-tapped tracking as ToggleExpandCallback: an article with
// nothing to expand is still "the one you're on", and it is marked read when you tap a
// different article, expandable or not (see markPreviousTappedRead) - never on its own press.
// Marking sets readAt and schedules a grace-period refresh, same as the other callbacks.
class NoOpTapFeedbackCallback : ActionCallback {
    companion object {
        val ARTICLE_ID_KEY = ActionParameters.Key<String>("articleId")
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val articleId = parameters[ARTICLE_ID_KEY] ?: return
        var markedReadId: String? = null
        updateAppWidgetState(context, glanceId) { prefs ->
            markedReadId = markPreviousTappedRead(prefs, articleId)
        }
        markedReadId?.let { ReadStatusStore(context).markRead(it) }
        // Only re-render when something actually changed (the previous article's unread dot
        // cleared) — a plain tap changes nothing visible, and the native press ripple already
        // fired regardless.
        if (markedReadId != null) NewsFeedWidget().update(context, glanceId)
        UnreadGracePeriod.scheduleRefresh(context, glanceId, markedReadId) { c, g -> NewsFeedWidget().update(c, g) }
    }
}
