package com.newsfeed.widget.glance

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.ReadStatusStore
import com.newsfeed.widget.data.WidgetStateKey
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
        var didExpand = false
        updateAppWidgetState(context, glanceId) { prefs ->
            val current = prefs[WidgetStateKey.expandedArticleId] ?: ""
            didExpand = current != articleId
            prefs[WidgetStateKey.expandedArticleId] = if (didExpand) articleId else ""

            // Expanding an article counts as having seen it. Read status also needs marking
            // here now — the external "Open article" button uses actionStartActivity(), which
            // (unlike a custom ActionCallback) can't run a suspend body of its own.
            if (didExpand) {
                val articles = prefs[WidgetStateKey.articles]
                    ?.let { runCatching { Json.decodeFromString<List<ArticleItem>>(it) }.getOrNull() }
                if (articles != null) {
                    prefs[WidgetStateKey.articles] = Json.encodeToString(
                        articles.map { if (it.id == articleId) it.copy(isRead = true) else it }
                    )
                }
            }
        }
        if (didExpand) ReadStatusStore(context).markRead(articleId)
        NewsFeedWidget().update(context, glanceId)
    }
}
