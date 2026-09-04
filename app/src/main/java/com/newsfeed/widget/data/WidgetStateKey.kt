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
    // Focus Mode only (BuildConfig.FOCUS_MODE build flavor — see FeedItemRow's fontSize
    // shadowing and FocusStepCallback): which article, if any, is currently shown at full
    // size while every other displayed row shrinks. Empty string = focus mode inactive, all
    // rows render at the normal configured font size, same as the standard flavor always does.
    val focusedArticleId = stringPreferencesKey("focused_article_id")
    // Focus Mode only. How large the focused row renders, as a multiple of fontSize — live,
    // on-widget adjustable via +/- buttons on the focused row itself (AdjustFocusScaleCallback),
    // deliberately NOT a Settings-screen slider like focusBackgroundScale: this is meant to be
    // tuned in the moment, per article, without leaving the widget. Was a hardcoded 1.25f;
    // absent (before the user has ever adjusted it, or after focus moves to a different
    // article — see AdjustFocusScaleCallback) means "use the 1.25f default".
    val focusScale = floatPreferencesKey("focus_scale")
}
