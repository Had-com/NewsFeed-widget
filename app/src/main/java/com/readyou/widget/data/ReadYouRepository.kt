package com.readyou.widget.data

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class ReadYouRepository(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun getArticles(config: WidgetConfig): List<ArticleItem> = withContext(Dispatchers.IO) {
        val all = mutableListOf<ArticleItem>()
        for (feed in config.feeds.filter { it.enabled && it.feedUrl.isNotBlank() }) {
            try { all += fetchFeedArticles(feed) } catch (_: Exception) {}
        }
        applyFiltersAndSort(all, config)
    }

    /** Fetches just the channel/feed title — used in the config screen when the user adds a URL. */
    suspend fun fetchFeedTitle(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("User-Agent", "ReadYouWidget/1.0").build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.byteStream()?.let { stream ->
                    val parser = Xml.newPullParser()
                    parser.setInput(stream, null)
                    parseFeedTitle(parser)
                }
            }
        } catch (_: Exception) { null }
    }

    // ── private ────────────────────────────────────────────────────────────────

    private fun fetchFeedArticles(feed: FeedConfig): List<ArticleItem> {
        val req = Request.Builder().url(feed.feedUrl).header("User-Agent", "ReadYouWidget/1.0").build()
        return client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.byteStream()?.let { stream ->
                val parser = Xml.newPullParser()
                parser.setInput(stream, null)
                parseFeed(parser, feed)
            } ?: emptyList()
        }
    }

    private fun parseFeedTitle(parser: XmlPullParser): String? {
        var channelFound = false
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name.lowercase()) {
                    "channel", "feed" -> channelFound = true
                    "title" -> if (channelFound) return runCatching { parser.nextText() }.getOrNull()?.trim()
                    "item", "entry" -> return null // past articles with no title found
                }
            }
            event = try { parser.next() } catch (_: Exception) { break }
        }
        return null
    }

    private fun parseFeed(parser: XmlPullParser, feed: FeedConfig): List<ArticleItem> {
        val items = mutableListOf<ArticleItem>()
        var event = try { parser.next() } catch (_: Exception) { return emptyList() }
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG &&
                (parser.name.equals("item", true) || parser.name.equals("entry", true))) {
                parseItem(parser, feed)?.let { items += it }
                if (items.size >= 25) break
            }
            event = try { parser.next() } catch (_: Exception) { break }
        }
        return items
    }

    private fun parseItem(parser: XmlPullParser, feed: FeedConfig): ArticleItem? {
        val entryTag = parser.name
        var title = ""
        var guid = ""       // deduplication id — may be a URN
        var articleUrl = "" // the actual web URL to open
        var pubDate = 0L

        try { parser.next() } catch (_: Exception) { return null }

        while (!(parser.eventType == XmlPullParser.END_TAG &&
                parser.name.equals(entryTag, true))) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name.lowercase()) {
                    "title" -> if (title.isEmpty()) {
                        title = runCatching { parser.nextText() }.getOrDefault("").trim()
                    }
                    "guid", "id" -> if (guid.isEmpty()) {
                        guid = runCatching { parser.nextText() }.getOrDefault("").trim()
                    }
                    "link" -> {
                        val href = parser.getAttributeValue(null, "href")
                        val rel = parser.getAttributeValue(null, "rel") ?: "alternate"
                        if (href != null) {
                            // Atom self-closing: <link href="..." rel="alternate" />
                            if (rel == "alternate" && articleUrl.isEmpty()) articleUrl = href
                        } else {
                            // RSS text: <link>url</link>
                            val text = runCatching { parser.nextText() }.getOrDefault("").trim()
                            if (text.isNotBlank() && articleUrl.isEmpty()) articleUrl = text
                        }
                    }
                    "pubdate" -> if (pubDate == 0L) {
                        pubDate = parseDate(runCatching { parser.nextText() }.getOrDefault(""))
                    }
                    "published", "updated" -> if (pubDate == 0L) {
                        pubDate = parseDate(runCatching { parser.nextText() }.getOrDefault(""))
                    }
                }
            }
            if (parser.eventType == XmlPullParser.END_DOCUMENT) break
            try { parser.next() } catch (_: Exception) { break }
        }

        if (title.isBlank()) return null
        return ArticleItem(
            id = guid.ifBlank { articleUrl.ifBlank { "${feed.feedId}_${System.nanoTime()}" } },
            feedId = feed.feedId,
            feedName = feed.displayName,
            title = title,
            articleUrl = articleUrl,
            publishedAt = if (pubDate > 0) pubDate else System.currentTimeMillis(),
            isRead = false,
        )
    }

    private fun parseDate(text: String): Long {
        if (text.isBlank()) return 0L
        // RFC 2822 (RSS 2.0): "Sat, 01 Jan 2022 00:00:00 +0000"
        runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH).parse(text.trim())?.time
        }.getOrNull()?.let { return it }
        // ISO 8601 with Z (Atom): "2022-01-01T00:00:00Z"
        runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH)
                .also { it.timeZone = TimeZone.getTimeZone("UTC") }
                .parse(text.trim())?.time
        }.getOrNull()?.let { return it }
        // ISO 8601 with zone offset: "2022-01-01T00:00:00+03:00"
        runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ENGLISH).parse(text.trim())?.time
        }.getOrNull()?.let { return it }
        return 0L
    }

    private fun applyFiltersAndSort(items: List<ArticleItem>, config: WidgetConfig): List<ArticleItem> {
        val enabledFeedIds = config.feeds.filter { it.enabled }.map { it.feedId }.toSet()
        val feedPositions = config.feedOrder.withIndex().associate { (i, id) -> id to i }

        val filtered = items
            .filter { it.feedId in enabledFeedIds }
            .filter { article ->
                when (config.filter) {
                    FilterMode.UNREAD.key -> !article.isRead
                    FilterMode.READ.key -> article.isRead
                    else -> true
                }
            }

        return when (config.sortOrder) {
            SortOrder.OLDEST.key ->
                filtered.sortedWith(compareBy({ feedPositions[it.feedId] ?: Int.MAX_VALUE }, { it.publishedAt }))
            SortOrder.BY_FEED.key ->
                filtered.sortedWith(compareBy({ feedPositions[it.feedId] ?: Int.MAX_VALUE }, { -it.publishedAt }))
            SortOrder.UNREAD_FIRST.key ->
                filtered.sortedWith(compareBy({ it.isRead }, { feedPositions[it.feedId] ?: Int.MAX_VALUE }, { -it.publishedAt }))
            else -> // newest first (default)
                filtered.sortedWith(compareBy({ feedPositions[it.feedId] ?: Int.MAX_VALUE }, { -it.publishedAt }))
        }
    }
}
