package com.newsfeed.widget.glance

import androidx.datastore.preferences.core.MutablePreferences
import com.newsfeed.widget.data.TapMode
import com.newsfeed.widget.data.WidgetStateKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** What a tap on an article row does; FeedItemRow maps each value to its ActionCallback. */
enum class TapAction { SET_FOCUS, TOGGLE_EXPAND, NO_OP }

/**
 * The tap-routing matrix. Focus wins regardless of description (a focused row auto-expands, so
 * a description-less article is still focusable). Expand mode falls back to a no-op ripple for
 * articles with no description (NoOpTapFeedbackCallback).
 */
fun tapActionFor(mode: TapMode, hasDescription: Boolean): TapAction = when {
    mode == TapMode.FOCUS -> TapAction.SET_FOCUS
    hasDescription        -> TapAction.TOGGLE_EXPAND
    else                  -> TapAction.NO_OP
}

/** True when saving [savedKey] over [loadedKey] actually switches the widget's tap mode. */
fun tapModeChanged(loadedKey: String, savedKey: String): Boolean =
    TapMode.fromKey(loadedKey) != TapMode.fromKey(savedKey)

/**
 * Called when a placed widget's tap mode is switched (either direction): drops the old mode's
 * transient state (expanded / focused / last-tapped article ids) so nothing stale leaks into the
 * new mode. It deliberately keeps the Focus zoom (focusScale), a persistent per-widget
 * preference rather than transient state. It also deliberately does NOT mark any
 * article read: read means "the user moved on to another article", and a mode switch is not
 * that, so the pending article simply stays unread and is handled normally later. Read flags
 * live inside articles_json, which this never touches.
 */
internal fun resetTapState(prefs: MutablePreferences) {
    prefs.remove(WidgetStateKey.expandedArticleId)
    prefs.remove(WidgetStateKey.focusedArticleId)
    prefs.remove(WidgetStateKey.lastTappedArticleId)
    // focusScale is intentionally kept: the Focus zoom is a persistent per-widget preference and
    // survives a tap-mode switch (BUG-024).
}

/**
 * The Glance-state mutation behind a Focus-mode row tap (SetFocusArticleCallback): tapping a
 * different row focuses it, re-tapping the focused row clears focus. When focus moves from one
 * article to ANOTHER, the article losing focus is flagged read (stamped [now]) inside
 * articles_json, but only if it was actually unread, so re-tapping through already-read
 * articles doesn't restart a grace period that doesn't apply to them. With story threads on, the
 * article's whole thread is flagged read with the same stamp, unless focus merely moved to another
 * member of that same thread (see idsToMarkReadOnMoveAway).
 *
 * Returns the ids that were just flagged read, the article that lost focus first (empty if none).
 */
internal fun applyFocusTapThread(prefs: MutablePreferences, articleId: String, now: Long): List<String> {
    var marked: List<String> = emptyList()
    val current = prefs[WidgetStateKey.focusedArticleId] ?: ""
    val movingToAnotherArticle = current.isNotBlank() && current != articleId
    prefs[WidgetStateKey.focusedArticleId] = if (current == articleId) "" else articleId
    // Deliberately does NOT touch WidgetStateKey.focusScale: the −/+ zoom is a persistent
    // per-widget preference, not a choice about one article (BUG-024 — it used to be wiped here
    // every time focus moved to a different article).

    if (movingToAnotherArticle) {
        val articles = decodeStoredArticles(prefs)
        if (articles != null) {
            // BUG-026: only an article that is actually on screen can be "left". A stale focused id (the
            // article is hidden in a collapsed thread, filtered out, or gone) was never read by the user,
            // so it must not be silently flagged read. When the config is unreadable we cannot tell, and
            // keep the long-standing behaviour.
            val onScreen = renderedRowIds(prefs, articles, now)
            val ids = if (onScreen == null || current in onScreen)
                idsToMarkReadOnMoveAway(prefs, articles, current, articleId) else emptyList()
            if (ids.isNotEmpty()) {
                val updated = markIdsRead(articles, ids, now)
                val updatedJson = Json.encodeToString(updated)
                ArticleListCache.remember(updatedJson, updated)
                prefs[WidgetStateKey.articles] = updatedJson
                marked = ids
            }
        }
    }
    return marked
}

/** Single-article view of [applyFocusTapThread]: the article that lost focus and was marked, or null. */
internal fun applyFocusTap(prefs: MutablePreferences, articleId: String, now: Long): String? =
    applyFocusTapThread(prefs, articleId, now).firstOrNull()

/**
 * Multiplier applied to a row's font sizes: only the focused row, in focus mode, is ever
 * scaled; every other row (including all rows when nothing is focused, and every row in
 * expand mode even if a stale focused id is still stored) renders at exactly 1.0.
 */
fun rowFontScale(isFocusMode: Boolean, isFocusedRow: Boolean, anyFocused: Boolean, focusScale: Float): Float =
    if (isFocusMode && anyFocused && isFocusedRow) focusScale else 1f
