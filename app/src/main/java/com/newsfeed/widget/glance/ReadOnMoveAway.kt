package com.newsfeed.widget.glance

import androidx.datastore.preferences.core.MutablePreferences
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.WidgetStateKey
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Expand mode (tapMode = expand): an article is marked read only when the user moves on to a different one,
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

/**
 * Expand mode keeps at most one article expanded. A tap on a description-less article never
 * expands anything (NoOpTapFeedbackCallback), so without this the previously expanded article
 * would stay open above it; it is marked read on that same tap and, under Unread only, dissolves
 * seconds later - the rows below then slide up under the user's finger. Collapsing it right away
 * makes the layout settle on the tap itself.
 *
 * Clears the expanded id only when it is non-blank and differs from [tappedId] (the toggle in
 * ToggleExpandCallback owns the same-article case). Returns true if it cleared something.
 */
internal fun collapseExpandedOnOtherTap(prefs: MutablePreferences, tappedId: String): Boolean {
    val expanded = prefs[WidgetStateKey.expandedArticleId] ?: ""
    if (expanded.isBlank() || expanded == tappedId) return false
    prefs[WidgetStateKey.expandedArticleId] = ""
    return true
}
