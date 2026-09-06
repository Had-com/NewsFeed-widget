package com.newsfeed.widget.data

/**
 * Applies the user's chosen filter (all/unread/read) and sort order (newest/oldest/by-feed/
 * unread-first) to an already-accumulated article list — the render-time counterpart to
 * NewsFeedRepository's own filter+sort logic, which only ever touches a single refresh's
 * freshly-fetched batch.
 *
 * Without this, config.sortOrder/config.filter had no effect on what the widget actually
 * displayed once even one article had been merged into the accumulated store:
 * WidgetWorker's merge step re-sorts the whole store by publishedAt unconditionally (so any
 * non-default sort order was immediately discarded on the very next refresh) and never
 * re-applies the filter to previously-stored articles (so "Unread only"/"Read only" never
 * actually hid anything already accumulated). Confirmed on-device — reported as a bug.
 *
 * Deliberately NOT the same function as NewsFeedRepository's private applyFiltersAndSort():
 * that one also does per-feed item capping, which is a fetch-volume concern specific to a
 * single refresh, not a display concern — capping the already-accumulated list here would
 * make "Load more" reveal fewer real articles than expected for no reason.
 */
fun applyFilterAndSort(articles: List<ArticleItem>, config: WidgetConfig): List<ArticleItem> {
    val filtered = articles.filter { article ->
        when (config.filter) {
            FilterMode.UNREAD.key -> !article.isRead
            FilterMode.READ.key   -> article.isRead
            else                  -> true
        }
    }
    return when (config.sortOrder) {
        SortOrder.OLDEST.key -> filtered.sortedBy { it.publishedAt }
        SortOrder.UNREAD_FIRST.key -> filtered.sortedWith(compareBy({ it.isRead }, { -it.publishedAt }))
        SortOrder.BY_FEED.key -> {
            // Same round-robin interleave as NewsFeedRepository's own BY_FEED handling —
            // one article per feed per round, in the user's configured feed order.
            val orderedIds = config.feedOrder.filter { id -> filtered.any { it.feedId == id } }
            val byFeed = orderedIds.associateWith { id ->
                filtered.filter { it.feedId == id }.sortedByDescending { it.publishedAt }
            }
            val result = mutableListOf<ArticleItem>()
            val maxSize = byFeed.values.maxOfOrNull { it.size } ?: 0
            for (i in 0 until maxSize) {
                for (id in orderedIds) { byFeed[id]?.getOrNull(i)?.let { result += it } }
            }
            result
        }
        else -> filtered.sortedByDescending { it.publishedAt }
    }
}
