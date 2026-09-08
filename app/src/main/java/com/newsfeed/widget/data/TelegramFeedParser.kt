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

    // Requires exactly one path segment of channel-name characters - this deliberately
    // excludes t.me/s/<channel> (already a canonical preview URL, not something to
    // re-canonicalize) and invite links like t.me/joinchat/<id> or t.me/+<id> (not a public
    // channel handle).
    private val CHANNEL_REF_REGEX = Regex(
        """^(?:https?://)?(?:t\.me|telegram\.me)/([A-Za-z0-9_]+)/?$""",
        RegexOption.IGNORE_CASE
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
        AT_HANDLE_REGEX.matchEntire(trimmed)?.let { return previewUrl(it.groupValues[1]) }
        CHANNEL_REF_REGEX.matchEntire(trimmed)?.let { return previewUrl(it.groupValues[1]) }
        return null
    }

    private fun previewUrl(channel: String) = "https://t.me/s/$channel"

    private val DATA_POST_REGEX = Regex("""data-post="([A-Za-z0-9_]+)/(\d+)"""")
    private val TIME_REGEX = Regex("""<time[^>]*\bdatetime="([^"]+)"""")
    private val TEXT_OPEN_TAG_REGEX = Regex(
        """tgme_widget_message_text[^"]*"[^>]*>"""
    )
    private val PHOTO_REGEX = Regex(
        """tgme_widget_message_photo_wrap[^"]*"[^>]*style="[^"]*background-image:url\('([^']+)'\)"""
    )

    /**
     * One post's raw, not-yet-cleaned data as scraped directly out of the t.me/s/ page.
     * rawText still has Telegram's own inline HTML (<br>, <b>, <a>, ...) and HTML entities
     * in it - see parseArticles()/stripTelegramHtml() (added in a later task) for cleanup.
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
            val rawText = TEXT_OPEN_TAG_REGEX.find(chunk)?.let { match ->
                extractBalancedDivContent(chunk, match.range.last + 1)
            }?.trim() ?: ""
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

    /**
     * Returns the content between [contentStart] and the </div> that closes the div whose
     * opening tag ends at [contentStart] - counting nested <div>...</div> pairs so a nested
     * div inside the content (Telegram's real pages nest divs for quote/reply-preview
     * framing) doesn't cause an early, wrong close. A plain non-nesting-aware regex would
     * silently truncate the text at the first inner </div> instead of the real one.
     */
    private fun extractBalancedDivContent(html: String, contentStart: Int): String {
        var depth = 1
        var i = contentStart
        while (i < html.length) {
            val nextOpen = html.indexOf("<div", i)
            val nextClose = html.indexOf("</div>", i)
            if (nextClose == -1) return html.substring(contentStart)
            if (nextOpen != -1 && nextOpen < nextClose) {
                depth++
                i = nextOpen + 4
            } else {
                depth--
                if (depth == 0) return html.substring(contentStart, nextClose)
                i = nextClose + 6
            }
        }
        return html.substring(contentStart)
    }

    private fun parseIsoDate(text: String): Long? = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).parse(text)?.time
    }.getOrNull()

    private val BR_TAG_REGEX = Regex("(?i)<br\\s*/?>")
    private val ANY_TAG_REGEX = Regex("<[^>]+>")
    private val NUMERIC_ENTITY_REGEX = Regex("&#(\\d+);")

    /**
     * Strips Telegram's own inline HTML (only ever <br>, <b>, <i>, <a> in practice) and
     * decodes HTML entities, without pulling in android.text.Html - this keeps the whole
     * parser Android-framework-free and unit-testable in a plain JVM test. Order matters:
     * the &amp; replacement runs before the numeric-entity regex, so a double-escaped
     * entity like "&amp;#128308;" correctly becomes "&#128308;" after the first pass and
     * then the real emoji after the second - the same double-escaping already confirmed in
     * real feeds (BUG-013, commit 88ea007).
     */
    fun stripTelegramHtml(rawText: String): String {
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
}
