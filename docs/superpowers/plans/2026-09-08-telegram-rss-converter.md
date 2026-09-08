# Telegram RSS Converter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users add a public Telegram channel as a feed source through the existing Add Feed field, converting its posts into `ArticleItem`s that flow through the app's normal storage/sort/render pipeline like any RSS feed.

**Architecture:** A Telegram channel is stored as an ordinary `FeedConfig` whose `feedUrl` is the canonical `https://t.me/s/<channel>` preview-page URL. `NewsFeedRepository`'s existing per-feed fetch dispatch grows one new branch that recognizes this URL shape and routes to a new, purely-parsing `TelegramFeedParser` object instead of the XML pull-parser path — network I/O stays centralized in `NewsFeedRepository` (reusing its existing OkHttp client/headers), while all the HTML-scraping logic lives in testable, Android-framework-free pure functions.

**Tech Stack:** Kotlin, OkHttp (already a dependency), plain-Kotlin regex parsing (no new HTML/DOM library), JUnit 4 for unit tests (new — this project currently has zero test infrastructure; see Task 1).

---

## Design note on testability (read before starting)

The approved spec (`docs/superpowers/specs/2026-09-08-telegram-rss-converter-design.md`) says HTML entity/tag cleanup should reuse the existing `Html.fromHtml()` call already used for RSS titles/descriptions. During planning this turned out to conflict with testability: `android.text.Html` requires the Android framework and isn't available in a plain JVM unit test without adding Robolectric — a much bigger dependency decision than this feature warrants, especially since this project has no test infrastructure at all today.

**Adjustment made during planning:** instead of `Html.fromHtml()`, Telegram's raw message text (which only ever contains a small, known set of inline tags — `<br>`, `<b>`, `<i>`, `<a>`) gets a small hand-written, pure-Kotlin tag-stripper + HTML-entity decoder (Task 3). This keeps the entire parser unit-testable with plain JUnit and adds no new runtime dependency. It also naturally handles the double-escaped numeric entities already confirmed present in real feeds this project ingests (e.g. `&amp;#128308;` → `🔴`, the same pattern fixed for RSS titles in commit `88ea007`), since the entity-decode step runs after tag-stripping either way.

**Also resolved during planning (not fully spelled out in the spec):** a photo-only Telegram post with no caption text would otherwise get a blank title, which the rest of the app treats as "skip this article" (matching RSS behavior for a blank `<title>`). Since the spec says photo-only posts should NOT be skipped (only messages with *neither* text nor image should be), Task 4 falls back to the feed's own display name as the title in that specific case, so the row still renders sensibly instead of silently vanishing.

Everything else in this plan matches the approved spec exactly.

---

## File Structure

- **Create:** `app/src/main/java/com/newsfeed/widget/data/TelegramFeedParser.kt` — all Telegram-specific parsing logic (URL detection, HTML scraping, entity decoding, `ArticleItem` construction). Zero network I/O, zero Android-framework dependency — fully unit-testable.
- **Create:** `app/src/test/java/com/newsfeed/widget/data/TelegramFeedParserTest.kt` — JUnit 4 tests for everything in the file above.
- **Modify:** `app/build.gradle.kts` — add the JUnit 4 test dependency (none exists yet).
- **Modify:** `app/src/main/java/com/newsfeed/widget/data/NewsFeedRepository.kt` — one new branch in `fetchFeedArticles()`, one new public function `fetchTelegramChannelTitle()`.
- **Modify:** `app/src/main/java/com/newsfeed/widget/config/WidgetConfigActivity.kt` — `doAddFeed()` gets Telegram detection before its existing URL normalization; Add Feed field placeholder text updated.

---

### Task 1: Test infrastructure + `canonicalize()`

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/newsfeed/widget/data/TelegramFeedParser.kt`
- Create: `app/src/test/java/com/newsfeed/widget/data/TelegramFeedParserTest.kt`

- [ ] **Step 1: Add the JUnit 4 test dependency**

Find the `dependencies { ... }` block in `app/build.gradle.kts` (it already has lines like `implementation("com.squareup.okhttp3:okhttp:...")`). Add this line inside that block, near any other `implementation(...)` lines:

```kotlin
    testImplementation("junit:junit:4.13.2")
