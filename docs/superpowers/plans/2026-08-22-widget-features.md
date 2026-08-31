# Widget features — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add five features to the NewsFeed widget: inline article expand/collapse reader, configurable external open, per-feed thumbnails, global font size slider, refresh-now button + countdown, and edit-feed-name/URL dialog.

**Architecture:** All widget interactions migrate from `DeepLinkActivity` to Glance `ActionCallback`s (`RefreshNowCallback`, `ToggleExpandCallback`, `OpenExternalCallback`). New data fields flow from the data model → RSS parser → WidgetWorker → Glance state → widget rendering. Config screen gains three new controls (font slider, external app dropdown, edit-feed dialog).

**Tech Stack:** Jetpack Compose Glance 1.1.0, WorkManager 2.9.0, OkHttp 4.12.0, DataStore Preferences, kotlinx.serialization, Android XmlPullParser (no namespace processing).

**Status:** ✅ All tasks complete. Commits: `ce26b32` (race condition fix), `ede46bd` (encodeToString import), `110c1ea` (6 bugs), `22d4547` (Hebrew bitmap font).

> **2026-08-25 update:** the "Read You" companion-app external-open option (referenced throughout Task 5/9's code below as `"readyou"` / `me.ash.reader`) has since been removed from the shipped app — only Browser and Share sheet remain. A QA pass on `app-debug_v25.apk` also drove a further round of fixes (silent-refresh-failure feedback, feed-name overflow at narrow widths). The task snippets below are left as-is as a historical record of what was implemented at each step; see the **"Bug fixes and improvements (shipped 2026-08-25, post-QA)"** section at the bottom of `docs/superpowers/specs/2026-08-22-widget-features-design.md` for the current, authoritative state of these features.

> **2026-08-27 update:** several more rounds of fixes landed on top of the above — `NewsFeedWidget.sizeMode` (was defaulting to `Single`, silently freezing `LocalSize.current` at the widget's minimum size for every Glamour headline bitmap), the Glamour handwriting font itself (now Refoyl, bold via fake-bold — Miriam Libre Bold's replacement was left unresolved as of the 2026-08-26 entries below), a genuine RTL bug affecting every Hebrew headline this whole project (`StaticLayout` was left-aligning instead of right-aligning), the Glamour meta-row layout (feed name now right-justified on its own line, consistent whether or not the row has a thumbnail), Glamour's headline color (switched to the theme's actual primary/darkest `onSurface` token, was accidentally using the secondary `onSurfaceVariant` shade), and Glamour body text now also using the handwriting font at regular weight for short snippets only. See the **"Bug fixes and features (shipped 2026-08-27)"** section at the bottom of the design doc — same historical-record convention as above; this plan file's task snippets are not being kept in sync task-by-task.

> **2026-08-28 update:** the "feed name on its own line" layout from 2026-08-27 was reverted — feed name is back grouped with the favicon circle on the shared meta row, and the thumbnail is confined to only the headline's row instead (achieves the same consistency goal from the other direction). Also this round: a real `sizeMode = Exact` memory-crash regression (RemoteViews bitmap budget) fixed with a 350dp width cap; Glamour/Data Science/Aerospace headline and body colors adjusted per explicit hex requests (Glamour's `0xFF2C1A0A` headline further adjusted to `0xFF4A2E14` after "looks black" feedback); English text inside Glamour now spans onto Android's system cursive font instead of a plain fallback (verified via `fontTools` that Refoyl/Solitreo/Dana Yad all have zero Latin glyphs); the config screen's live preview — accurate for font/color/width since 2026-08-27 — was found to still not be RTL-aligned at all and fixed; and the Glamour font itself changed again, from Refoyl to **Dana Yad**, this time with the user's explicit sign-off on its non-OFL license restrictions (documented directly in `TextBitmapHelper.kt` and in `README.md`'s License section). See the **"Bug fixes and features (shipped 2026-08-28)"** section at the bottom of the design doc.

