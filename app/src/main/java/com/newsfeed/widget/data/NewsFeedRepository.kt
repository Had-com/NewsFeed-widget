package com.newsfeed.widget.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.Html
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class NewsFeedRepository(private val context: Context) {

    companion object {
        // Normal per-refresh cap — a feed that's already accumulated articles only needs its
        // newest handful each poll, not the whole feed body.
        private const val MAX_ITEMS_PER_FETCH = 50
        // First time a feed is fetched (no accumulated articles yet), pull as much of its
        // available backlog as the feed provides, capped only by the same 300-article ceiling
        // WidgetWorker applies to the overall accumulated store — most RSS feeds only carry a
        // few dozen to ~100 items anyway, so this is rarely the binding constraint in practice.
        private const val FIRST_LOAD_MAX_ITEMS = 300
    }

    val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Browser-like headers improve compatibility with news sites that block bot UAs
    private fun browserHeaders(url: String) = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.8, */*;q=0.7",
        "Accept-Language" to "he-IL,he;q=0.9,en-US;q=0.8,en;q=0.7",
    )

    // knownFeedIds: feeds that already have at least one accumulated article. A feed not in
    // this set is being fetched for the very first time, so it gets its full available
    // backlog (FIRST_LOAD_MAX_ITEMS) instead of the normal per-refresh cap (MAX_ITEMS_PER_FETCH)
    // — otherwise a feed's history would always start artificially truncated to whatever was
    // in its 50 most recent items at the moment it was first added.
    suspend fun getArticles(config: WidgetConfig, knownFeedIds: Set<String> = emptySet()): ArticleFetchResult = withContext(Dispatchers.IO) {
        val enabledFeeds = config.feeds.filter { it.enabled && it.feedUrl.isNotBlank() }
        val results = enabledFeeds
            .map { feed ->
                val maxItems = if (feed.feedId in knownFeedIds) MAX_ITEMS_PER_FETCH else FIRST_LOAD_MAX_ITEMS
                async { runCatching { fetchFeedArticles(feed, maxItems) } }
            }
            .awaitAll()
        val all = results.flatMap { it.getOrDefault(emptyList()) }
        // "Failed" means every enabled feed's fetch threw — a handful of unreachable
        // feeds among many working ones is normal and shouldn't be flagged as an error.
        val allFailed = enabledFeeds.isNotEmpty() && results.all { it.isFailure }
        ArticleFetchResult(applyFiltersAndSort(all, config, knownFeedIds), allFailed)
    }

    suspend fun fetchFeedTitle(url: String): String? = withContext(Dispatchers.IO) {
        try {
            var req = Request.Builder().url(url)
            browserHeaders(url).forEach { (k, v) -> req = req.header(k, v) }
            client.newCall(req.build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                // Use string() so OkHttp applies the correct charset from Content-Type
                val text = response.body?.string() ?: return@withContext null
                val parser = Xml.newPullParser()
                parser.setInput(text.reader())
                parseFeedTitle(parser)
            }
        } catch (_: Exception) { null }
    }

    suspend fun searchFeeds(query: String): List<FeedSearchResult> = withContext(Dispatchers.IO) {
        try {
            val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val req = Request.Builder()
                .url("https://cloud.feedly.com/v3/search/feeds?query=$encoded&count=15")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string() ?: return@withContext emptyList()
                val json = org.json.JSONObject(body)
                val arr  = json.optJSONArray("results") ?: return@withContext emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val obj    = arr.getJSONObject(i)
                    val feedId = obj.optString("feedId", "")
                    val url    = if (feedId.startsWith("feed/")) feedId.drop(5) else feedId
                    if (url.isBlank()) null
                    else FeedSearchResult(
                        feedUrl     = url,
                        title       = obj.optString("title", url),
                        description = obj.optString("description", ""),
                        subscribers = obj.optInt("subscribers", 0),
                    )
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    suspend fun downloadFavicons(feeds: List<FeedConfig>) = withContext(Dispatchers.IO) {
        for (feed in feeds) {
            if (feed.feedUrl.isBlank()) continue
            val file = FaviconHelper.file(context, feed.feedId)
            // Re-fetch at most once per week
            if (file.exists() && System.currentTimeMillis() - file.lastModified() < 7 * 24 * 3600_000L) continue
            val host = runCatching { java.net.URL(feed.feedUrl).host }.getOrNull() ?: continue
            val faviconUrl = "https://www.google.com/s2/favicons?domain=$host&sz=64"
            runCatching {
                val req = Request.Builder().url(faviconUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching
                    val bytes = resp.body?.bytes() ?: return@runCatching
                    if (bytes.size < 64) return@runCatching // skip empty/error responses
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
                }
            }
        }
    }

    suspend fun downloadThumbnails(articles: List<ArticleItem>, feeds: List<FeedConfig>) =
        withContext(Dispatchers.IO) {
            val imageFeedIds = feeds.filter { it.displayMode == "image" }.map { it.feedId }.toSet()
            for (article in articles) {
                if (article.feedId !in imageFeedIds) continue
                if (article.imageUrl.isBlank()) continue
                val file = ThumbnailHelper.file(context, article.id)
                // A 100px JPEG at quality 90 is a few KB; anything bigger is almost certainly a
                // stale file from before the 300px->100px fix and needs replacing immediately
                // rather than waiting out the normal 24h cache window.
                if (file.exists() && file.length() < 15_000 &&
                    System.currentTimeMillis() - file.lastModified() < 24 * 3600_000L) continue
                runCatching {
                    val req = Request.Builder().url(article.imageUrl)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@runCatching
                        val bmp = resp.body?.byteStream()?.let { BitmapFactory.decodeStream(it) }
                            ?: return@runCatching
                        // 100px is ~2x the widget's actual on-screen thumbnail size at typical
                        // density — plenty sharp. A prior change bumped this to 300px for quality
                        // and unknowingly blew RemoteViews' ~1.2MB per-update bitmap budget: a
                        // 300px ARGB_8888 bitmap alone is 360KB, so as few as 4 thumbnails in one
                        // update crashes the widget to a permanent "Can't show content" state.
                        // At 100px each bitmap is ~40KB, keeping even a worst-case update (every
                        // rendered row has a thumbnail) safely under budget.
                        val scaled = scaleBitmap(bmp, 100)
                        file.parentFile?.mkdirs()
                        file.outputStream().use { out ->
                            scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }
                    }
                }
            }
        }

    // ── private ───────────────────────────────────────────────────────────────

    private fun fetchFeedArticles(feed: FeedConfig, maxItems: Int = MAX_ITEMS_PER_FETCH): List<ArticleItem> {
        var req = Request.Builder().url(feed.feedUrl)
        browserHeaders(feed.feedUrl).forEach { (k, v) -> req = req.header(k, v) }
        return client.newCall(req.build()).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            // Use string() so OkHttp honours the charset in Content-Type (fixes Windows-1255 Hebrew feeds)
            val text = response.body?.string() ?: return emptyList()
            val parser = Xml.newPullParser()
            parser.setInput(text.reader())
            parseFeed(parser, feed, maxItems)
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
                    "item", "entry" -> return null
                }
            }
            event = try { parser.next() } catch (_: Exception) { break }
        }
        return null
    }

    private fun parseFeed(parser: XmlPullParser, feed: FeedConfig, maxItems: Int): List<ArticleItem> {
        val items = mutableListOf<ArticleItem>()
        var event = try { parser.next() } catch (_: Exception) { return emptyList() }
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG &&
                (parser.name.equals("item", true) || parser.name.equals("entry", true))) {
                parseItem(parser, feed)?.let { items += it }
                if (items.size >= maxItems) break
            }
            event = try { parser.next() } catch (_: Exception) { break }
        }
        return items
    }

    private fun parseItem(parser: XmlPullParser, feed: FeedConfig): ArticleItem? {
        val entryTag = parser.name
        var title = ""
        var guid = ""
        var articleUrl = ""
        var rawDescription = ""
        var imageUrl = ""
        var pubDate = 0L

        try { parser.next() } catch (_: Exception) { return null }

        while (!(parser.eventType == XmlPullParser.END_TAG &&
                parser.name.equals(entryTag, true))) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.lowercase()
                when {
                    tag == "title" && title.isEmpty() ->
                        title = runCatching { parser.nextText() }.getOrDefault("").trim()

                    tag == "guid" || tag == "id" -> if (guid.isEmpty())
                        guid = runCatching { parser.nextText() }.getOrDefault("").trim()

                    tag == "link" -> {
                        val href = parser.getAttributeValue(null, "href")
                        val rel  = parser.getAttributeValue(null, "rel") ?: "alternate"
                        if (href != null) {
                            if (rel == "alternate" && articleUrl.isEmpty()) articleUrl = href
                        } else {
                            val text = runCatching { parser.nextText() }.getOrDefault("").trim()
                            if (text.isNotBlank() && articleUrl.isEmpty()) articleUrl = text
                        }
                    }

                    // Atom <content> and <summary> (in addition to RSS content:encoded / description)
                    tag == "content:encoded" || (tag == "content" && rawDescription.isEmpty()) -> {
                        val text = runCatching { parser.nextText() }.getOrDefault("")
                        if (text.isNotBlank()) rawDescription = text
                    }
                    (tag == "description" || tag == "summary") && rawDescription.isEmpty() ->
                        rawDescription = runCatching { parser.nextText() }.getOrDefault("")

                    // Image: media:thumbnail (highest priority)
                    tag == "media:thumbnail" && imageUrl.isEmpty() ->
                        imageUrl = parser.getAttributeValue(null, "url") ?: ""

                    // Image: media:content with image medium
                    tag == "media:content" && imageUrl.isEmpty() -> {
                        val medium = parser.getAttributeValue(null, "medium") ?: ""
                        val type   = parser.getAttributeValue(null, "type") ?: ""
                        if (medium == "image" || type.startsWith("image/"))
                            imageUrl = parser.getAttributeValue(null, "url") ?: ""
                    }

                    // Image: enclosure with image type
                    tag == "enclosure" && imageUrl.isEmpty() -> {
                        val type = parser.getAttributeValue(null, "type") ?: ""
                        if (type.startsWith("image/"))
                            imageUrl = parser.getAttributeValue(null, "url") ?: ""
                    }

                    tag == "pubdate" && pubDate == 0L ->
                        pubDate = parseDate(runCatching { parser.nextText() }.getOrDefault(""))

                    (tag == "published" || tag == "updated") && pubDate == 0L ->
                        pubDate = parseDate(runCatching { parser.nextText() }.getOrDefault(""))
                }
            }
            if (parser.eventType == XmlPullParser.END_DOCUMENT) break
            try { parser.next() } catch (_: Exception) { break }
        }

        if (title.isBlank()) return null

        // If no image found in feed metadata, extract the first <img src> from description HTML.
        // Many Hebrew news sites embed images inside <description> rather than using media tags.
        if (imageUrl.isEmpty() && rawDescription.contains("<img", ignoreCase = true)) {
            imageUrl = Regex("""<img[^>]+src=["']([^"'>]+)["']""", RegexOption.IGNORE_CASE)
                .find(rawDescription)?.groupValues?.getOrNull(1) ?: ""
        }

        @Suppress("DEPRECATION")
        val description = if (rawDescription.isNotBlank()) {
            Html.fromHtml(rawDescription, Html.FROM_HTML_MODE_COMPACT)
                .toString().trim().take(2000)
        } else ""

        return ArticleItem(
            id          = guid.ifBlank { articleUrl.ifBlank { "${feed.feedId}_${System.nanoTime()}" } },
            feedId      = feed.feedId,
            feedName    = feed.displayName,
            title       = title,
            articleUrl  = articleUrl,
            description = description,
            imageUrl    = imageUrl,
            publishedAt = if (pubDate > 0) pubDate else System.currentTimeMillis(),
            isRead      = false,
        )
    }

    private fun parseDate(text: String): Long {
        if (text.isBlank()) return 0L
        runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH).parse(text.trim())?.time
        }.getOrNull()?.let { return it }
        runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH)
                .also { it.timeZone = TimeZone.getTimeZone("UTC") }
                .parse(text.trim())?.time
        }.getOrNull()?.let { return it }
        runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ENGLISH).parse(text.trim())?.time
        }.getOrNull()?.let { return it }
        return 0L
    }

    private fun applyFiltersAndSort(items: List<ArticleItem>, config: WidgetConfig, knownFeedIds: Set<String> = emptySet()): List<ArticleItem> {
        val enabledFeedIds = config.feeds.filter { it.enabled }.map { it.feedId }.toSet()
        val filtered = items
            .filter { it.feedId in enabledFeedIds }
            .filter { article ->
                when (config.filter) {
                    FilterMode.UNREAD.key -> !article.isRead
                    FilterMode.READ.key   -> article.isRead
                    else                  -> true
                }
            }
        return when (config.sortOrder) {
            SortOrder.BY_FEED.key -> {
                // Round-robin interleave: one article per feed per round so every feed
                // appears in the visible slice regardless of posting frequency.
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
            else -> {
                // Cap each feed at its 10 most-relevant articles before the global sort so a
                // single high-frequency feed can't fill all visible widget slots — except a
                // feed being fetched for the very first time (not yet in knownFeedIds), which
                // instead gets the same FIRST_LOAD_MAX_ITEMS ceiling its fetch was already
                // capped at, so newly-added feeds seed their whole available backlog into the
                // accumulated store instead of only their 10 newest.
                val perFeed = filtered
                    .groupBy { it.feedId }
                    .values
                    .flatMap { group ->
                        val cap = if (group.first().feedId in knownFeedIds) 10 else FIRST_LOAD_MAX_ITEMS
                        when (config.sortOrder) {
                            SortOrder.OLDEST.key       -> group.sortedBy { it.publishedAt }
                            SortOrder.UNREAD_FIRST.key -> group.sortedWith(compareBy({ it.isRead }, { -it.publishedAt }))
                            else                       -> group.sortedByDescending { it.publishedAt }
                        }.take(cap)
                    }
                when (config.sortOrder) {
                    SortOrder.OLDEST.key       -> perFeed.sortedBy { it.publishedAt }
                    SortOrder.UNREAD_FIRST.key -> perFeed.sortedWith(compareBy({ it.isRead }, { -it.publishedAt }))
                    else                       -> perFeed.sortedByDescending { it.publishedAt }
                }
            }
        }
    }

    private fun scaleBitmap(bitmap: Bitmap, maxPx: Int): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        if (w <= maxPx && h <= maxPx) return bitmap
        val scale = maxPx.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
    }
}