```

- [ ] **Step 2: Create the test file with failing tests for `canonicalize()`**

Create `app/src/test/java/com/newsfeed/widget/data/TelegramFeedParserTest.kt`:

```kotlin
package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TelegramFeedParserTest {

    @Test
    fun `canonicalize recognizes a full https t dot me URL`() {
        assertEquals(
            "https://t.me/s/testchannel",
            TelegramFeedParser.canonicalize("https://t.me/testchannel"),
        )
    }

    @Test
    fun `canonicalize recognizes a bare t dot me URL without scheme`() {
        assertEquals(
            "https://t.me/s/testchannel",
            TelegramFeedParser.canonicalize("t.me/testchannel"),
        )
    }

    @Test
    fun `canonicalize recognizes telegram dot me`() {
        assertEquals(
            "https://t.me/s/testchannel",
            TelegramFeedParser.canonicalize("telegram.me/testchannel"),
        )
    }

    @Test
    fun `canonicalize recognizes a bare at-handle`() {
        assertEquals(
            "https://t.me/s/testchannel",
            TelegramFeedParser.canonicalize("@testchannel"),
        )
    }

    @Test
    fun `canonicalize strips a trailing slash from the channel name`() {
        assertEquals(
            "https://t.me/s/testchannel",
            TelegramFeedParser.canonicalize("https://t.me/testchannel/"),
        )
    }

    @Test
    fun `canonicalize returns null for a normal RSS URL`() {
        assertEquals(null, TelegramFeedParser.canonicalize("https://example.com/rss.xml"))
    }

    @Test
    fun `canonicalize returns null for blank input`() {
        assertEquals(null, TelegramFeedParser.canonicalize(""))
    }

    @Test
    fun `canonicalize returns null for a t dot me search or share link`() {
        // https://t.me/s/... is itself already a preview URL, not a channel reference to
        // re-canonicalize, and https://t.me/joinchat/... is an invite link, not a public
        // channel handle - neither should be treated as a valid channel reference here.
        assertEquals(null, TelegramFeedParser.canonicalize("https://t.me/joinchat/abc123"))
    }
}
```

- [ ] **Step 3: Run the tests and verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.newsfeed.widget.data.TelegramFeedParserTest"`
Expected: FAIL to compile — `TelegramFeedParser` doesn't exist yet.

- [ ] **Step 4: Create `TelegramFeedParser.kt` with `canonicalize()`**

Create `app/src/main/java/com/newsfeed/widget/data/TelegramFeedParser.kt`:

```kotlin
package com.newsfeed.widget.data

/**
 * Converts public Telegram channels into the same ArticleItem shape every RSS/Atom feed
 * produces, by scraping each channel's public, no-login HTML preview page
 * (https://t.me/s/<channel>) instead of parsing XML. See
 * docs/superpowers/specs/2026-09-08-telegram-rss-converter-design.md for the full design.
 *
 * Deliberately Android-framework-free (no android.text.Html, no network I/O) so every
 * function here is a plain, directly unit-testable Kotlin function. Network fetching stays
 * in NewsFeedRepository, which already owns a shared OkHttpClient and browser headers for
 * every other feed type.
 */
object TelegramFeedParser {

    private val CHANNEL_REF_REGEX = Regex(
        """^(?:https?://)?(?:t\.me|telegram\.me)/([A-Za-z0-9_]+)/?$"""
    )
    private val AT_HANDLE_REGEX = Regex("""^@([A-Za-z0-9_]+)$""")

    /**
     * Recognizes a Telegram channel reference typed or pasted into the Add Feed field and
     * returns the canonical https://t.me/s/<channel> fetch URL, or null if [raw] doesn't
     * look like a Telegram reference at all (the caller should fall through to normal
     * RSS-URL handling in that case).
     */
    fun canonicalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        AT_HANDLE_REGEX.find(trimmed)?.let { return "https://t.me/s/${it.groupValues[1]}" }
        CHANNEL_REF_REGEX.find(trimmed)?.let { return "https://t.me/s/${it.groupValues[1]}" }
        return null
    }
}
```

- [ ] **Step 5: Run the tests and verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.newsfeed.widget.data.TelegramFeedParserTest"`
Expected: PASS (8 tests, 0 failures)

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/newsfeed/widget/data/TelegramFeedParser.kt app/src/test/java/com/newsfeed/widget/data/TelegramFeedParserTest.kt
git commit -m "Add TelegramFeedParser.canonicalize() with unit tests

