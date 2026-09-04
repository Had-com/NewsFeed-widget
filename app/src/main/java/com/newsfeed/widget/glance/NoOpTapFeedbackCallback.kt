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

// For articles whose RSS provides no <description> at all (e.g. ynet's "מבזקים" flash
// ticker, rotter.net's forum feed — see NewsFeedRepository.kt) there is nothing to expand
// into: no teaser text, and per the user's request, no "Open article" link either, since
// that was the only thing an expand ever revealed for these and always looked like a dead
// end. Rather than leaving the row non-clickable (which drops Android's built-in press
// ripple entirely, so a tap would look and feel unresponsive), this is wired to the row's
// .clickable() instead of ToggleExpandCallback — the tap still gets the native ripple/press
// feedback, it just doesn't expand anything or navigate anywhere.
//
// It does still mark the article read (silently — no expand, no navigation), same as a real
// expand or "Open article" would. Without this, an article the user has no way to interact
// with beyond this no-op tap would keep its unread dot forever, since neither of the other
// two read-marking paths (ToggleExpandCallback, "Open article") is reachable for it.
class NoOpTapFeedbackCallback : ActionCallback {
    companion object {
        val ARTICLE_ID_KEY = ActionParameters.Key<String>("articleId")
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val articleId = parameters[ARTICLE_ID_KEY] ?: return
        var wasUnread = false
        updateAppWidgetState(context, glanceId) { prefs ->
            val articles = prefs[WidgetStateKey.articles]
                ?.let { runCatching { Json.decodeFromString<List<ArticleItem>>(it) }.getOrNull() }
            if (articles != null) {
                wasUnread = articles.any { it.id == articleId && !it.isRead }
                if (wasUnread) {
                    prefs[WidgetStateKey.articles] = Json.encodeToString(
                        articles.map { if (it.id == articleId) it.copy(isRead = true) else it }
                    )
                }
            }
        }
        if (wasUnread) {
            ReadStatusStore(context).markRead(articleId)
            // Only re-render when something actually changed (the unread dot cleared) — the
            // native press ripple already fired on tap regardless, so an already-read article
            // tapped again needs no widget update at all.
            NewsFeedWidget().update(context, glanceId)
        }
    }
}