> **2026-08-31 update:** first real-hardware-device testing session (Samsung Galaxy S21+, real touch/uiautomator) — unblocked several verifications earlier entries had flagged as unconfirmed due to synthetic-input limits, and found genuine new bugs. Fixed: full-article mojibake on sites that declare charset only via an in-HTML `<meta>` tag, not the HTTP header (rotter.net); a real RemoteViews crash (`Column container cannot have more than 10 elements`, hit once full-article "Load more" chunks plus per-chunk "Open in browser" links pushed one row's element count past that hard limit — fixed with nested `Column` wrappers); the article-list's flat 15-row cap, replaced with the same computed-memory-budget chunked "Load more" pattern already used for full articles; a duplicate-collapsing Glance rendering bug (`key()` fix) that hid every "Open in browser" link but the first; the no-image headline-width bug (was checking the feed's display-mode setting instead of whether that specific article actually had a thumbnail); and a too-thin thumbnail on 1-line headlines. Added: per-feed article retention setting, and full backlog fetch on a feed's first load. Also: this session's builds ran from the user's own terminal against the cached Gradle distribution — the assistant's own tool sandbox cannot establish the JVM loopback socket Gradle's daemon needs (a Windows/JDK AppContainer restriction), and `gradlew.bat`/`gradlew` are non-functional in this repo since `gradle-wrapper.jar` was never committed. See the **"Bug fixes and features (shipped 2026-08-31)"** section at the bottom of the design doc.

---

## File map

| Action | File |
|---|---|
| Modify | `app/src/main/java/com/newsfeed/widget/data/FeedConfig.kt` |
| Modify | `app/src/main/java/com/newsfeed/widget/data/WidgetStateKey.kt` |
| **Create** | `app/src/main/java/com/newsfeed/widget/data/ThumbnailHelper.kt` |
| Modify | `app/src/main/java/com/newsfeed/widget/data/NewsFeedRepository.kt` |
| **Create** | `app/src/main/java/com/newsfeed/widget/glance/RefreshNowCallback.kt` |
| **Create** | `app/src/main/java/com/newsfeed/widget/glance/ToggleExpandCallback.kt` |
| **Create** | `app/src/main/java/com/newsfeed/widget/glance/OpenExternalCallback.kt` |
| Modify | `app/src/main/java/com/newsfeed/widget/glance/WidgetWorker.kt` |
| Modify | `app/src/main/java/com/newsfeed/widget/glance/FeedItemRow.kt` |
| Modify | `app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt` |
| Modify | `app/src/main/java/com/newsfeed/widget/config/FeedConfigRow.kt` |
| Modify | `app/src/main/java/com/newsfeed/widget/config/WidgetConfigActivity.kt` |
| **Delete** | `app/src/main/java/com/newsfeed/widget/DeepLinkActivity.kt` |
| Modify | `app/src/main/AndroidManifest.xml` |

---

## Task 1: Data model — new fields on FeedConfig, WidgetConfig, ArticleItem

**Files:** Modify `app/src/main/java/com/newsfeed/widget/data/FeedConfig.kt`

- [ ] **Replace the entire file** with the version below. All new fields have defaults so existing DataStore JSON deserializes cleanly (`ignoreUnknownKeys = true` is already set in `WidgetConfigStore`).

```kotlin
package com.newsfeed.widget.data

import kotlinx.serialization.Serializable

@Serializable
data class FeedConfig(
    val feedId: String,
    val displayName: String,
    val feedUrl: String = "",
    val accentColor: String = "#9B72E3",
    val fontFamily: String = "sans",
    val textStyle: Set<String> = emptySet(),
    val layoutDirection: String = "ltr",
    val displayMode: String = "text",   // "text" | "image"
    val enabled: Boolean = true,
)

@Serializable
data class WidgetConfig(
    val widgetId: Int,
    val sortOrder: String = "newest",
    val filter: String = "all",
    val feedOrder: List<String> = emptyList(),
    val feeds: List<FeedConfig> = emptyList(),
    val refreshIntervalMinutes: Int = 15,
    val fontSize: Float = 1.0f,                // 0.75 – 1.5
    val externalApp: String = "browser",       // "readyou" | "browser" | "share"
)

@Serializable
data class ArticleItem(
    val id: String,
    val feedId: String,
    val feedName: String,
    val title: String,
    val articleUrl: String = "",
    val description: String = "",              // plain text, max 400 chars
    val imageUrl: String = "",                 // first image from RSS enclosure/media tags
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
```

- [ ] **Verify build compiles** (no other files reference the removed fields yet — this step only adds fields):
```bash
cd C:\readyou-widget && gradle assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Commit**
```bash
git add app/src/main/java/com/newsfeed/widget/data/FeedConfig.kt
git commit -m "feat: add displayMode, fontSize, externalApp, description, imageUrl fields"
```

---

## Task 2: Glance state keys — lastRefreshTime + expandedArticleId

**Files:** Modify `app/src/main/java/com/newsfeed/widget/data/WidgetStateKey.kt`

- [ ] **Replace the file:**

```kotlin
package com.newsfeed.widget.data

import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object WidgetStateKey {
    val articles          = stringPreferencesKey("articles_json")
    val configJson        = stringPreferencesKey("config_json")
    val lastRefreshTime   = longPreferencesKey("last_refresh_time")
    val expandedArticleId = stringPreferencesKey("expanded_article_id")
}
```

- [ ] **Build check:**
```bash
gradle assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Commit**
```bash
git add app/src/main/java/com/newsfeed/widget/data/WidgetStateKey.kt
git commit -m "feat: add lastRefreshTime and expandedArticleId state keys"
```

---

## Task 3: ThumbnailHelper — shared cache file path

**Files:** Create `app/src/main/java/com/newsfeed/widget/data/ThumbnailHelper.kt`

- [ ] **Create the file:**

```kotlin
package com.newsfeed.widget.data

import android.content.Context
import java.io.File

object ThumbnailHelper {
    fun file(context: Context, articleId: String): File =
        File(context.cacheDir, "thumbs/thumb_${articleId.hashCode()}.jpg")
}
```

- [ ] **Build check:**
```bash
gradle assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Commit**
```bash
git add app/src/main/java/com/newsfeed/widget/data/ThumbnailHelper.kt
git commit -m "feat: add ThumbnailHelper for shared cache file path"
```

---

## Task 4: RSS parser — description, imageUrl, thumbnail download

**Files:** Modify `app/src/main/java/com/newsfeed/widget/data/NewsFeedRepository.kt`

- [ ] **Replace the entire file.** Key changes: `parseItem()` captures `<description>`, `<content:encoded>`, `<media:thumbnail>`, `<media:content>`, `<enclosure>`; new `downloadThumbnails()` and `scaleBitmap()` methods.

```kotlin
package com.newsfeed.widget.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.Html
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

class NewsFeedRepository(private val context: Context) {

    val client = OkHttpClient.Builder()
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

    suspend fun fetchFeedTitle(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("User-Agent", "NewsFeedWidget/1.0").build()
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
                        .header("User-Agent", "NewsFeedWidget/1.0").build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@runCatching
                        val bmp = resp.body?.byteStream()?.let { BitmapFactory.decodeStream(it) }
                            ?: return@runCatching
                        val scaled = scaleBitmap(bmp, 120)
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
        val req = Request.Builder().url(feed.feedUrl).header("User-Agent", "NewsFeedWidget/1.0").build()
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
                if (items.size >= 25) break
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

                    // Description: prefer content:encoded over description
                    tag == "content:encoded" -> {
                        val text = runCatching { parser.nextText() }.getOrDefault("")
                        if (text.isNotBlank()) rawDescription = text
                    }
                    tag == "description" && rawDescription.isEmpty() ->
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

        val description = if (rawDescription.isNotBlank()) {
            @Suppress("DEPRECATION")
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
        val enabledFeedIds  = config.feeds.filter { it.enabled }.map { it.feedId }.toSet()
        val feedPositions   = config.feedOrder.withIndex().associate { (i, id) -> id to i }
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
            SortOrder.OLDEST.key     -> filtered.sortedBy { it.publishedAt }
            SortOrder.BY_FEED.key    -> filtered.sortedWith(compareBy({ feedPositions[it.feedId] ?: Int.MAX_VALUE }, { -it.publishedAt }))
            SortOrder.UNREAD_FIRST.key -> filtered.sortedWith(compareBy({ it.isRead }, { -it.publishedAt }))
            else                     -> filtered.sortedByDescending { it.publishedAt }
        }
    }

    private fun scaleBitmap(bitmap: Bitmap, maxPx: Int): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        if (w <= maxPx && h <= maxPx) return bitmap
        val scale = maxPx.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
    }
}
```

- [ ] **Build check:**
```bash
gradle assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Commit**
```bash
git add app/src/main/java/com/newsfeed/widget/data/NewsFeedRepository.kt
git commit -m "feat: parse description, imageUrl from RSS; add downloadThumbnails()"
```

---

## Task 5: Three Glance ActionCallbacks

**Files:** Create three new files in `app/src/main/java/com/newsfeed/widget/glance/`

- [ ] **Create `RefreshNowCallback.kt`:**

```kotlin
package com.newsfeed.widget.glance

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback

class RefreshNowCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        WidgetWorker.refreshNow(context)
    }
}
```

- [ ] **Create `ToggleExpandCallback.kt`:**

```kotlin
package com.newsfeed.widget.glance

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.newsfeed.widget.data.WidgetStateKey

class ToggleExpandCallback : ActionCallback {
    companion object {
        val ARTICLE_ID_KEY = ActionParameters.Key<String>("articleId")
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val articleId = parameters[ARTICLE_ID_KEY] ?: return
        updateAppWidgetState(context, glanceId) { prefs ->
            val current = prefs[WidgetStateKey.expandedArticleId] ?: ""
            prefs[WidgetStateKey.expandedArticleId] = if (current == articleId) "" else articleId
        }
        NewsFeedWidget().update(context, glanceId)
    }
}
```

- [ ] **Create `OpenExternalCallback.kt`:**

```kotlin
package com.newsfeed.widget.glance

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.newsfeed.widget.data.ReadStatusStore
import com.newsfeed.widget.data.WidgetConfigStore
import kotlinx.coroutines.flow.first

class OpenExternalCallback : ActionCallback {
    companion object {
        val ARTICLE_URL_KEY = ActionParameters.Key<String>("articleUrl")
        val ARTICLE_ID_KEY  = ActionParameters.Key<String>("articleId")
        val WIDGET_ID_KEY   = ActionParameters.Key<Int>("widgetId")
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val articleUrl = parameters[ARTICLE_URL_KEY] ?: return
        val articleId  = parameters[ARTICLE_ID_KEY]  ?: ""
        val widgetId   = parameters[WIDGET_ID_KEY]   ?: return

        val config = WidgetConfigStore(context).configFlow(widgetId).first()

        if (articleId.isNotBlank()) ReadStatusStore(context).markRead(articleId)

        val uri    = Uri.parse(articleUrl)
        val intent = when (config.externalApp) {
            "readyou" -> {
                val ry = Intent(Intent.ACTION_VIEW, uri).setPackage("me.ash.reader")
                if (ry.resolveActivity(context.packageManager) != null) ry
                else Intent(Intent.ACTION_VIEW, uri)
            }
            "share" -> Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, articleUrl),
                "Share article",
            )
            else -> Intent(Intent.ACTION_VIEW, uri)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        runCatching { context.startActivity(intent) }
        WidgetWorker.refreshNow(context)
    }
}
```

- [ ] **Build check:**
```bash
gradle assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Commit**
```bash
git add app/src/main/java/com/newsfeed/widget/glance/RefreshNowCallback.kt \
        app/src/main/java/com/newsfeed/widget/glance/ToggleExpandCallback.kt \
        app/src/main/java/com/newsfeed/widget/glance/OpenExternalCallback.kt
git commit -m "feat: add RefreshNow, ToggleExpand, OpenExternal action callbacks"
```

---

## Task 6: WidgetWorker — write lastRefreshTime + download thumbnails

**Files:** Modify `app/src/main/java/com/newsfeed/widget/glance/WidgetWorker.kt`

- [ ] **Replace the entire file:**

```kotlin
package com.newsfeed.widget.glance

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.newsfeed.widget.data.ReadStatusStore
import com.newsfeed.widget.data.NewsFeedRepository
import com.newsfeed.widget.data.WidgetConfigStore
import com.newsfeed.widget.data.WidgetStateKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

class WidgetWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store   = WidgetConfigStore(context)
        val repo    = NewsFeedRepository(context)
        val readIds = ReadStatusStore(context).readIdsFlow().first()
        val manager = GlanceAppWidgetManager(context)
        val widgetIds = manager.getGlanceIds(NewsFeedWidget::class.java)

        for (glanceId in widgetIds) {
            val appWidgetId = manager.getAppWidgetId(glanceId)
            val config      = store.configFlow(appWidgetId).first()
            val articles    = repo.getArticles(config).map { article ->
                if (article.id in readIds) article.copy(isRead = true) else article
            }

            repo.downloadThumbnails(articles, config.feeds)

            val now = System.currentTimeMillis()
            updateAppWidgetState(context, glanceId) { prefs ->
                prefs[WidgetStateKey.articles]        = Json.encodeToString(articles)
                prefs[WidgetStateKey.configJson]      = Json.encodeToString(config)
                prefs[WidgetStateKey.lastRefreshTime] = now
            }
        }

        NewsFeedWidget().updateAll(context)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "NewsFeedWidgetRefresh"

        fun schedule(context: Context, intervalMinutes: Long = 15) {
            val request = PeriodicWorkRequestBuilder<WidgetWorker>(
                intervalMinutes.coerceAtLeast(15), TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.REPLACE, request,
            )
        }

        fun refreshNow(context: Context) {
            WorkManager.getInstance(context)
                .enqueue(OneTimeWorkRequestBuilder<WidgetWorker>().build())
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
```

- [ ] **Build check:**
```bash
gradle assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Commit**
```bash
git add app/src/main/java/com/newsfeed/widget/glance/WidgetWorker.kt
git commit -m "feat: write lastRefreshTime to state; download thumbnails in worker"
```

---

## Task 7: FeedItemRow — expand/collapse, dimming, thumbnail, font size

**Files:** Modify `app/src/main/java/com/newsfeed/widget/glance/FeedItemRow.kt`

- [ ] **Replace the entire file.** The composable now accepts `expandedArticleId`, `widgetId`, and `fontSize`. Expanded article shows description + Open button. Non-expanded articles dim when any article is open.

```kotlin
package com.newsfeed.widget.glance

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontStyle
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.FeedConfig
import com.newsfeed.widget.data.ThumbnailHelper
import java.util.concurrent.TimeUnit

@Composable
fun FeedItemRow(
    article: ArticleItem,
    feedConfig: FeedConfig,
    expandedArticleId: String,
    widgetId: Int,
    fontSize: Float,
) {
    val context   = LocalContext.current
    val isExpanded    = article.id == expandedArticleId
    val anyExpanded   = expandedArticleId.isNotEmpty()
    val isDimmed      = anyExpanded && !isExpanded

    val accentColor   = runCatching { Color.parseColor(feedConfig.accentColor) }
        .getOrDefault(Color.parseColor("#9B72E3"))
    val accentProvider = ColorProvider(androidx.compose.ui.graphics.Color(accentColor))

    val dimColor       = ColorProvider(androidx.compose.ui.graphics.Color(0xFF555566))
    val dimMetaColor   = ColorProvider(androidx.compose.ui.graphics.Color(0xFF444455))

    val toggleAction = actionRunCallback<ToggleExpandCallback>(
        actionParametersOf(ToggleExpandCallback.ARTICLE_ID_KEY to article.id)
    )

    val metaFontSize = (9f * fontSize).sp
    val headlineFontSize = (13f * fontSize).sp

    val isRtl = feedConfig.layoutDirection == "rtl"

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clickable(toggleAction),
        verticalAlignment = Alignment.Top,
    ) {
        if (!isRtl) {
            Box(modifier = GlanceModifier.width(3.dp).height(if (isExpanded) 60.dp else 38.dp)
                .background(accentProvider)) {}
        }

        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalAlignment = if (isRtl) Alignment.End else Alignment.Start,
        ) {
            // Meta row: feed name + timestamp
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = if (isRtl) Alignment.End else Alignment.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!article.isRead && !isDimmed) {
                    Box(modifier = GlanceModifier.width(5.dp).height(5.dp)
                        .background(accentProvider)) {}
                    Spacer(GlanceModifier.width(3.dp))
                }
                if (isRtl) {
                    Spacer(GlanceModifier.defaultWeight())
                    Text(relativeTime(article.publishedAt),
                        style = TextStyle(fontSize = metaFontSize,
                            color = if (isDimmed) dimMetaColor else GlanceTheme.colors.onSurfaceVariant))
                    Spacer(GlanceModifier.width(5.dp))
                    Text(article.feedName,
                        style = TextStyle(fontSize = metaFontSize,
                            color = if (isDimmed) dimColor else accentProvider))
                } else {
                    Text(article.feedName,
                        style = TextStyle(fontSize = metaFontSize,
                            color = if (isDimmed) dimColor else accentProvider))
                    Spacer(GlanceModifier.width(5.dp))
                    Text(relativeTime(article.publishedAt),
                        style = TextStyle(fontSize = metaFontSize,
                            color = if (isDimmed) dimMetaColor else GlanceTheme.colors.onSurfaceVariant))
                    Spacer(GlanceModifier.defaultWeight())
                }
            }

            Spacer(GlanceModifier.height(2.dp))

            // Headline
            Text(
                text = article.title,
                style = TextStyle(
                    fontSize = headlineFontSize,
                    fontWeight = if ("bold" in feedConfig.textStyle) FontWeight.Bold else FontWeight.Normal,
                    fontStyle  = if ("italic" in feedConfig.textStyle) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = if ("underline" in feedConfig.textStyle) TextDecoration.Underline else TextDecoration.None,
                    fontFamily = when (feedConfig.fontFamily) {
                        "serif" -> FontFamily.Serif
                        "mono"  -> FontFamily.Monospace
                        else    -> FontFamily.SansSerif
                    },
                    color = when {
                        isDimmed    -> dimColor
                        article.isRead -> GlanceTheme.colors.onSurfaceVariant
                        else        -> GlanceTheme.colors.onSurface
                    },
                    textAlign = if (isRtl) androidx.glance.text.TextAlign.End
                                else      androidx.glance.text.TextAlign.Start,
                ),
                maxLines = 2,
                modifier = GlanceModifier.fillMaxWidth(),
            )

            // Expanded: description + open button
            if (isExpanded) {
                if (article.description.isNotBlank()) {
                    Spacer(GlanceModifier.height(4.dp))
                    Text(
                        text = article.description,
                        style = TextStyle(
                            fontSize = (10f * fontSize).sp,
                            color = GlanceTheme.colors.onSurfaceVariant,
                            textAlign = if (isRtl) androidx.glance.text.TextAlign.End
                                        else      androidx.glance.text.TextAlign.Start,
                        ),
                        maxLines = 5,
                        modifier = GlanceModifier.fillMaxWidth(),
                    )
                }
                if (article.articleUrl.isNotBlank()) {
                    Spacer(GlanceModifier.height(6.dp))
                    Text(
                        text = "Open article",
                        style = TextStyle(
                            fontSize = (9f * fontSize).sp,
                            color = accentProvider,
                        ),
                        modifier = GlanceModifier
                            .background(ColorProvider(androidx.compose.ui.graphics.Color(0x229B72E3)))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .clickable(
                                actionRunCallback<OpenExternalCallback>(
                                    actionParametersOf(
                                        OpenExternalCallback.ARTICLE_URL_KEY to article.articleUrl,
                                        OpenExternalCallback.ARTICLE_ID_KEY  to article.id,
                                        OpenExternalCallback.WIDGET_ID_KEY   to widgetId,
                                    )
                                )
                            ),
                    )
                }
            }
        }

        // Right-side thumbnail (image mode, not dimmed, not expanded)
        if (feedConfig.displayMode == "image" && !isExpanded) {
            val thumbFile = ThumbnailHelper.file(context, article.id)
            if (thumbFile.exists()) {
                val bmp = BitmapFactory.decodeFile(thumbFile.absolutePath)
                if (bmp != null) {
                    Image(
                        provider = ImageProvider(bmp),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = GlanceModifier.width(40.dp).height(40.dp),
                    )
                }
            }
        }

        if (isRtl) {
            Spacer(GlanceModifier.width(6.dp))
            Box(modifier = GlanceModifier.width(3.dp).height(if (isExpanded) 60.dp else 38.dp)
                .background(accentProvider)) {}
        }
    }
}

private fun relativeTime(epochMs: Long): String {
    val diff = System.currentTimeMillis() - epochMs
    return when {
        diff < TimeUnit.MINUTES.toMillis(60) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m"
        diff < TimeUnit.HOURS.toMillis(24)   -> "${TimeUnit.MILLISECONDS.toHours(diff)}h"
        else                                 -> "${TimeUnit.MILLISECONDS.toDays(diff)}d"
    }
}
```

- [ ] **Build check:**
```bash
gradle assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL` (NewsFeedWidget.kt will have compile errors until Task 8 — fix those first if needed)

- [ ] **Commit**
```bash
git add app/src/main/java/com/newsfeed/widget/glance/FeedItemRow.kt
git commit -m "feat: FeedItemRow — expand/collapse, dimming, thumbnails, font size"
```

---

## Task 8: NewsFeedWidget — footer refresh + countdown, pass new params

**Files:** Modify `app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt`

- [ ] **Replace the entire file:**

```kotlin
package com.newsfeed.widget.glance

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.WidgetConfig
import com.newsfeed.widget.data.WidgetStateKey
import kotlinx.serialization.json.Json

class NewsFeedWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: android.content.Context, id: GlanceId) {
        provideContent { WidgetContent() }
    }

    @Composable
    private fun WidgetContent() {
        val prefs            = currentState<androidx.datastore.preferences.core.Preferences>()
        val configJson       = prefs[WidgetStateKey.configJson]
        val articlesJson     = prefs[WidgetStateKey.articles]
        val lastRefreshTime  = prefs[WidgetStateKey.lastRefreshTime] ?: 0L
        val expandedArticleId = prefs[WidgetStateKey.expandedArticleId] ?: ""

        val config = configJson
            ?.let { runCatching { Json.decodeFromString<WidgetConfig>(it) }.getOrNull() }
            ?: WidgetConfig(widgetId = -1)

        val articles: List<ArticleItem> = articlesJson
            ?.let { runCatching { Json.decodeFromString<List<ArticleItem>>(it) }.getOrNull() }
            ?: emptyList()

        val feedMap    = config.feeds.associateBy { it.feedId }
        val unreadCount = articles.count { !it.isRead }

        GlanceTheme {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(GlanceTheme.colors.surface)
                    .cornerRadius(18.dp)
                    .padding(0.dp),
            ) {
                WidgetHeader(unreadCount)
                Divider()

                if (articles.isEmpty()) {
                    EmptyState()
                } else {
                    articles.take(10).forEach { article ->
                        val feedConfig = feedMap[article.feedId] ?: return@forEach
                        FeedItemRow(
                            article           = article,
                            feedConfig        = feedConfig,
                            expandedArticleId = expandedArticleId,
                            widgetId          = config.widgetId,
                            fontSize          = config.fontSize,
                        )
                        Divider(thin = true)
                    }
                }

                Spacer(GlanceModifier.defaultWeight())
                WidgetFooter(lastRefreshTime, config.refreshIntervalMinutes)
            }
        }
    }

    @Composable
    private fun WidgetHeader(unreadCount: Int) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "NewsFeed",
                style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
            Spacer(GlanceModifier.defaultWeight())
            if (unreadCount > 0) {
                Text(
                    text = "$unreadCount",
                    style = TextStyle(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = ColorProvider(Color(0xFFC4A9FF)),
                    ),
                    modifier = GlanceModifier
                        .background(ColorProvider(Color(0x296750A4)))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }

    @Composable
    private fun WidgetFooter(lastRefreshTime: Long, intervalMinutes: Int) {
        Divider()
        val now          = System.currentTimeMillis()
        val nextMs       = lastRefreshTime + intervalMinutes * 60_000L
        val minutesLeft  = ((nextMs - now) / 60_000L).coerceIn(0L, intervalMinutes.toLong())
        val refreshLabel = "↻ refresh in ${minutesLeft}min"

        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = refreshLabel,
                style = TextStyle(
                    fontSize = 11.sp,
                    color = ColorProvider(Color(0xFF9B72E3)),
                ),
                modifier = GlanceModifier.clickable(actionRunCallback<RefreshNowCallback>()),
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                text = "All articles →",
                style = TextStyle(
                    fontSize = 11.sp,
                    color = ColorProvider(Color(0xFF9B72E3)),
                ),
            )
        }
    }

    @Composable
    private fun EmptyState() {
        Box(
            modifier = GlanceModifier.fillMaxWidth().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No articles",
                style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
        }
    }

    @Composable
    private fun Divider(thin: Boolean = false) {
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(if (thin) 0.5.dp else 1.dp)
                .background(GlanceTheme.colors.surfaceVariant),
        ) {}
    }
}

class NewsFeedWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = NewsFeedWidget()

    override fun onEnabled(context: android.content.Context) {
        super.onEnabled(context)
        WidgetWorker.schedule(context)
    }

    override fun onDisabled(context: android.content.Context) {
        super.onDisabled(context)
        WidgetWorker.cancel(context)
    }
}
```

- [ ] **Build check:**
```bash
gradle assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Commit**
```bash
git add app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt
git commit -m "feat: widget footer refresh countdown; pass expandedArticleId, widgetId, fontSize"
```

---

## Task 9: FeedConfigRow — display mode toggle + tappable name

**Files:** Modify `app/src/main/java/com/newsfeed/widget/config/FeedConfigRow.kt`

- [ ] **Replace the entire file.** Adds `onEditRequest: () -> Unit` callback; makes display name tappable; adds Text/Image toggle after the RTL/LTR button.

```kotlin
package com.newsfeed.widget.config

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newsfeed.widget.R
import com.newsfeed.widget.data.FeedConfig
import sh.calvin.reorderable.ReorderableCollectionItemScope

@Composable
fun ReorderableCollectionItemScope.FeedConfigRow(
    feedConfig: FeedConfig,
    onUpdate: (FeedConfig) -> Unit,
    onRemove: () -> Unit,
    onEditRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showColorPicker by remember { mutableStateOf(false) }
    var showFontMenu    by remember { mutableStateOf(false) }

    val accentColor = runCatching {
        Color(android.graphics.Color.parseColor(feedConfig.accentColor))
    }.getOrDefault(Color(0xFF9B72E3))

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_drag_handle),
                contentDescription = "Drag to reorder",
                modifier = Modifier.size(20.dp).draggableHandle(),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            Spacer(Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(accentColor)
                    .clickable { showColorPicker = !showColorPicker }
            )
            Spacer(Modifier.width(8.dp))

            // Tappable name → opens edit dialog
            Text(
                text = feedConfig.displayName,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onEditRequest),
            )

            // Remove
            Text(
                text = "×",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.clickable(onClick = onRemove).padding(4.dp),
            )
            Spacer(Modifier.width(4.dp))

            // RTL / LTR toggle
            val isRtl = feedConfig.layoutDirection == "rtl"
            val dirBorderColor by animateColorAsState(
                if (isRtl) Color(0xFF6750A4) else MaterialTheme.colorScheme.outline,
                label = "dirBorder",
            )
            Text(
                text = if (isRtl) "RTL" else "LTR",
                fontSize = 11.sp,
                color = if (isRtl) Color(0xFFC4A9FF) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .border(0.5.dp, dirBorderColor, RoundedCornerShape(6.dp))
                    .clickable { onUpdate(feedConfig.copy(layoutDirection = if (isRtl) "ltr" else "rtl")) }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Spacer(Modifier.width(4.dp))

            // Text / Image toggle
            val isImage = feedConfig.displayMode == "image"
            val imgBorderColor by animateColorAsState(
                if (isImage) Color(0xFF6750A4) else MaterialTheme.colorScheme.outline,
                label = "imgBorder",
            )
            Text(
                text = if (isImage) "IMG" else "TXT",
                fontSize = 11.sp,
                color = if (isImage) Color(0xFFC4A9FF) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .border(0.5.dp, imgBorderColor, RoundedCornerShape(6.dp))
                    .clickable { onUpdate(feedConfig.copy(displayMode = if (isImage) "text" else "image")) }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }

        if (showColorPicker) {
            Spacer(Modifier.height(8.dp))
            ColorPickerGrid(
                selectedColor = feedConfig.accentColor,
                onColorSelected = {
                    onUpdate(feedConfig.copy(accentColor = it))
                    showColorPicker = false
                },
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box {
                val fontLabel = when (feedConfig.fontFamily) {
                    "serif" -> "Serif"; "mono" -> "Mono"; else -> "Default"
                }
                Text(
                    text = "$fontLabel ▾",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .border(0.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(5.dp))
                        .clickable { showFontMenu = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                DropdownMenu(expanded = showFontMenu, onDismissRequest = { showFontMenu = false }) {
                    listOf("sans" to "Default", "serif" to "Serif", "mono" to "Mono").forEach { (key, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { onUpdate(feedConfig.copy(fontFamily = key)); showFontMenu = false },
                        )
                    }
                }
            }
            StyleToggle("B", "bold",      FontWeight.Bold, feedConfig, onUpdate)
            StyleToggle("I", "italic",    null, feedConfig, onUpdate, fontStyle = FontStyle.Italic)
            StyleToggle("U", "underline", null, feedConfig, onUpdate, textDecoration = TextDecoration.Underline)
        }
    }
}

@Composable
private fun StyleToggle(
    label: String,
    styleKey: String,
    fontWeight: FontWeight? = null,
    feedConfig: FeedConfig,
    onUpdate: (FeedConfig) -> Unit,
    fontStyle: FontStyle? = null,
    textDecoration: TextDecoration? = null,
) {
    val isOn = styleKey in feedConfig.textStyle
    val bgColor     by animateColorAsState(if (isOn) Color(0x296750A4) else Color.Transparent, label = "styleBg")
    val borderColor by animateColorAsState(if (isOn) Color(0xFF6750A4) else Color(0xFF666666),   label = "styleBorder")
    Text(
        text = label,
        fontSize = 12.sp,
        fontWeight = fontWeight ?: FontWeight.Normal,
        fontStyle = fontStyle ?: FontStyle.Normal,
        textDecoration = textDecoration ?: TextDecoration.None,
        color = if (isOn) Color(0xFFC4A9FF) else Color(0xFF888888),
        modifier = Modifier
            .size(28.dp, 24.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(bgColor)
            .border(0.5.dp, borderColor, RoundedCornerShape(5.dp))
            .clickable {
                val updated = if (isOn) feedConfig.textStyle - styleKey else feedConfig.textStyle + styleKey
                onUpdate(feedConfig.copy(textStyle = updated))
            }
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}
```

- [ ] **Build check:**
```bash
gradle assembleDebug 2>&1 | tail -5
```
Expected: compile error in `WidgetConfigActivity.kt` (missing `onEditRequest` param) — fix in Task 10.

- [ ] **Commit** (partial — will finish in Task 10):
```bash
git add app/src/main/java/com/newsfeed/widget/config/FeedConfigRow.kt
git commit -m "feat: FeedConfigRow — tappable name (onEditRequest), TXT/IMG toggle"
```

---

## Task 10: WidgetConfigActivity — font slider, external app dropdown, edit-feed dialog

**Files:** Modify `app/src/main/java/com/newsfeed/widget/config/WidgetConfigActivity.kt`

This is the largest config change. Key additions:
1. `editingFeed` state → `AlertDialog` with Name + URL fields
2. `showExternalMenu` + `externalOptions` dropdown in Sort & Filter section
3. Font size `Slider` in Sort & Filter section
4. `onEditRequest` wired in each `FeedConfigRow` call

- [ ] **Replace the entire file:**

```kotlin
package com.newsfeed.widget.config

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.newsfeed.widget.data.FeedConfig
import com.newsfeed.widget.data.FilterMode
import com.newsfeed.widget.data.OpmlManager
import com.newsfeed.widget.data.NewsFeedRepository
import com.newsfeed.widget.data.SortOrder
import com.newsfeed.widget.data.WidgetConfig
import com.newsfeed.widget.data.WidgetConfigStore
import com.newsfeed.widget.glance.NewsFeedWidget
import com.newsfeed.widget.glance.WidgetWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File

class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appWidgetId = intent.extras
            ?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_CANCELED); finish(); return
        }

        val store = WidgetConfigStore(this)
        val repo  = NewsFeedRepository(this)

        setContent {
            MaterialTheme {
                var config by remember { mutableStateOf(WidgetConfig(widgetId = appWidgetId)) }
                val scope  = rememberCoroutineScope()

                androidx.compose.runtime.LaunchedEffect(appWidgetId) {
                    val saved = store.configFlow(appWidgetId).first()
                    config = saved.copy(feedOrder = saved.feedOrder.ifEmpty { saved.feeds.map { it.feedId } })
                }

                val feedOrder = remember(config.feedOrder) {
                    androidx.compose.runtime.mutableStateListOf(*config.feedOrder.toTypedArray())
                }
                val lazyListState  = rememberLazyListState()
                val reorderState   = rememberReorderableLazyListState(lazyListState) { from, to ->
                    feedOrder.apply { add(to.index, removeAt(from.index)) }
                }

                var showSortMenu     by remember { mutableStateOf(false) }
                var showFilterMenu   by remember { mutableStateOf(false) }
                var showRefreshMenu  by remember { mutableStateOf(false) }
                var showExternalMenu by remember { mutableStateOf(false) }

                val refreshOptions = listOf(
                    15 to "15 minutes", 30 to "30 minutes", 60 to "1 hour",
                    120 to "2 hours", 240 to "4 hours", 360 to "6 hours", 720 to "12 hours",
                )
                val externalOptions = listOf(
                    "browser"  to "Browser",
                    "readyou"  to "NewsFeed",
                    "share"    to "Share sheet",
                )

                var addFeedUrl   by remember { mutableStateOf("") }
                var isAddingFeed by remember { mutableStateOf(false) }
                var addFeedError by remember { mutableStateOf<String?>(null) }
                var statusMessage by remember { mutableStateOf("") }

                // Edit-feed dialog state
                var editingFeed   by remember { mutableStateOf<FeedConfig?>(null) }
                var editName      by remember { mutableStateOf("") }
                var editUrl       by remember { mutableStateOf("") }
                var editUrlError  by remember { mutableStateOf<String?>(null) }
                var isEditLoading by remember { mutableStateOf(false) }

                if (editingFeed != null) {
                    AlertDialog(
                        onDismissRequest = { editingFeed = null },
                        title   = { Text("Edit feed") },
                        text    = {
                            Column {
                                OutlinedTextField(
                                    value = editName,
                                    onValueChange = { editName = it },
                                    label = { Text("Name") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = editUrl,
                                    onValueChange = { editUrl = it; editUrlError = null },
                                    label = { Text("Feed URL") },
                                    singleLine = true,
                                    isError = editUrlError != null,
                                    supportingText = editUrlError?.let { e -> { Text(e) } },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                enabled = !isEditLoading,
                                onClick = {
                                    val original = editingFeed ?: return@TextButton
                                    val newUrl   = editUrl.trim().let { if (it.startsWith("http")) it else "https://$it" }
                                    scope.launch {
                                        var updatedName = editName.trim()
                                        if (newUrl != original.feedUrl) {
                                            isEditLoading = true
                                            val fetched = repo.fetchFeedTitle(newUrl)
                                            if (fetched == null) {
                                                editUrlError  = "Could not load feed — check the URL"
                                                isEditLoading = false
                                                return@launch
                                            }
                                            if (updatedName.isBlank()) updatedName = fetched
                                            isEditLoading = false
                                        }
                                        if (updatedName.isBlank()) updatedName = original.displayName
                                        config = config.copy(
                                            feeds = config.feeds.map {
                                                if (it.feedId == original.feedId)
                                                    it.copy(displayName = updatedName, feedUrl = newUrl, feedId = newUrl)
                                                else it
                                            }
                                        )
                                        val idx = feedOrder.indexOf(original.feedId)
                                        if (idx >= 0) feedOrder[idx] = newUrl
                                        editingFeed = null
                                    }
                                },
                            ) {
                                if (isEditLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                else Text("Save")
                            }
                        },
                        dismissButton = { TextButton(onClick = { editingFeed = null }) { Text("Cancel") } },
                    )
                }

                val importLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    uri ?: return@rememberLauncherForActivityResult
                    scope.launch {
                        val xml = withContext(Dispatchers.IO) {
                            runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.readText() }.getOrNull()
                        } ?: run { statusMessage = "Could not read file"; return@launch }
                        val parsed   = OpmlManager.parse(xml)
                        val existing = config.feeds.map { it.feedId }.toSet()
                        val toAdd    = parsed
                            .filter { (_, url) -> url !in existing }
                            .map { (title, url) -> FeedConfig(feedId = url, displayName = title, feedUrl = url) }
                        if (toAdd.isNotEmpty()) {
                            config = config.copy(feeds = config.feeds + toAdd)
                            toAdd.forEach { feedOrder.add(it.feedId) }
                            statusMessage = "Added ${toAdd.size} feed(s)"
                        } else {
                            statusMessage = "No new feeds found"
                        }
                    }
                }

                fun doAddFeed() {
                    val raw = addFeedUrl.trim(); if (raw.isBlank()) return
                    val url = if (raw.startsWith("http")) raw else "https://$raw"
                    scope.launch {
                        isAddingFeed = true; addFeedError = null; statusMessage = ""
                        val title = repo.fetchFeedTitle(url)
                        if (title != null) {
                            config = config.copy(feeds = config.feeds + FeedConfig(feedId = url, displayName = title, feedUrl = url))
                            feedOrder.add(url); addFeedUrl = ""
                        } else { addFeedError = "Could not load feed — check the URL" }
                        isAddingFeed = false
                    }
                }

                fun doExport() {
                    scope.launch {
                        val file = withContext(Dispatchers.IO) {
                            val dir = File(cacheDir, "opml").also { it.mkdirs() }
                            File(dir, "feeds.opml").also { it.writeText(OpmlManager.export(config.feeds)) }
                        }
                        val uri = FileProvider.getUriForFile(this@WidgetConfigActivity, "${packageName}.fileprovider", file)
                        startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/xml"; putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "Export OPML",
                        ))
                    }
                }

                val fontSizeLabel = when {
                    config.fontSize < 0.9f -> "Small"
                    config.fontSize > 1.2f -> "Large"
                    else                   -> "Medium"
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Widget settings") },
                            actions = {
                                TextButton(onClick = {
                                    val final = config.copy(feedOrder = feedOrder.toList())
                                    scope.launch {
                                        store.save(final)
                                        WidgetWorker.schedule(this@WidgetConfigActivity, final.refreshIntervalMinutes.toLong())
                                        WidgetWorker.refreshNow(this@WidgetConfigActivity)
                                        val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
                                        NewsFeedWidget().update(this@WidgetConfigActivity, glanceId)
                                        setResult(RESULT_OK, Intent().apply { putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId) })
                                        finish()
                                    }
                                }) { Text("Save") }
                            },
                        )
                    },
                ) { paddingValues ->
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize().padding(paddingValues),
                    ) {
                        // ── Sort, Filter, Refresh, External App, Font Size ──
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("SORT & FILTER", fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.05.sp)
                                Spacer(Modifier.height(8.dp))

                                // Sort
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Sort by", style = MaterialTheme.typography.bodyMedium)
                                    androidx.compose.foundation.layout.Box {
                                        TextButton(onClick = { showSortMenu = true }) {
                                            Text("${SortOrder.entries.first { it.key == config.sortOrder }.labelRes} ▾", fontSize = 13.sp)
                                        }
                                        DropdownMenu(showSortMenu, { showSortMenu = false }) {
                                            SortOrder.entries.forEach { o ->
                                                DropdownMenuItem(text = { Text(o.labelRes) },
                                                    onClick = { config = config.copy(sortOrder = o.key); showSortMenu = false })
                                            }
                                        }
                                    }
                                }

                                // Filter
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Show", style = MaterialTheme.typography.bodyMedium)
                                    androidx.compose.foundation.layout.Box {
                                        TextButton(onClick = { showFilterMenu = true }) {
                                            Text("${FilterMode.entries.first { it.key == config.filter }.labelRes} ▾", fontSize = 13.sp)
                                        }
                                        DropdownMenu(showFilterMenu, { showFilterMenu = false }) {
                                            FilterMode.entries.forEach { m ->
                                                DropdownMenuItem(text = { Text(m.labelRes) },
                                                    onClick = { config = config.copy(filter = m.key); showFilterMenu = false })
                                            }
                                        }
                                    }
                                }

                                // Refresh interval
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Refresh every", style = MaterialTheme.typography.bodyMedium)
                                    androidx.compose.foundation.layout.Box {
                                        val label = refreshOptions.firstOrNull { it.first == config.refreshIntervalMinutes }?.second
                                            ?: "${config.refreshIntervalMinutes} min"
                                        TextButton(onClick = { showRefreshMenu = true }) { Text("$label ▾", fontSize = 13.sp) }
                                        DropdownMenu(showRefreshMenu, { showRefreshMenu = false }) {
                                            refreshOptions.forEach { (minutes, lbl) ->
                                                DropdownMenuItem(text = { Text(lbl) },
                                                    onClick = { config = config.copy(refreshIntervalMinutes = minutes); showRefreshMenu = false })
                                            }
                                        }
                                    }
                                }

                                // Open article in
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Open article in", style = MaterialTheme.typography.bodyMedium)
                                    androidx.compose.foundation.layout.Box {
                                        val label = externalOptions.firstOrNull { it.first == config.externalApp }?.second ?: "Browser"
                                        TextButton(onClick = { showExternalMenu = true }) { Text("$label ▾", fontSize = 13.sp) }
                                        DropdownMenu(showExternalMenu, { showExternalMenu = false }) {
                                            externalOptions.forEach { (key, lbl) ->
                                                DropdownMenuItem(text = { Text(lbl) },
                                                    onClick = { config = config.copy(externalApp = key); showExternalMenu = false })
                                            }
                                        }
                                    }
                                }

                                // Font size slider
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Font size", style = MaterialTheme.typography.bodyMedium)
                                    Text(fontSizeLabel, fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Slider(
                                    value = config.fontSize,
                                    onValueChange = { config = config.copy(fontSize = it) },
                                    valueRange = 0.75f..1.5f,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            HorizontalDivider()
                        }

                        // ── Add Feed ──
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("ADD FEED", fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.05.sp)
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = addFeedUrl,
                                        onValueChange = { addFeedUrl = it; addFeedError = null },
                                        label = { Text("RSS or Atom feed URL") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        isError = addFeedError != null,
                                        supportingText = addFeedError?.let { e -> { Text(e, color = MaterialTheme.colorScheme.error) } },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = { doAddFeed() }),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    if (isAddingFeed) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                    else TextButton(onClick = { doAddFeed() }, enabled = addFeedUrl.isNotBlank()) { Text("Add") }
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                                    TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Import OPML") }
                                    TextButton(onClick = { doExport() }, enabled = config.feeds.isNotEmpty()) { Text("Export OPML") }
                                }
                                if (statusMessage.isNotEmpty()) {
                                    Text(statusMessage, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            HorizontalDivider()
                        }

                        // ── Feed order header ──
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("FEED ORDER & STYLE", fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.05.sp)
                                Text("Tap name to edit  ·  Drag to reorder  ·  × to remove", fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            }
                        }

                        // ── Per-feed rows ──
                        items(count = feedOrder.size, key = { feedOrder[it] }) { index ->
                            val feedId     = feedOrder[index]
                            val feedConfig = config.feeds.firstOrNull { it.feedId == feedId } ?: return@items

                            ReorderableItem(reorderState, key = feedId) {
                                Column {
                                    FeedConfigRow(
                                        feedConfig = feedConfig,
                                        onUpdate   = { updated ->
                                            config = config.copy(feeds = config.feeds.map { if (it.feedId == updated.feedId) updated else it })
                                        },
                                        onRemove   = {
                                            feedOrder.remove(feedId)
                                            config = config.copy(feeds = config.feeds.filter { it.feedId != feedId })
                                        },
                                        onEditRequest = {
                                            editName    = feedConfig.displayName
                                            editUrl     = feedConfig.feedUrl
                                            editUrlError = null
                                            editingFeed = feedConfig
                                        },
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Build check:**
```bash
gradle assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Commit**
```bash
git add app/src/main/java/com/newsfeed/widget/config/WidgetConfigActivity.kt
git commit -m "feat: font size slider, external app setting, edit-feed dialog in config"
```

---

## Task 11: Remove DeepLinkActivity + update manifest

**Files:** Delete `DeepLinkActivity.kt`; modify `AndroidManifest.xml`

- [ ] **Delete the file:**
```bash
rm "C:\readyou-widget\app\src\main\java\com\readyou\widget\DeepLinkActivity.kt"
```

- [ ] **Update `AndroidManifest.xml`** — remove the `DeepLinkActivity` entry and the `SystemJobService` duplicate (WorkManager's merged manifest already declares it). Keep `<queries>` for `me.ash.reader`. Replace the entire file:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <queries>
        <package android:name="me.ash.reader" />
        <package android:name="io.github.ashinch.readyou" />
    </queries>

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <application
        android:allowBackup="true"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.DeviceDefault">

        <receiver
            android:name=".glance.NewsFeedWidgetReceiver"
            android:exported="true"
            android:label="@string/widget_label">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/appwidget_info" />
        </receiver>

        <activity
            android:name=".config.WidgetConfigActivity"
            android:exported="true"
            android:theme="@android:style/Theme.DeviceDefault">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_CONFIGURE" />
            </intent-filter>
        </activity>

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>

    </application>

</manifest>
```

- [ ] **Build check:**
```bash
gradle assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Commit**
```bash
git add app/src/main/AndroidManifest.xml
git rm app/src/main/java/com/newsfeed/widget/DeepLinkActivity.kt
git commit -m "feat: remove DeepLinkActivity; clean up manifest"
```

---

## Task 12: Final build + push

- [ ] **Full clean build:**
```bash
gradle clean assembleDebug 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL` with APK at `app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Update README `In development` section** — move items to the main Features section now that they're implemented, remove the `In development` block.

- [ ] **Push:**
```bash
git push
```

---

## Self-review

**Spec coverage:**
- ✅ Refresh now button + countdown → Task 5 (RefreshNowCallback), Task 6 (lastRefreshTime), Task 8 (footer rendering)
- ✅ Edit feed name + URL dialog → Task 9 (FeedConfigRow `onEditRequest`), Task 10 (AlertDialog)
- ✅ Per-feed display mode text/image → Task 1 (FeedConfig.displayMode), Task 3 (ThumbnailHelper), Task 4 (imageUrl parsing + downloadThumbnails), Task 6 (worker calls downloadThumbnails), Task 7 (thumbnail rendering), Task 9 (TXT/IMG toggle)
- ✅ Global font size slider → Task 1 (WidgetConfig.fontSize), Task 7 (FeedItemRow uses fontSize), Task 8 (passes fontSize), Task 10 (Slider in config)
- ✅ Inline expand/collapse → Task 1 (ArticleItem.description), Task 2 (expandedArticleId key), Task 4 (description parsing + HTML strip), Task 5 (ToggleExpandCallback), Task 7 (FeedItemRow expanded state), Task 8 (passes expandedArticleId)
- ✅ Configurable external open → Task 1 (WidgetConfig.externalApp), Task 5 (OpenExternalCallback), Task 7 ("Open article" button), Task 10 (dropdown)
- ✅ DeepLinkActivity removal → Task 11

**Type consistency check:**
- `ToggleExpandCallback.ARTICLE_ID_KEY` defined in Task 5, used in Task 7 ✅
- `OpenExternalCallback.ARTICLE_URL_KEY / ARTICLE_ID_KEY / WIDGET_ID_KEY` defined in Task 5, used in Task 7 ✅
- `ThumbnailHelper.file(context, articleId)` defined in Task 3, used in Task 4, 6, 7 ✅
- `NewsFeedRepository.downloadThumbnails(articles, feeds)` defined in Task 4, called in Task 6 ✅
- `WidgetStateKey.lastRefreshTime` / `expandedArticleId` defined in Task 2, written in Task 6, read in Task 8 ✅
- `FeedConfig.displayMode`, `WidgetConfig.fontSize`, `WidgetConfig.externalApp`, `ArticleItem.description / imageUrl` all defined in Task 1, used throughout ✅
- `FeedConfigRow` now has 4 named params: `feedConfig`, `onUpdate`, `onRemove`, `onEditRequest` — all wired in Task 10 ✅

**No placeholders found.**
