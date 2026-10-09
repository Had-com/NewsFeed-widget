package com.newsfeed.widget.glance

import com.newsfeed.widget.data.ArticleItem
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * One-entry memo of the decoded article list, keyed by the exact stored JSON (DEBUG_PLAN 14.aj). A tap decodes
 * the same ~120 KB articles_json in the callback and again in the render (about 40 ms on the phone), and a render
 * that follows a render sees the same string. A callback that marks articles read hands over the list it just
 * encoded ([remember]), so the render that follows does not decode at all. The lists are never mutated.
 */
internal object ArticleListCache {
    private var json: String? = null
    private var list: List<ArticleItem> = emptyList()

    /** The decoded list for [raw], or null when [raw] is not a readable article list (nothing is cached then). */
    @Synchronized
    fun decode(raw: String): List<ArticleItem>? {
        if (raw == json) return list
        val decoded = runCatching { Json.decodeFromString<List<ArticleItem>>(raw) }.getOrNull() ?: return null
        json = raw
        list = decoded
        return decoded
    }

    /** [raw] is the JSON [articles] was just encoded to. */
    @Synchronized
    fun remember(raw: String, articles: List<ArticleItem>) {
        json = raw
        list = articles
    }
}
