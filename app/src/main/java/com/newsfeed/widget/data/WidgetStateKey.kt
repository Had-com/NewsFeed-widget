package com.newsfeed.widget.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object WidgetStateKey {
    val articles          = stringPreferencesKey("articles_json")
    val configJson        = stringPreferencesKey("config_json")
    val lastRefreshTime   = longPreferencesKey("last_refresh_time")
    val lastRefreshFailed = booleanPreferencesKey("last_refresh_failed")
    val expandedArticleId = stringPreferencesKey("expanded_article_id")
    // Expand mode only: the article the user tapped most recently, whether or not it expands.
    // Read is flagged on it when a different article is tapped next (see markPreviousTappedRead).
    val lastTappedArticleId = stringPreferencesKey("last_tapped_article_id")
    val fullArticleId     = stringPreferencesKey("full_article_id")
    val fullArticleText   = stringPreferencesKey("full_article_text")
    // How many characters of fullArticleText are currently revealed for Glamour's chunked
    // "Load more" pagination — see LoadMoreArticleCallback. Only one article's full text is
    // ever loaded at a time (same as fullArticleId/fullArticleText), so this is a single
    // value, not a per-article map.
    val fullArticleShownChars = intPreferencesKey("full_article_shown_chars")
    // How many of the accumulated (up to 300) articles are currently revealed in the list —
    // see LoadMoreArticlesCallback and NewsFeedWidget's maxRowsAllowed. Same chunked-reveal
    // pattern as fullArticleShownChars, but for the article list itself instead of one
    // article's body: the row count safe to render in one RemoteViews update depends on
    // real bitmap memory (headline/thumbnail bitmaps), not a flat guess.
    val visibleArticleCount = intPreferencesKey("visible_article_count")
    // Focus mode only (WidgetConfig.tapMode == "focus", see FeedItemRow's row scaling and
    // SetFocusArticleCallback): which article, if any, is currently shown enlarged. Every other
    // row keeps its normal size. Empty string = nothing focused. Cleared by resetTapState() when
    // a widget's tap mode is switched.
    val focusedArticleId = stringPreferencesKey("focused_article_id")
    // Focus mode (tapMode = focus) only. How large the focused row renders, as a multiple of fontSize — live,
    // on-widget adjustable via +/- buttons on the focused row itself (AdjustFocusScaleCallback),
    // deliberately NOT a Settings-screen slider: this is meant to be
    // tuned in the moment, per article, without leaving the widget. Was a hardcoded 1.25f;
    // absent (before the user has ever adjusted it, or after focus moves to a different
    // article — see AdjustFocusScaleCallback) means "use the 1.25f default".
    val focusScale = floatPreferencesKey("focus_scale")

    // A pure "something changed" touch value with no meaning of its own beyond forcing a
    // genuine Preferences mutation - Glance/Compose skips recomposing a widget when its
    // observed Preferences value is unchanged from last time, but UnreadGracePeriod's
    // delayed refresh has nothing else to write (the grace period's expiry is a pure
    // wall-clock-time condition, not a data change), so without this, that delayed update()
    // call can be silently skipped as a no-op. Confirmed on-device: without this, an expired
    // article stayed visible for 70+ seconds past its 5-second grace period, only actually
    // disappearing once some unrelated later write (e.g. a CLOCK_TICK tick) forced a real
    // recomposition.
    val graceCheckTick = longPreferencesKey("grace_check_tick")
}
