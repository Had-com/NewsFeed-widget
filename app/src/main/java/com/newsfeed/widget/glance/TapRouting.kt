package com.newsfeed.widget.glance

import androidx.datastore.preferences.core.MutablePreferences
import com.newsfeed.widget.data.TapMode
import com.newsfeed.widget.data.WidgetStateKey

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
 * transient state so nothing stale leaks into the new mode. It deliberately does NOT mark any
 * article read: read means "the user moved on to another article", and a mode switch is not
 * that, so the pending article simply stays unread and is handled normally later. Read flags
 * live inside articles_json, which this never touches.
 */
internal fun resetTapState(prefs: MutablePreferences) {
    prefs.remove(WidgetStateKey.expandedArticleId)
    prefs.remove(WidgetStateKey.focusedArticleId)
    prefs.remove(WidgetStateKey.lastTappedArticleId)
    prefs.remove(WidgetStateKey.focusScale)
}

/**
 * Multiplier applied to a row's font sizes: only the focused row, in focus mode, is ever
 * scaled; every other row (including all rows when nothing is focused, and every row in
 * expand mode even if a stale focused id is still stored) renders at exactly 1.0.
 */
fun rowFontScale(isFocusMode: Boolean, isFocusedRow: Boolean, anyFocused: Boolean, focusScale: Float): Float =
    if (isFocusMode && anyFocused && isFocusedRow) focusScale else 1f
