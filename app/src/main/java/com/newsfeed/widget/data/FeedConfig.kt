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
    val sortOrder: String = "newest",
    val filter: String = "all",
    val feedOrder: List<String> = emptyList(),
    val feeds: List<FeedConfig> = emptyList(),
    val refreshIntervalMinutes: Int = 15,
    val fontSize: Float = 1.0f,                // 0.5 – 3.0
    val externalApp: String = "browser",       // "browser" | "share"
    val articleLength: String = "medium",      // "short" | "medium" | "full"
    val widgetTheme: String = "glamer",        // "auto" | "lavender" | "amethyst" | "glassy" | "simple" | "aerospace" | "silicon" | "glamer"
    val themeVariant: String = "light",        // "light" | "dark"
    val useThemeColors: Boolean = true,        // when true, all feeds use the theme accent instead of per-feed colors
    val backgroundAlpha: Float = 1.0f,         // 0.0 (fully transparent) – 1.0 (fully opaque)
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
)

data class FeedSearchResult(
    val feedUrl: String,
    val title: String,
    val description: String,
    val subscribers: Int,
)

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
