package com.newsfeed.widget.data

/**
 * Merges a freshly-fetched batch of articles with the previously-accumulated store, keeping
 * every freshly-fetched article's own fields EXCEPT [ArticleItem.readAt], which is carried
 * over from the existing stored article with the same id when one exists.
 *
 * Without this, WidgetWorker's refresh cycle silently wiped readAt on every article that
 * survived into the next fetch (still present in its live RSS feed) - a brand new ArticleItem
 * parsed fresh from the feed always starts with readAt = null, and only isRead was being
 * carried over from ReadStatusStore's read-id set, not readAt from the previously-stored
 * article. Confirmed on-device: marking an article read, then triggering any refresh shortly
 * after (e.g. WidgetConfigActivity's Save button, which calls WidgetWorker.refreshNow()
 * immediately), reset that article's readAt to null before its 5-second grace period
 * (ArticleSorting.UNREAD_GRACE_PERIOD_MS) had elapsed - making it vanish from "Unread only"
 * within roughly a couple of seconds instead of the intended 5, with no dimmed state ever
 * shown first.
 *
 * Articles present only in [existing] (not re-fetched this cycle - e.g. an older item that
 * fell off the feed's current page) pass through unchanged, same as before this fix.
 */
fun mergeFreshArticles(fresh: List<ArticleItem>, existing: List<ArticleItem>): List<ArticleItem> {
    val existingReadAt = existing.associate { it.id to it.readAt }
    val freshWithReadAt = fresh.map { article ->
        val priorReadAt = existingReadAt[article.id]
        if (priorReadAt != null) article.copy(readAt = priorReadAt) else article
    }
    val freshIds = freshWithReadAt.map { it.id }.toSet()
    return freshWithReadAt + existing.filter { it.id !in freshIds }
}
