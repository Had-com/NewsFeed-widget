package com.readyou.widget.data

import androidx.datastore.preferences.core.stringPreferencesKey

object WidgetStateKey {
    val articles = stringPreferencesKey("articles_json")
    val configJson = stringPreferencesKey("config_json")
}
