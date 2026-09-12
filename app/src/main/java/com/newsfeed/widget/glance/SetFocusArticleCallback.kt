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

// Focus Mode only (BuildConfig.FOCUS_MODE build flavor — see FeedItemRow.kt's fontSize
// shadowing). Tapping a row sets it as the focused article, shrinking every other displayed
// row; tapping the already-focused row again clears focus, returning all rows to the normal
// configured size.
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
        var articleLosingFocusId: String? = null
        updateAppWidgetState(context, glanceId) { prefs ->
            val current = prefs[WidgetStateKey.focusedArticleId] ?: ""
            val movingToAnotherArticle = current.isNotBlank() && current != articleId
            prefs[WidgetStateKey.focusedArticleId] = if (current == articleId) "" else articleId
            // Each newly-focused article starts at AdjustFocusScaleCallback's default size —
            // an earlier +/- adjustment made while looking at a different article isn't a
            // choice about this one, so it shouldn't carry over silently.
            if (current != articleId) prefs.remove(WidgetStateKey.focusScale)

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
                        articleLosingFocusId = current
                    }
                }
            }
        }
        articleLosingFocusId?.let { ReadStatusStore(context).markRead(it) }
        NewsFeedFocusWidget().update(context, glanceId)
        UnreadGracePeriod.scheduleRefresh(context, glanceId, articleLosingFocusId) { c, g -> NewsFeedFocusWidget().update(c, g) }
    }
}
