package com.newsfeed.widget.glance

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState

// Story threads (WidgetConfig.groupStories): the "+N" badge on a thread's lead row expands / collapses
// the thread, i.e. shows or hides its other members as rows right under the lead. Only the badge
// routes here; a tap on the row itself still does what the widget's tap mode says. Toggling touches
// nothing but WidgetStateKey.expandedThreadIds (and, on collapse, drops an expanded/focused id that
// points at a now-hidden member): it never marks anything read, because opening a thread is not
// "moving on" from an article.
class ToggleThreadCallback : ActionCallback {
    companion object {
        val LEAD_ID_KEY = ActionParameters.Key<String>("threadLeadId")
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val leadId = parameters[LEAD_ID_KEY] ?: return
        updateAppWidgetState(context, glanceId) { prefs -> toggleThreadExpanded(prefs, leadId) }
        NewsFeedWidget().update(context, glanceId)
    }
}
