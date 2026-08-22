package com.readyou.widget.data

import kotlinx.serialization.Serializable

@Serializable
data class FeedConfig(
    val feedId: String,
    val displayName: String,
    val feedUrl: String = "",
    val accentColor: String = "#9B72E3",
    val fontFamily: String = "sans",
    val textStyle: Set<String> = emptySet(),
    val layoutDirection: String = "ltr",
    val displayMode: String = "text",   // "text" | "image"
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
    val fontSize: Float = 1.0f,                // 0.75 – 1.5
    val externalApp: String = "browser",       // "readyou" | "browser" | "share"
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
