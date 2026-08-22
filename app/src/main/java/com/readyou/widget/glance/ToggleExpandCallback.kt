package com.readyou.widget.glance

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.readyou.widget.data.WidgetStateKey

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
        updateAppWidgetState(context, glanceId) { prefs ->
            val current = prefs[WidgetStateKey.expandedArticleId] ?: ""
            prefs[WidgetStateKey.expandedArticleId] = if (current == articleId) "" else articleId
        }
        ReadYouWidget().update(context, glanceId)
    }
}
