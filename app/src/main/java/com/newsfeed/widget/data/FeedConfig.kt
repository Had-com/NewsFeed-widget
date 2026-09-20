package com.newsfeed.widget.data

import kotlinx.serialization.Serializable

@Serializable
data class FeedConfig(
    val feedId: String,
    val displayName: String,
    val feedUrl: String = "",
    val accentColor: String = "#9B72E3",
    val fontFamily: String = "sans",
    val textStyle: Set<String> = emptySet(),
    val layoutDirection: String = "rtl",
    val displayMode: String = "image",  // "text" | "image"
    val enabled: Boolean = true,
)

@Serializable
data class WidgetConfig(
    val widgetId: Int,
    // by_feed round-robins one article per feed per round, so a low-frequency feed's
    // articles always surface within the widget's own render-row ceiling (maxRowsAllowed in
    // NewsFeedWidget.kt) — under "newest", a handful of high-frequency feeds can otherwise
    // fill every visible slot and make a quieter feed's articles unreachable even though
    // they're safely retained in the accumulated store. Confirmed on-device.
    val sortOrder: String = "by_feed",
    val filter: String = "all",
    val feedOrder: List<String> = emptyList(),
    val feeds: List<FeedConfig> = emptyList(),
    val refreshIntervalMinutes: Int = 15,
    val fontSize: Float = 1.0f,                // 0.5 – 3.0
    val articleFontSize: Float = 1.0f,         // 0.5 – 3.0 — independent of fontSize, expanded-article body text only
    val externalApp: String = "browser",       // "browser" | "share"
    val articleLength: String = "medium",      // "short" | "medium" | "full"
    val widgetTheme: String = "glamer",        // "auto" | "lavender" | "amethyst" | "glassy" | "simple" | "aerospace" | "silicon" | "glamer" | "blackwhite" | "custom"
    val customFontColor: String = "#1B1F27",       // used only when widgetTheme == "custom"
    val customBackgroundColor: String = "#FFFFFF", // dark-on-light default so it looks
                                                    // reasonable before the user changes it
    val themeVariant: String = "light",        // "light" | "dark"
    val useThemeColors: Boolean = true,        // when true, all feeds use the theme accent instead of per-feed colors
    val backgroundAlpha: Float = 1.0f,         // 0.0 (fully transparent) – 1.0 (fully opaque)
    val retentionDays: Int = 0,                // how long an accumulated article stays in the list; 0 = unlimited (only the 300-article cap applies)
    // Unused since 2026-09-20 (Focus no longer shrinks the other rows). Kept ONLY so saved config
    // JSON that still carries this key keeps decoding: WidgetContent decodes configJson with the
    // default Json (no ignoreUnknownKeys), so deleting the property would make that decode throw
    // and the widget would render as an empty default config. New code must never read or write it.
    @Deprecated("Unused since 2026-09-20; kept so old saved JSON still decodes.")
    val focusBackgroundScale: Float = 0.5f,
    // What a tap on an article row does: TapMode.EXPAND.key ("expand", the default, and what every
    // config saved before this field existed reads as) or TapMode.FOCUS.key ("focus"). Always read
    // it through TapMode.fromKey() so an unknown value degrades to EXPAND.
    val tapMode: String = TapMode.EXPAND.key,
)

@Serializable
data class ArticleItem(
    val id: String,
    val feedId: String,
    val feedName: String,
    val title: String,
    val articleUrl: String = "",
    val description: String = "",              // plain text, max 400 chars
    val imageUrl: String = "",                 // first image from RSS enclosure/media tags
    val publishedAt: Long,
    val isRead: Boolean,
    val readAt: Long? = null,                  // set when isRead transitions to true; null
                                                // otherwise, including for articles that were
                                                // already read before this field existed
)

data class FeedSearchResult(
    val feedUrl: String,
    val title: String,
    val description: String,
    val subscribers: Int,
)

data class ArticleFetchResult(
    val articles: List<ArticleItem>,
    val allFailed: Boolean,   // true only when every enabled feed's fetch threw
)

enum class TapMode(val key: String, val label: String) {
    EXPAND("expand", "Expand in place"),
    FOCUS("focus", "Focus (enlarge)");

    companion object {
        fun fromKey(key: String?): TapMode = entries.firstOrNull { it.key == key } ?: EXPAND
    }
}

enum class SortOrder(val key: String, val labelRes: String) {
    NEWEST("newest", "Newest first"),
    OLDEST("oldest", "Oldest first"),
    BY_FEED("by_feed", "By feed"),
    UNREAD_FIRST("unread_first", "Unread first"),
}

enum class FilterMode(val key: String, val labelRes: String) {
    ALL("all", "All"),
    UNREAD("unread", "Unread only"),
    READ("read", "Read only"),
}
