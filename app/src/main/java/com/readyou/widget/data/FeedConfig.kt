package com.readyou.widget.data

import kotlinx.serialization.Serializable

@Serializable
data class FeedConfig(
    val feedId: String,
    val displayName: String,
    val feedUrl: String = "",
    val accentColor: String = "#9B72E3",
    val fontFamily: String = "sans",         // "sans" | "serif" | "mono"
    val textStyle: Set<String> = emptySet(), // "bold", "italic", "underline"
    val layoutDirection: String = "ltr",     // "rtl" | "ltr"
    val enabled: Boolean = true,
)

@Serializable
data class WidgetConfig(
    val widgetId: Int,
    val sortOrder: String = "newest",        // "newest" | "oldest" | "by_feed" | "unread_first"
    val filter: String = "all",             // "all" | "unread" | "read"
    val feedOrder: List<String> = emptyList(),
    val feeds: List<FeedConfig> = emptyList(),
)

@Serializable
data class ArticleItem(
    val id: String,
    val feedId: String,
    val feedName: String,
    val title: String,
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
