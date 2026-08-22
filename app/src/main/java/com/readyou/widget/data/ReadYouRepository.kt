package com.readyou.widget.data

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

class ReadYouRepository(private val context: Context) {

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

    suspend fun getArticles(config: WidgetConfig): List<ArticleItem> = withContext(Dispatchers.IO) {
        val all = config.feeds
            .filter { it.enabled && it.feedUrl.isNotBlank() }
            .map { feed -> async { runCatching { fetchFeedArticles(feed) }.getOrDefault(emptyList()) } }
            .awaitAll()
            .flatten()
        applyFiltersAndSort(all, config)
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

    suspend fun downloadThumbnails(articles: List<ArticleItem>, feeds: List<FeedConfig>) =
        withContext(Dispatchers.IO) {
            val imageFeedIds = feeds.filter { it.displayMode == "image" }.map { it.feedId }.toSet()
            for (article in articles) {
                if (article.feedId !in imageFeedIds) continue
                if (article.imageUrl.isBlank()) continue
                val file = ThumbnailHelper.file(context, article.id)
                if (file.exists() && System.currentTimeMillis() - file.lastModified() < 24 * 3600_000L) continue
                runCatching {
                    val req = Request.Builder().url(article.imageUrl)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@runCatching
                        val bmp = resp.body?.byteStream()?.let { BitmapFactory.decodeStream(it) }
                            ?: return@runCatching
                        val scaled = scaleBitmap(bmp, 56)
                        file.parentFile?.mkdirs()
                        file.outputStream().use { out ->
                            scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
                        }
                    }
                }
            }
        }

    // ── private ───────────────────────────────────────────────────────────────

    private fun fetchFeedArticles(feed: FeedConfig): List<ArticleItem> {
        var req = Request.Builder().url(feed.feedUrl)
        browserHeaders(feed.feedUrl).forEach { (k, v) -> req = req.header(k, v) }
        return client.newCall(req.build()).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            // Use string() so OkHttp honours the charset in Content-Type (fixes Windows-1255 Hebrew feeds)
            val text = response.body?.string() ?: return emptyList()
            val parser = Xml.newPullParser()
            parser.setInput(text.reader())
            parseFeed(parser, feed)
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

    private fun parseFeed(parser: XmlPullParser, feed: FeedConfig): List<ArticleItem> {
        val items = mutableListOf<ArticleItem>()
        var event = try { parser.next() } catch (_: Exception) { return emptyList() }
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG &&
                (parser.name.equals("item", true) || parser.name.equals("entry", true))) {
                parseItem(parser, feed)?.let { items += it }
                if (items.size >= 50) break
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
                .toString().trim().take(400)
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

    private fun applyFiltersAndSort(items: List<ArticleItem>, config: WidgetConfig): List<ArticleItem> {
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
                // Cap each feed at its 10 most-relevant articles before the global sort
                // so a single high-frequency feed can't fill all visible widget slots.
                val perFeed = filtered
                    .groupBy { it.feedId }
                    .values
                    .flatMap { group ->
                        when (config.sortOrder) {
                            SortOrder.OLDEST.key       -> group.sortedBy { it.publishedAt }
                            SortOrder.UNREAD_FIRST.key -> group.sortedWith(compareBy({ it.isRead }, { -it.publishedAt }))
                            else                       -> group.sortedByDescending { it.publishedAt }
                        }.take(10)
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
