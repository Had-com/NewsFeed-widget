package com.readyou.widget.data

import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object WidgetStateKey {
    val articles          = stringPreferencesKey("articles_json")
    val configJson        = stringPreferencesKey("config_json")
    val lastRefreshTime   = longPreferencesKey("last_refresh_time")
    val expandedArticleId = stringPreferencesKey("expanded_article_id")
}