First piece of the Telegram RSS converter (see
docs/superpowers/specs/2026-09-08-telegram-rss-converter-design.md).
Also adds this project's first unit test infrastructure (JUnit 4) -
previously everything was verified on-device only."
```

---

### Task 2: `extractRawMessages()` — pure HTML scraping

**Files:**
- Modify: `app/src/main/java/com/newsfeed/widget/data/TelegramFeedParser.kt`
- Modify: `app/src/test/java/com/newsfeed/widget/data/TelegramFeedParserTest.kt`

- [ ] **Step 1: Add a realistic fixture and failing tests**

Append to `TelegramFeedParserTest.kt` (inside the `TelegramFeedParserTest` class, after the last `canonicalize` test):

```kotlin
    // Modeled on the real t.me/s/<channel> page structure: one <div class="tgme_widget_message"
    // data-post="channel/id"> block per post, a <time datetime="..."> for its timestamp, an
    // optional photo via a background-image style, and message text in its own div.
    private val sampleHtml = """
        <div class="tgme_widget_message_wrap">
        <div class="tgme_widget_message" data-post="testchannel/101" data-view="abc">
            <div class="tgme_widget_message_photo_wrap" style="background-image:url('https://cdn.example.com/photo101.jpg')"></div>
            <div class="tgme_widget_message_text js-message_text" dir="auto">First line of post 101<br/>Second line with more detail.</div>
            <time class="time" datetime="2026-09-07T10:15:00+00:00">10:15</time>
        </div>
        </div>
        <div class="tgme_widget_message_wrap">
        <div class="tgme_widget_message" data-post="testchannel/102" data-view="abc">
            <div class="tgme_widget_message_text js-message_text" dir="auto">Text-only post with no photo</div>
            <time class="time" datetime="2026-09-07T09:00:00+00:00">09:00</time>
        </div>
        </div>
        <div class="tgme_widget_message_wrap">
        <div class="tgme_widget_message" data-post="testchannel/103" data-view="abc">
            <div class="tgme_widget_message_photo_wrap" style="background-image:url('https://cdn.example.com/photo103.jpg')"></div>
            <time class="time" datetime="2026-09-07T08:30:00+00:00">08:30</time>
        </div>
        </div>
        <div class="tgme_widget_message_wrap">
        <div class="tgme_widget_message" data-post="testchannel/104" data-view="abc">
            <time class="time" datetime="2026-09-07T08:00:00+00:00">08:00</time>
        </div>
        </div>
    """.trimIndent()

    @Test
    fun `extractRawMessages skips only the message with neither text nor photo`() {
        val messages = extractRawMessages(sampleHtml)
        assertEquals(3, messages.size)
        assertEquals(false, messages.any { it.id.endsWith("/104") })
    }

    @Test
    fun `extractRawMessages captures permalink as both id and articleUrl`() {
        val messages = extractRawMessages(sampleHtml)
        assertEquals("https://t.me/testchannel/101", messages[0].id)
        assertEquals("https://t.me/testchannel/101", messages[0].articleUrl)
    }

    @Test
    fun `extractRawMessages captures photo url when present`() {
        val messages = extractRawMessages(sampleHtml)
        assertEquals("https://cdn.example.com/photo101.jpg", messages[0].imageUrl)
    }

    @Test
    fun `extractRawMessages leaves imageUrl blank when no photo`() {
        val messages = extractRawMessages(sampleHtml)
        assertEquals("https://t.me/testchannel/102", messages[1].id)
        assertEquals("", messages[1].imageUrl)
    }

    @Test
    fun `extractRawMessages captures photo-only post with blank rawText`() {
        val messages = extractRawMessages(sampleHtml)
        assertEquals("https://t.me/testchannel/103", messages[2].id)
        assertEquals("", messages[2].rawText)
        assertEquals("https://cdn.example.com/photo103.jpg", messages[2].imageUrl)
    }

    @Test
    fun `extractRawMessages captures raw text with inline tags intact`() {
        val messages = extractRawMessages(sampleHtml)
        assertEquals(true, messages[0].rawText.contains("<br/>"))
        assertEquals(true, messages[0].rawText.contains("First line of post 101"))
    }

    @Test
    fun `extractRawMessages parses ISO-8601 datetime to correct epoch millis`() {
        val messages = extractRawMessages(sampleHtml)
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.set(2026, java.util.Calendar.SEPTEMBER, 7, 10, 15, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        assertEquals(cal.timeInMillis, messages[0].publishedAt)
    }

    @Test
    fun `extractRawMessages returns empty list for html with no messages`() {
        assertEquals(0, extractRawMessages("<html><body>channel is private</body></html>").size)
    }
```

- [ ] **Step 2: Run and verify these fail**

Run: `./gradlew testDebugUnitTest --tests "com.newsfeed.widget.data.TelegramFeedParserTest"`
Expected: FAIL to compile — `extractRawMessages` and `TelegramRawMessage` don't exist yet.

- [ ] **Step 3: Implement `TelegramRawMessage` and `extractRawMessages()`**

Add to `TelegramFeedParser.kt`, inside the `object TelegramFeedParser { ... }` block, after `canonicalize()`:

```kotlin
    private val DATA_POST_REGEX = Regex("""data-post="([A-Za-z0-9_]+)/(\d+)"""")
    private val TIME_REGEX = Regex("""<time[^>]*\bdatetime="([^"]+)"""")
    private val TEXT_REGEX = Regex(
        """(?s)tgme_widget_message_text[^"]*"[^>]*>(.*?)</div>"""
    )
    private val PHOTO_REGEX = Regex(
        """tgme_widget_message_photo_wrap[^"]*"[^>]*style="[^"]*background-image:url\('([^']+)'\)"""
    )

    /**
     * One post's raw, not-yet-cleaned data as scraped directly out of the t.me/s/ page.
     * rawText still has Telegram's own inline HTML (<br>, <b>, <a>, ...) and HTML entities
     * in it - see parseArticles()/stripTelegramHtml() for the cleanup step.
     */
    internal data class TelegramRawMessage(
        val id: String,
        val articleUrl: String,
        val publishedAt: Long,
        val rawText: String,
        val imageUrl: String,
    )

    /**
     * Slices the page into one chunk per <div data-post="channel/id">...</div> block (from
     * each data-post match to the start of the next one, or end of string for the last) and
     * extracts fields independently within each chunk - a small purpose-built parser rather
     * than a full HTML/DOM library, since the page's structure is simple and regular.
     */
    internal fun extractRawMessages(html: String): List<TelegramRawMessage> {
        val posts = DATA_POST_REGEX.findAll(html).toList()
        val messages = mutableListOf<TelegramRawMessage>()
        for (i in posts.indices) {
            val match = posts[i]
            val channel = match.groupValues[1]
            val postId = match.groupValues[2]
            val chunkStart = match.range.first
            val chunkEnd = if (i + 1 < posts.size) posts[i + 1].range.first else html.length
            val chunk = html.substring(chunkStart, chunkEnd)

            val datetime = TIME_REGEX.find(chunk)?.groupValues?.get(1) ?: continue
            val publishedAt = parseIsoDate(datetime) ?: continue
            val rawText = TEXT_REGEX.find(chunk)?.groupValues?.get(1)?.trim() ?: ""
            val imageUrl = PHOTO_REGEX.find(chunk)?.groupValues?.get(1) ?: ""
            if (rawText.isBlank() && imageUrl.isBlank()) continue

            val permalink = "https://t.me/$channel/$postId"
            messages += TelegramRawMessage(
                id = permalink,
                articleUrl = permalink,
                publishedAt = publishedAt,
                rawText = rawText,
                imageUrl = imageUrl,
            )
        }
        return messages
    }

    private fun parseIsoDate(text: String): Long? = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).parse(text)?.time
    }.getOrNull()
```

- [ ] **Step 4: Run and verify all tests pass**

Run: `./gradlew testDebugUnitTest --tests "com.newsfeed.widget.data.TelegramFeedParserTest"`
Expected: PASS (15 tests, 0 failures)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/newsfeed/widget/data/TelegramFeedParser.kt app/src/test/java/com/newsfeed/widget/data/TelegramFeedParserTest.kt
git commit -m "Add TelegramFeedParser.extractRawMessages() with unit tests"
```

---

### Task 3: `stripTelegramHtml()` — pure tag/entity cleanup

**Files:**
- Modify: `app/src/main/java/com/newsfeed/widget/data/TelegramFeedParser.kt`
- Modify: `app/src/test/java/com/newsfeed/widget/data/TelegramFeedParserTest.kt`

- [ ] **Step 1: Add failing tests**

Append to `TelegramFeedParserTest.kt`:

```kotlin
    @Test
    fun `stripTelegramHtml converts br tags to newlines`() {
        assertEquals(
            "Line one\nLine two",
            stripTelegramHtml("Line one<br/>Line two"),
        )
    }

    @Test
    fun `stripTelegramHtml removes bold and italic tags but keeps their text`() {
        assertEquals(
            "Some bold and italic text",
            stripTelegramHtml("Some <b>bold</b> and <i>italic</i> text"),
        )
    }

    @Test
    fun `stripTelegramHtml removes link tags but keeps their text`() {
        assertEquals(
            "See this article",
            stripTelegramHtml("""See <a href="https://example.com">this article</a>"""),
        )
    }

    @Test
    fun `stripTelegramHtml decodes basic HTML entities`() {
        assertEquals(
            """Tom & Jerry said "hi" <ok>""",
            stripTelegramHtml("Tom &amp; Jerry said &quot;hi&quot; &lt;ok&gt;"),
        )
    }

    @Test
    fun `stripTelegramHtml decodes a numeric entity to the real emoji`() {
        assertEquals("🔴 breaking", stripTelegramHtml("&#128308; breaking"))
    }

    @Test
    fun `stripTelegramHtml decodes a double-escaped numeric entity`() {
        // Confirmed live on rotter.net's RSS feed (BUG-013, commit 88ea007): some sources
        // double-escape numeric entities. The tag-strip pass doesn't touch & at all, so
        // decodeHtmlEntities's own sequential &amp;-then-numeric replacement handles this
        // the same way already fixed for RSS titles.
        assertEquals("🔴🔴", stripTelegramHtml("&amp;#128308;&amp;#128308;"))
    }

    @Test
    fun `stripTelegramHtml trims surrounding whitespace`() {
        assertEquals("hello", stripTelegramHtml("  hello  \n"))
    }
```

- [ ] **Step 2: Run and verify these fail**

Run: `./gradlew testDebugUnitTest --tests "com.newsfeed.widget.data.TelegramFeedParserTest"`
Expected: FAIL to compile — `stripTelegramHtml` doesn't exist yet.

- [ ] **Step 3: Implement `stripTelegramHtml()`**

Add to `TelegramFeedParser.kt`, after `extractRawMessages()`:

```kotlin
    private val BR_TAG_REGEX = Regex("(?i)<br\\s*/?>")
    private val ANY_TAG_REGEX = Regex("<[^>]+>")
    private val NUMERIC_ENTITY_REGEX = Regex("&#(\\d+);")

    /**
     * Strips Telegram's own inline HTML (only ever <br>, <b>, <i>, <a> in practice) and
     * decodes HTML entities, without pulling in android.text.Html - see this file's header
     * doc and the plan's "Design note on testability" for why. Order matters: the &amp;
     * replacement runs before the numeric-entity regex, so a double-escaped entity like
     * "&amp;#128308;" correctly becomes "&#128308;" after the first pass and then the real
     * emoji after the second - the same double-escaping already confirmed in real feeds
     * (BUG-013).
     */
    internal fun stripTelegramHtml(rawText: String): String {
        val withoutTags = rawText
            .replace(BR_TAG_REGEX, "\n")
            .replace(ANY_TAG_REGEX, "")
        var decoded = withoutTags
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
        decoded = NUMERIC_ENTITY_REGEX.replace(decoded) { match ->
            match.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: match.value
        }
        return decoded.trim()
    }
```

- [ ] **Step 4: Run and verify all tests pass**

Run: `./gradlew testDebugUnitTest --tests "com.newsfeed.widget.data.TelegramFeedParserTest"`
Expected: PASS (22 tests, 0 failures)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/newsfeed/widget/data/TelegramFeedParser.kt app/src/test/java/com/newsfeed/widget/data/TelegramFeedParserTest.kt
git commit -m "Add TelegramFeedParser.stripTelegramHtml() with unit tests"
```

---

### Task 4: `parseArticles()` and `extractChannelTitle()` — tie it together

**Files:**
- Modify: `app/src/main/java/com/newsfeed/widget/data/TelegramFeedParser.kt`
- Modify: `app/src/test/java/com/newsfeed/widget/data/TelegramFeedParserTest.kt`

- [ ] **Step 1: Add failing tests**

Append to `TelegramFeedParserTest.kt`:

```kotlin
    @Test
    fun `parseArticles splits first line as title and rest as description`() {
        val articles = TelegramFeedParser.parseArticles(
            feedId = "https://t.me/s/testchannel",
            feedDisplayName = "Test Channel",
            html = sampleHtml,
            maxItems = 10,
        )
        assertEquals("First line of post 101", articles[0].title)
        assertEquals("Second line with more detail.", articles[0].description)
    }

    @Test
    fun `parseArticles carries feedId, feedName, articleUrl, imageUrl, publishedAt through`() {
        val articles = TelegramFeedParser.parseArticles(
            feedId = "https://t.me/s/testchannel",
            feedDisplayName = "Test Channel",
            html = sampleHtml,
            maxItems = 10,
        )
        assertEquals("https://t.me/s/testchannel", articles[0].feedId)
        assertEquals("Test Channel", articles[0].feedName)
        assertEquals("https://t.me/testchannel/101", articles[0].articleUrl)
        assertEquals("https://cdn.example.com/photo101.jpg", articles[0].imageUrl)
        assertEquals(false, articles[0].isRead)
    }

    @Test
    fun `parseArticles falls back to the channel name as title for a photo-only post`() {
        val articles = TelegramFeedParser.parseArticles(
            feedId = "https://t.me/s/testchannel",
            feedDisplayName = "Test Channel",
            html = sampleHtml,
            maxItems = 10,
        )
        val photoOnly = articles.first { it.articleUrl.endsWith("/103") }
        assertEquals("Test Channel", photoOnly.title)
        assertEquals("", photoOnly.description)
    }

    @Test
    fun `parseArticles respects maxItems`() {
        val articles = TelegramFeedParser.parseArticles(
            feedId = "https://t.me/s/testchannel",
            feedDisplayName = "Test Channel",
            html = sampleHtml,
            maxItems = 2,
        )
        assertEquals(2, articles.size)
    }

    @Test
    fun `parseArticles returns empty list for a page with no messages`() {
        val articles = TelegramFeedParser.parseArticles(
            feedId = "https://t.me/s/testchannel",
            feedDisplayName = "Test Channel",
            html = "<html><body>channel is private</body></html>",
            maxItems = 10,
        )
        assertEquals(0, articles.size)
    }

    @Test
    fun `extractChannelTitle finds the channel display name`() {
        val html = """
            <div class="tgme_channel_info_header_title">
                <span dir="auto">Test Channel Display Name</span>
            </div>
        """.trimIndent()
        assertEquals("Test Channel Display Name", TelegramFeedParser.extractChannelTitle(html))
    }

    @Test
    fun `extractChannelTitle returns null when the page has no channel header`() {
        assertEquals(
            null,
            TelegramFeedParser.extractChannelTitle("<html><body>channel is private</body></html>"),
        )
    }
```

- [ ] **Step 2: Run and verify these fail**

Run: `./gradlew testDebugUnitTest --tests "com.newsfeed.widget.data.TelegramFeedParserTest"`
Expected: FAIL to compile — `parseArticles` and `extractChannelTitle` don't exist yet.

- [ ] **Step 3: Implement `parseArticles()` and `extractChannelTitle()`**

Add to `TelegramFeedParser.kt`, after `stripTelegramHtml()`:

```kotlin
    private val CHANNEL_TITLE_REGEX = Regex(
        """(?s)tgme_channel_info_header_title[^>]*>\s*<span[^>]*>([^<]+)</span>"""
    )

    /**
     * Turns a fetched t.me/s/<channel> page into the same ArticleItem shape every other
     * feed type produces. A post's message text's first line becomes the title, the rest
     * becomes the description; a photo-only post with no caption text falls back to
     * [feedDisplayName] as its title instead of being silently dropped (an empty title
     * would otherwise be treated the same as a blank RSS <title> and skipped downstream -
     * see this plan's "Design note on testability" for why that's wrong for Telegram).
     */
    fun parseArticles(
        feedId: String,
        feedDisplayName: String,
        html: String,
        maxItems: Int,
    ): List<ArticleItem> {
        return extractRawMessages(html).take(maxItems).map { raw ->
            val cleanText = stripTelegramHtml(raw.rawText)
            val lines = cleanText.lines().map { it.trim() }.filter { it.isNotBlank() }
            val title = lines.firstOrNull() ?: feedDisplayName
            val description = lines.drop(1).joinToString("\n").take(2000)
            ArticleItem(
                id = raw.id,
                feedId = feedId,
                feedName = feedDisplayName,
                title = title.take(200),
                articleUrl = raw.articleUrl,
                description = description,
                imageUrl = raw.imageUrl,
                publishedAt = raw.publishedAt,
                isRead = false,
            )
        }
    }

    /** The channel's own display name, shown as its header on the t.me/s/ page itself. */
    fun extractChannelTitle(html: String): String? =
        CHANNEL_TITLE_REGEX.find(html)?.groupValues?.get(1)?.let { stripTelegramHtml(it) }
```

- [ ] **Step 4: Run and verify all tests pass**

Run: `./gradlew testDebugUnitTest --tests "com.newsfeed.widget.data.TelegramFeedParserTest"`
Expected: PASS (29 tests, 0 failures)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/newsfeed/widget/data/TelegramFeedParser.kt app/src/test/java/com/newsfeed/widget/data/TelegramFeedParserTest.kt
git commit -m "Add TelegramFeedParser.parseArticles() and extractChannelTitle() with unit tests

TelegramFeedParser is now feature-complete and fully unit-tested.
Remaining tasks wire it into the existing fetch/add-feed flows -
those parts touch real network I/O and UI, verified on-device like
the rest of this app, matching its existing testing convention."
```

---

### Task 5: Wire into `NewsFeedRepository`'s fetch dispatch

**Files:**
- Modify: `app/src/main/java/com/newsfeed/widget/data/NewsFeedRepository.kt:197-208` (the `fetchFeedArticles` function)
- Modify: `app/src/main/java/com/newsfeed/widget/data/NewsFeedRepository.kt:67-80` (near `fetchFeedTitle`, to add the new `fetchTelegramChannelTitle`)

No new unit tests here — this function does real network I/O (an OkHttp call against a live URL), which this codebase has never unit-tested for any other feed type either (see `fetchFeedArticles`/`fetchFeedTitle`'s own existing lack of tests). Verified on-device in Task 7.

- [ ] **Step 1: Add the Telegram branch to `fetchFeedArticles`**

In `app/src/main/java/com/newsfeed/widget/data/NewsFeedRepository.kt`, find:

```kotlin
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
```

Replace it with:

```kotlin
    private fun fetchFeedArticles(feed: FeedConfig, maxItems: Int = MAX_ITEMS_PER_FETCH): List<ArticleItem> {
        var req = Request.Builder().url(feed.feedUrl)
        browserHeaders(feed.feedUrl).forEach { (k, v) -> req = req.header(k, v) }
        return client.newCall(req.build()).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            // Use string() so OkHttp honours the charset in Content-Type (fixes Windows-1255 Hebrew feeds)
            val text = response.body?.string() ?: return emptyList()
            if (feed.feedUrl.startsWith("https://t.me/s/")) {
                return TelegramFeedParser.parseArticles(feed.feedId, feed.displayName, text, maxItems)
            }
            val parser = Xml.newPullParser()
            parser.setInput(text.reader())
            parseFeed(parser, feed, maxItems)
        }
    }
```

- [ ] **Step 2: Add `fetchTelegramChannelTitle()` next to `fetchFeedTitle()`**

In the same file, find:

```kotlin
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
```

Add this new function directly after it:

```kotlin
    // Mirrors fetchFeedTitle()'s role but for a Telegram channel's own display name,
    // instead of an RSS <title> - used when a channel is first added in Settings.
    suspend fun fetchTelegramChannelTitle(canonicalUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            var req = Request.Builder().url(canonicalUrl)
            browserHeaders(canonicalUrl).forEach { (k, v) -> req = req.header(k, v) }
            client.newCall(req.build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val text = response.body?.string() ?: return@withContext null
                TelegramFeedParser.extractChannelTitle(text)
            }
        } catch (_: Exception) { null }
    }
```

- [ ] **Step 3: Verify the project still compiles**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/newsfeed/widget/data/NewsFeedRepository.kt
git commit -m "Route Telegram-sourced feeds through TelegramFeedParser in the fetch pipeline"
```

---

### Task 6: Wire into Add Feed UI

**Files:**
- Modify: `app/src/main/java/com/newsfeed/widget/config/WidgetConfigActivity.kt:303-314` (the `doAddFeed` function)
- Modify: `app/src/main/java/com/newsfeed/widget/config/WidgetConfigActivity.kt:756` (placeholder text)

- [ ] **Step 1: Update `doAddFeed()` to detect Telegram references first**

In `app/src/main/java/com/newsfeed/widget/config/WidgetConfigActivity.kt`, find:

```kotlin
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
```

Replace it with:

```kotlin
                fun doAddFeed() {
                    val raw = addFeedUrl.trim(); if (raw.isBlank()) return
                    // Recognized Telegram references (t.me/channel, @channel, ...) are
                    // canonicalized to their https://t.me/s/<channel> preview URL BEFORE the
                    // generic https-prefix fallback below, which would otherwise mangle a
                    // bare "@channel" into "https://@channel" or misread "t.me/channel" as
                    // a literal (wrong) RSS-fetch target.
                    val telegramUrl = TelegramFeedParser.canonicalize(raw)
                    val url = telegramUrl ?: if (raw.startsWith("http")) raw else "https://$raw"
                    scope.launch {
                        isAddingFeed = true; addFeedError = null; statusMessage = ""
                        val title = if (telegramUrl != null) repo.fetchTelegramChannelTitle(url) else repo.fetchFeedTitle(url)
                        if (title != null) {
                            val newFeed = FeedConfig(feedId = url, displayName = title, feedUrl = url,
                                accentColor = feedAccentColors(config.widgetTheme)[config.feeds.size % feedAccentColors(config.widgetTheme).size])
                            config = config.copy(feeds = config.feeds + newFeed)
                            feedOrder.add(url); addFeedUrl = ""
                        } else { addFeedError = "Could not load feed — check the URL" }
                        isAddingFeed = false
                    }
```

- [ ] **Step 2: Update the Add Feed field's placeholder text**

In the same file, find:

```kotlin
                                        label = { Text("RSS or Atom feed URL") },
```

Replace it with:

```kotlin
                                        label = { Text("RSS, Telegram or Atom feed URL") },
```

- [ ] **Step 3: Verify the project still compiles**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/newsfeed/widget/config/WidgetConfigActivity.kt
git commit -m "Detect Telegram channel references in the Add Feed field"
```

---

### Task 7: On-device verification and cleanup

**Files:** none (verification only)

- [ ] **Step 1: Run the full unit test suite one more time**

Run: `./gradlew testDebugUnitTest`
Expected: PASS, all `TelegramFeedParserTest` tests green, no other test regressions (there are no other tests in this project yet).

- [ ] **Step 2: Build and install a debug APK**

Run: `./gradlew assembleDebug` then `adb install -r app/build/outputs/apk/debug/app-debug.apk` (or push to `main` and install the CI-built release, matching this project's established verification workflow for every other change this session).

- [ ] **Step 3: Add a real public Telegram channel via the Add Feed field**

Type a known-public channel reference (e.g. `@some_real_public_channel` or `t.me/some_real_public_channel`) into the Add Feed field and tap Add. Confirm:
- The channel's real display name appears (not the raw handle).
- After Save, articles appear on the widget with sensible headlines, timestamps, and images (where the channel posts them).
- If the channel is Hebrew-language, its RTL/LTR per-feed setting behaves the same as any RSS feed (no different treatment expected or desired).

- [ ] **Step 4: Confirm dedup works across a refresh**

Trigger a manual refresh (the widget's own refresh control) a few minutes after the initial add. Confirm no duplicate articles appear for posts already fetched (the permalink-derived `id` should dedup correctly, same mechanism as every other feed type).

- [ ] **Step 5: Confirm the error path for an invalid/private channel**

Type a nonexistent or private channel handle into the Add Feed field. Confirm the existing "Could not load feed — check the URL" error appears, same as an unreachable RSS URL.

- [ ] **Step 6: Update `docs/BUGS.md` or a features log if this project tracks shipped features there**

Check whether `docs/BUGS.md`'s "Feature additions" section (used for the 2026-09-07 batch) is the right place to note this, and add a short entry following that existing pattern if so.

- [ ] **Step 7: Final push**

```bash
git push origin main
```

Per this project's established workflow this session: run a security-focused review of the full diff before this push (per the standing pre-push security check) — in particular, confirm the new network fetch (`fetchTelegramChannelTitle`, the Telegram branch in `fetchFeedArticles`) only ever targets `https://t.me/s/...` URLs derived from `canonicalize()`'s own regex output (never a raw, unvalidated user string passed straight to the network layer), and that `extractChannelTitle`/`parseArticles` only ever read from the HTTP response body, never execute or evaluate anything from it.

---

## Self-Review Notes

- **Spec coverage:** every section of the approved spec has a corresponding task — Architecture (Task 5), Fetching & Parsing (Tasks 2-4), Add-Feed integration (Task 6), Error handling (verified in Task 7 Step 5, no new code needed since it reuses the existing error path), Scope/YAGNI on pagination (no task needed — this is a documented non-implementation), UI copy (Task 6 Step 2).
- **Two decisions were made during planning that go slightly beyond the spec's literal text** (the `Html.fromHtml()` → hand-written stripper swap, and the photo-only-post title fallback) — both are called out explicitly in the "Design note on testability" section above rather than silently diverging from what was approved.
- **Type/name consistency checked:** `TelegramFeedParser.canonicalize()`, `.parseArticles()`, `.extractChannelTitle()`, and the internal `.extractRawMessages()`/`.stripTelegramHtml()`/`TelegramRawMessage` are named and used identically across Tasks 1-6 wherever referenced.
