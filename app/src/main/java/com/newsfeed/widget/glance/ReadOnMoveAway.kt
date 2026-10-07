package com.newsfeed.widget.glance

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.WidgetStateKey
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * What "moving on" marks read. When [fromId] (the article the user is leaving) is unread:
 *  - grouping off / not in a thread: just [fromId];
 *  - in a story thread (data/StoryThreads.kt, WidgetConfig.groupStories): the WHOLE thread - [fromId]
 *    first, then the other members by id - recomputed from [articles], the same threads the render
 *    drew. Exception: if [toId] is another member of that same thread the user has not moved on
 *    from the story, so nothing is marked (returns empty).
 * An already-read or missing [fromId] returns empty.
 */
internal fun idsToMarkReadOnMoveAway(
    prefs: Preferences,
    articles: List<ArticleItem>,
    fromId: String,
    toId: String?,
): List<String> {
    val from = articles.firstOrNull { it.id == fromId } ?: return emptyList()
    if (from.isRead) return emptyList()
    val thread = storedThreads(prefs, articles)?.firstOrNull { t -> t.members.any { it.id == fromId } }
    if (thread == null || thread.others.isEmpty()) return listOf(fromId)
    if (toId != null && thread.members.any { it.id == toId }) return emptyList()
    val rest = thread.members.filter { !it.isRead && it.id != fromId }.map { it.id }.sorted()
    return listOf(fromId) + rest
}

/** Stamps every article whose id is in [ids] read at [now]; everything else is returned unchanged. */
internal fun markIdsRead(articles: List<ArticleItem>, ids: Collection<String>, now: Long): List<ArticleItem> {
    val set = ids.toSet()
    return articles.map { if (it.id in set) it.copy(isRead = true, readAt = now) else it }
}

/**
 * Expand mode (tapMode = expand): an article is marked read only when the user moves on to a different one,
 * not when pressed. Records [tappedId] as the last-tapped article and, if a different, still
 * unread article was tapped before it, flags it read (isRead + readAt = [now]) in [prefs] - together
 * with the rest of its story thread when grouping is on (see [idsToMarkReadOnMoveAway]; every
 * member gets the SAME readAt, so the thread dissolves and leaves Unread only as one). Returns the
 * ids newly marked read, previous article first (empty if nothing changed: first tap, re-tap of the
 * same article, tap within the same thread, previous already read/missing, or unreadable article
 * JSON). Never re-stamps readAt on an already-read article.
 */
internal fun markPreviousTappedReadThread(
    prefs: MutablePreferences,
    tappedId: String,
    now: Long = System.currentTimeMillis(),
): List<String> {
    val prev = prefs[WidgetStateKey.lastTappedArticleId] ?: ""
    prefs[WidgetStateKey.lastTappedArticleId] = tappedId
    if (prev.isBlank() || prev == tappedId) return emptyList()

    val articles = decodeStoredArticles(prefs) ?: return emptyList()
    val ids = idsToMarkReadOnMoveAway(prefs, articles, prev, tappedId)
    if (ids.isEmpty()) return emptyList()

    prefs[WidgetStateKey.articles] = Json.encodeToString(markIdsRead(articles, ids, now))
    return ids
}

/** Single-article view of [markPreviousTappedReadThread]: the previous article's id, or null. */
internal fun markPreviousTappedRead(
    prefs: MutablePreferences,
    tappedId: String,
    now: Long = System.currentTimeMillis(),
): String? = markPreviousTappedReadThread(prefs, tappedId, now).firstOrNull()

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
