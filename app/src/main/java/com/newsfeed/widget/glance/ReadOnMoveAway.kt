package com.newsfeed.widget.glance

import androidx.datastore.preferences.core.MutablePreferences
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.WidgetStateKey
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Standard widget: an article is marked read only when the user moves on to a different one,
 * not when pressed. Records [tappedId] as the last-tapped article and, if a different, still
 * unread article was tapped before it, flags that one read (isRead + readAt = [now]) in
 * [prefs]. Returns the id that was newly marked read, or null if nothing changed (first tap,
 * re-tap of the same article, previous already read/missing, or unreadable article JSON).
 * Never re-stamps readAt on an already-read article.
 */
internal fun markPreviousTappedRead(
    prefs: MutablePreferences,
    tappedId: String,
    now: Long = System.currentTimeMillis(),
): String? {
    val prev = prefs[WidgetStateKey.lastTappedArticleId] ?: ""
    prefs[WidgetStateKey.lastTappedArticleId] = tappedId
    if (prev.isBlank() || prev == tappedId) return null

    val articles = prefs[WidgetStateKey.articles]
        ?.let { runCatching { Json.decodeFromString<List<ArticleItem>>(it) }.getOrNull() }
        ?: return null
    val target = articles.firstOrNull { it.id == prev } ?: return null
    if (target.isRead) return null

    prefs[WidgetStateKey.articles] = Json.encodeToString(
        articles.map { if (it.id == prev) it.copy(isRead = true, readAt = now) else it }
    )
    return prev
}
