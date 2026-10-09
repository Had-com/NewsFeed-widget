package com.newsfeed.widget.glance

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.StoryThread
import com.newsfeed.widget.data.dedupeById
import com.newsfeed.widget.data.applyRetention
import com.newsfeed.widget.data.displayThreadsFor
import com.newsfeed.widget.data.flattenThreads
import com.newsfeed.widget.data.WidgetConfig
import com.newsfeed.widget.data.WidgetStateKey
import com.newsfeed.widget.data.groupStories
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

// Persisted state and read helpers for story threads (WidgetConfig.groupStories, data/StoryThreads.kt).
// Everything here works on the widget's Glance Preferences so the tap callbacks (which cannot see
// the render's local variables) recompute exactly the threads the render drew.

/** Most expanded threads remembered per widget; the oldest-expanded ids drop first. */
internal const val EXPANDED_THREADS_MAX = 30
private const val SEP = '\n'

/** The expanded lead ids stored in [WidgetStateKey.expandedThreadIds] (insertion order kept). */
fun parseExpandedThreadIds(raw: String?): Set<String> =
    raw.orEmpty().split(SEP).filter { it.isNotEmpty() }.toCollection(LinkedHashSet())

/** Inverse of [parseExpandedThreadIds]; ids containing the separator cannot be stored and are skipped. */
fun encodeExpandedThreadIds(ids: Collection<String>): String =
    ids.filter { it.isNotEmpty() && SEP !in it }.joinToString(SEP.toString())

/**
 * The threads of everything this widget has stored, or null when grouping does not apply (no
 * readable config, `groupStories` off, or no readable article list). Input = ALL stored articles of
 * the feeds still in the config - deliberately before the All / Unread only / Read only filter:
 * threads never mix read classes (see StoryThreads.kt), so a filtered render and this unfiltered
 * recomputation produce the same threads for every article the render can show.
 */
internal fun storedThreads(
    prefs: Preferences,
    articles: List<ArticleItem>,
    now: Long = System.currentTimeMillis(),
): List<StoryThread>? {
    val config = prefs[WidgetStateKey.configJson]
        ?.let { runCatching { Json.decodeFromString<WidgetConfig>(it) }.getOrNull() }
        ?: return null
    if (!config.groupStories) return null
    val feedIds = config.feeds.map { it.feedId }.toSet()
    // dedupeById: the render de-duplicates same-id articles (same article served by two feeds) before
    // grouping, so the callbacks must too, or a "+1" thread of one article and its own copy would exist here.
    // applyRetention: the render drops articles past the "Keep articles for" window (displayThreadsFor), so
    // the callbacks must too or an expired article would still be counted in a thread (and in its statistics).
    return groupStories(dedupeById(applyRetention(articles.filter { it.feedId in feedIds }, config, now)))
}

/**
 * Ids of the article rows the widget currently renders (the post-filter, de-duplicated, grouped and
 * flattened list, i.e. the rows a user can see or scroll to - a Focus row beyond the row cap is pinned
 * into the slice, see layoutRows), or null when that cannot be told (unreadable config). A member of
 * a collapsed thread, a filtered-out or a removed article is NOT in the set.
 */
internal fun renderedRowIds(prefs: Preferences, articles: List<ArticleItem>, now: Long): Set<String>? {
    val config = prefs[WidgetStateKey.configJson]
        ?.let { runCatching { Json.decodeFromString<WidgetConfig>(it) }.getOrNull() }
        ?: return null
    val expanded = parseExpandedThreadIds(prefs[WidgetStateKey.expandedThreadIds])
    return flattenThreads(displayThreadsFor(articles, config, now), expanded).mapTo(HashSet()) { it.article.id }
}

internal fun decodeStoredArticles(prefs: Preferences): List<ArticleItem>? =
    prefs[WidgetStateKey.articles]?.let { ArticleListCache.decode(it) }

/**
 * Expands / collapses the thread led by [leadId] (the "+N" badge tap, ToggleThreadCallback). Also:
 *  - prunes remembered ids that no longer lead a thread of 2+ articles (a thread that dissolved
 *    away, was read and left, or whose lead changed because a newer article joined it);
 *  - when COLLAPSING, clears the expanded / focused article if it is one of the thread's hidden
 *    members, so no state points at a row that is no longer on screen;
 *  - caps the remembered ids at [EXPANDED_THREADS_MAX].
 * Returns true when the thread is expanded afterwards.
 */
internal fun toggleThreadExpanded(prefs: MutablePreferences, leadId: String): Boolean {
    val ids = parseExpandedThreadIds(prefs[WidgetStateKey.expandedThreadIds]).toMutableSet()
    val expandNow = if (leadId in ids) { ids.remove(leadId); false } else { ids.add(leadId); true }

    val articles = decodeStoredArticles(prefs)
    val threads = articles?.let { storedThreads(prefs, it) }
    if (threads != null) {
        val multiLeads = threads.filter { it.others.isNotEmpty() }.map { it.lead.id }.toSet()
        ids.retainAll(multiLeads)
        if (!expandNow) {
            val hiddenMembers = threads.firstOrNull { it.lead.id == leadId }?.others?.map { it.id }?.toSet().orEmpty()
            if (prefs[WidgetStateKey.expandedArticleId] in hiddenMembers) prefs.remove(WidgetStateKey.expandedArticleId)
            if (prefs[WidgetStateKey.focusedArticleId] in hiddenMembers) prefs.remove(WidgetStateKey.focusedArticleId)
        }
    }
    val kept = ids.toList().takeLast(EXPANDED_THREADS_MAX)
    if (kept.isEmpty()) prefs.remove(WidgetStateKey.expandedThreadIds)
    else prefs[WidgetStateKey.expandedThreadIds] = encodeExpandedThreadIds(kept)
    return expandNow && leadId in kept
}
