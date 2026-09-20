package com.newsfeed.widget.config

import com.newsfeed.widget.data.FeedConfig
import com.newsfeed.widget.data.TelegramFeedParser

data class MergeResult(val merged: List<FeedConfig>, val addedCount: Int, val skippedCount: Int)

private val TG_PREVIEW_REGEX = Regex("""^(?:https?://)?(?:t\.me|telegram\.me)/s/([A-Za-z0-9_]+)/?$""", RegexOption.IGNORE_CASE)
private val URL_PARTS_REGEX = Regex("""^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)(.*)$""")

/**
 * Canonical form used only to decide whether two feed URLs are the same feed.
 * Telegram references (@x, t.me/x, t.me/s/x, ...) collapse to one lowercase preview URL.
 * Otherwise: trim, lowercase scheme and host, strip trailing slashes from the end.
 * http and https are deliberately NOT equated (the Add Feed duplicate check doesn't either).
 */
fun normalizeFeedUrl(raw: String): String {
    val s = raw.trim()
    val tg = TelegramFeedParser.canonicalize(s) ?: TG_PREVIEW_REGEX.matchEntire(s)?.let { "https://t.me/s/${it.groupValues[1]}" }
    if (tg != null) return tg.lowercase().trimEnd('/')
    val m = URL_PARTS_REGEX.matchEntire(s) ?: return s.trimEnd('/')
    return (m.groupValues[1].lowercase() + "://" + m.groupValues[2].lowercase() + m.groupValues[3]).trimEnd('/')
}

private fun FeedConfig.identity() = normalizeFeedUrl(feedUrl.ifBlank { feedId })

/** Appends defaults missing from [existing] (in defaults order); never modifies or reorders existing feeds. */
fun mergeDefaultFeeds(existing: List<FeedConfig>, defaults: List<FeedConfig>): MergeResult {
    val seen = existing.map { it.identity() }.toMutableSet()
    val added = mutableListOf<FeedConfig>()
    var skipped = 0
    for (d in defaults) {
        if (seen.add(d.identity())) added += d else skipped++
    }
    return MergeResult(existing + added, added.size, skipped)
}
