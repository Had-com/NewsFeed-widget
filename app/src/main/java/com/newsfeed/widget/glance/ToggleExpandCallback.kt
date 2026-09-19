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

// Standard widget only. Tapping a row expands it inline (only one article expanded at a time);
// tapping the expanded row again collapses it.
//
// Read is flagged on the article LOSING expansion, once the user has actually moved on to
// another one - not the moment an article is pressed, which was too early (reported live).
// This mirrors Focus mode's SetFocusArticleCallback: nothing is marked on the first press or on
// collapsing the expanded article. Read status also needs marking here because the external
// "Open article" button uses actionStartActivity(), which (unlike a custom ActionCallback)
// can't run a suspend body of its own.
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
        // Only set when the article losing expansion was actually unread - this is what
        // genuinely flips isRead false -> true, so it is both what ReadStatusStore should be
        // told about and what should get a grace-period refresh scheduled. Re-marking an
        // already-read article isn't a new "just read" moment and must not reset its readAt.
        var markedReadId: String? = null
        updateAppWidgetState(context, glanceId) { prefs ->
            val current = prefs[WidgetStateKey.expandedArticleId] ?: ""
            val movingToAnotherArticle = current.isNotBlank() && current != articleId
            prefs[WidgetStateKey.expandedArticleId] = if (current != articleId) articleId else ""

            if (movingToAnotherArticle) {
                val articles = prefs[WidgetStateKey.articles]
                    ?.let { runCatching { Json.decodeFromString<List<ArticleItem>>(it) }.getOrNull() }
                if (articles != null) {
                    val target = articles.firstOrNull { it.id == current }
                    if (target != null && !target.isRead) {
                        val now = System.currentTimeMillis()
                        prefs[WidgetStateKey.articles] = Json.encodeToString(
                            articles.map { if (it.id == current) it.copy(isRead = true, readAt = now) else it }
                        )
                        markedReadId = current
                    }
                }
            }
        }
        markedReadId?.let { ReadStatusStore(context).markRead(it) }
        NewsFeedWidget().update(context, glanceId)
        UnreadGracePeriod.scheduleRefresh(context, glanceId, markedReadId) { c, g -> NewsFeedWidget().update(c, g) }
    }
}
