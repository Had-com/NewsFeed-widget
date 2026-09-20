package com.newsfeed.widget.glance

import com.newsfeed.widget.data.TelegramFeedParser

/** Prefix FetchFullArticleCallback.fetchContent puts on every failure string. */
internal const val FULL_ARTICLE_ERROR_PREFIX = "Could not load article"

/**
 * Decides what the expanded row should show after a "Load full article" fetch. Never
 * replaces the text already on screen with something blank, an error string, a Telegram
 * page (a JS shell whose scrape is only page chrome), or a result under half the length of
 * an already-substantial current text (a bare consent/paywall stub).
 */
fun chooseFullArticleText(current: String, fetched: String?, url: String): String {
    if (fetched == null || fetched.isBlank()) return current
    if (fetched.startsWith(FULL_ARTICLE_ERROR_PREFIX)) return current
    if (TelegramFeedParser.isTelegramUrl(url)) return current
    if (current.length > 200 && fetched.length < current.length / 2) return current
    return fetched
}

/** A Telegram post's full text is already its description; there is nothing more to load. */
fun canLoadFullArticle(articleUrl: String): Boolean =
    articleUrl.isNotBlank() && !TelegramFeedParser.isTelegramUrl(articleUrl)
