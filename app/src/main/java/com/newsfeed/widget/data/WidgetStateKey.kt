package com.newsfeed.widget.data

import androidx.datastore.preferences.core.booleanPreferencesKey
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
}
