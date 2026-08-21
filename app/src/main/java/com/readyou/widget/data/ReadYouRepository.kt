package com.readyou.widget.data

import android.content.Context

/**
 * Bridges the widget to Read You's data.
 *
 * OPTION A (recommended): replace the bodies below with direct Room DAO calls
 * when this widget lives inside the Read You app module.
 *
 * OPTION B: replace with ContentProvider queries if shipping as a separate APK.
 */
class ReadYouRepository(private val context: Context) {

    fun getFeeds(): List<FeedConfig> {
        // TODO (Option A): inject and call ReadYou's FeedDao.getAll()
        // TODO (Option B): context.contentResolver.query(READ_YOU_FEEDS_URI, ...)
        return emptyList()
    }

    fun getArticles(config: WidgetConfig): List<ArticleItem> {
        // TODO (Option A): call ArticleDao with sort/filter derived from config
        // TODO (Option B): ContentProvider query with selection args
        val raw = fetchRaw()
        return applyFiltersAndSort(raw, config)
    }

    private fun fetchRaw(): List<ArticleItem> = emptyList()

    private fun applyFiltersAndSort(
        items: List<ArticleItem>,
        config: WidgetConfig,
    ): List<ArticleItem> {
        val enabledFeedIds = config.feeds.filter { it.enabled }.map { it.feedId }.toSet()

        val filtered = items
            .filter { it.feedId in enabledFeedIds }
            .filter { article ->
                when (config.filter) {
                    FilterMode.UNREAD.key -> !article.isRead
                    FilterMode.READ.key -> article.isRead
                    else -> true
                }
            }

        // enforce the user-defined feed order, then sort within each group
        val feedPositions = config.feedOrder.withIndex().associate { (i, id) -> id to i }

        return when (config.sortOrder) {
            SortOrder.OLDEST.key ->
                filtered.sortedWith(compareBy({ feedPositions[it.feedId] ?: Int.MAX_VALUE }, { it.publishedAt }))
            SortOrder.BY_FEED.key ->
                filtered.sortedWith(compareBy({ feedPositions[it.feedId] ?: Int.MAX_VALUE }, { -it.publishedAt }))
            SortOrder.UNREAD_FIRST.key ->
                filtered.sortedWith(compareBy({ it.isRead }, { feedPositions[it.feedId] ?: Int.MAX_VALUE }, { -it.publishedAt }))
            else -> // newest first
                filtered.sortedWith(compareBy({ feedPositions[it.feedId] ?: Int.MAX_VALUE }, { -it.publishedAt }))
        }
    }
}
