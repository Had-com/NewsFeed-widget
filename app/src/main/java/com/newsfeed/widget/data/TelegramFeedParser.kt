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

    // https://t.me/<channel>/<id> (optionally /s/<channel>/<id>), trailing slash / query ok.
    private val POST_URL_REGEX = Regex(
        """^(?:https?://)?(?:t\.me|telegram\.me)/(?:s/)?[A-Za-z0-9_]+/\d+/?(?:[?#].*)?$""",
        RegexOption.IGNORE_CASE
    )
    private val TELEGRAM_HOST_REGEX = Regex(
        """^(?:https?://)?(?:t\.me|telegram\.me)(?:[/?#]|$)""",
        RegexOption.IGNORE_CASE
    )

    /** True for a single-post permalink. Its page is a JS shell with no post text. */
    fun isTelegramPostUrl(url: String): Boolean = POST_URL_REGEX.matches(url.trim())

    /** True for any t.me / telegram.me URL. */
    fun isTelegramUrl(url: String): Boolean = TELEGRAM_HOST_REGEX.containsMatchIn(url.trim())

    // Telegram's own maximum message length.
    private const val MAX_POST_CHARS = 4096

    // Display cap for a headline; text beyond it moves to the description (nothing is dropped).
    private const val MAX_TITLE_CHARS = 200

    /**
     * Cuts [joined] (longer than [MAX_TITLE_CHARS]) at the cap on a word boundary, appending
     * "..." to the head (the head incl. the ellipsis is <= the cap); the rest is the overflow.
     * An unbroken run with no space in range is hard-cut, never inside a surrogate pair.
     */
    private fun splitTitleOverflow(joined: String): Pair<String, String> {
        val window = joined.take(MAX_TITLE_CHARS - 1)
        var cut = window.lastIndexOf(' ')
        if (cut <= 0) {
            cut = window.length
            if (cut > 0 && Character.isHighSurrogate(window[cut - 1])) cut--
        }
        return (joined.substring(0, cut).trimEnd() + "…") to joined.substring(cut).trim()
    }

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
    // Decimal (&#8207;) or hexadecimal (&#x200F;, x case-insensitive) numeric character reference.
    private val NUMERIC_ENTITY_REGEX = Regex("&#(?:[xX]([0-9a-fA-F]+)|(\\d+));")
    // Invisible direction MARKS: LRM U+200E, RLM U+200F, ALM U+061C. They carry no text and only
    // steer the bidi algorithm, so they are removed from titles and descriptions altogether (QA 14.ah
    // BUG-3: an N12chat title showed the literal "&rlm;"). Embeddings / isolates and the zero width
    // joiner (emoji sequences) are deliberately kept.
    private val DIRECTION_MARKS_REGEX = Regex("[\\u200E\\u200F\\u061C]")

    // Only matches a well-formed <a href="...">...</a> with a double-quoted href and a real
    // closing tag. DOT_MATCHES_ALL so a <br> or another tag inside the link text doesn't stop
    // the match early. Deliberately narrow: a malformed anchor (no href, single-quoted href,
    // or a missing closing tag) simply doesn't match and falls through to ANY_TAG_REGEX below,
    // which strips just the unrecognized tag and leaves the surrounding text untouched - never
    // throws, never corrupts the rest of the message (BUG-023).
    private val ANCHOR_REGEX = Regex(
        """<a\s+href="([^"]*)"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /**
     * Rewrites every well-formed `<a href="URL">label</a>` into `label (URL)` (BUG-023: the
     * old behaviour threw the href away entirely, keeping only the visible label text, so a
     * Telegram post's "קישור לכתבה" (link to the article) link became unreachable). Two
     * special cases avoid a useless or ugly result:
     * - label text identical to the URL (Telegram auto-links a bare URL) -> the URL once, not
     *   "https://x (https://x)".
     * - empty/whitespace-only label (an anchor with no visible text) -> the bare URL alone.
     * An anchor with a blank href="" is left completely alone (no "label ()").
     *
     * Runs BEFORE the generic tag-strip pass so the label is rewritten using its raw,
     * still-tagged inner HTML (kept verbatim in the output) - any inner formatting tag (e.g.
     * `<b>`) or nested `<br>` in the label is then cleaned up by the normal br/tag-strip pass
     * that already runs over the whole result afterward, instead of needing its own handling
     * here.
     */
    private fun rewriteAnchors(text: String): String = ANCHOR_REGEX.replace(text) { match ->
        val href = match.groupValues[1].trim()
        val labelRaw = match.groupValues[2]
        if (href.isEmpty()) return@replace match.value
        val labelVisible = labelRaw.replace(BR_TAG_REGEX, " ").replace(ANY_TAG_REGEX, "").trim()
        when {
            labelVisible.isEmpty() -> href
            labelVisible == href -> href
            isChannelSelfLink(labelVisible, href) -> labelRaw
            else -> "$labelRaw ($href)"
        }
    }

    private val HANDLE_LABEL_REGEX = Regex("""^@([A-Za-z0-9_]+)$""")
    private val CHANNEL_HOME_URL_REGEX = Regex(
        """^https?://(?:t\.me|telegram\.me)/([A-Za-z0-9_]+)/?$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * True for a channel linking to itself: label `@N12chat` with href `https://t.me/N12chat`
     * (same channel, case-insensitive). That signature carries no information, so only the
     * handle is kept (BUG-025). A different channel, or a link to a specific post
     * (t.me/N12chat/123), is a real link and keeps its URL.
     */
    private fun isChannelSelfLink(labelVisible: String, href: String): Boolean {
        val handle = HANDLE_LABEL_REGEX.matchEntire(labelVisible)?.groupValues?.get(1) ?: return false
        val channel = CHANNEL_HOME_URL_REGEX.matchEntire(href)?.groupValues?.get(1) ?: return false
        return handle.equals(channel, ignoreCase = true)
    }

    // "label (URL)" as produced by rewriteAnchors. The URL is matched lazily up to a ")" that
    // is followed by whitespace, sentence punctuation, end of text or a non-ASCII character (so
    // a Hebrew letter right after the paren still ends it), which lets a URL that itself
    // contains parentheses (wikipedia ..._(x)) survive intact.
    private val PAREN_URL_REGEX = Regex(
        """[ \t]*\(((?:https?|tg)://\S+?)\)(?=[\s.,;:!?]|$|[^\x00-\x7F])""",
        RegexOption.IGNORE_CASE
    )
    private val BARE_URL_REGEX = Regex("""(?:https?|tg)://\S+""", RegexOption.IGNORE_CASE)
    private val MULTI_SPACE_REGEX = Regex("""[ \t]{2,}""")

    /**
     * Removes every `(URL)` and bare URL from one line of text and returns the cleaned line
     * (label text kept, spacing tidied) together with the removed URLs in order of appearance.
     * A bare URL's trailing sentence punctuation (and an unbalanced ")") is not part of the URL
     * and stays in the text.
     */
    private fun pullOutUrls(line: String): Pair<String, List<String>> {
        val urls = mutableListOf<String>()
        var out = PAREN_URL_REGEX.replace(line) { m -> urls += m.groupValues[1]; "" }
        out = BARE_URL_REGEX.replace(out) { m ->
            var url = m.value
            var tail = ""
            while (url.isNotEmpty()) {
                val last = url.last()
                val strip = last in ".,;:!?" ||
                    (last == ')' && url.count { it == ')' } > url.count { it == '(' })
                if (!strip) break
                tail = last + tail
                url = url.dropLast(1)
            }
            urls += url
            tail
        }
        return out.replace(MULTI_SPACE_REGEX, " ").trim() to urls
    }

    /**
     * Strips Telegram's own inline HTML (only ever <br>, <b>, <i>, <a> in practice) and
     * decodes HTML entities, without pulling in android.text.Html - this keeps the whole
     * parser Android-framework-free and unit-testable in a plain JVM test. A link
     * (`<a href="URL">label</a>`) is rewritten to `label (URL)` first (see [rewriteAnchors])
     * so the URL - otherwise silently discarded - survives into the plain-text result.
     */
    fun stripTelegramHtml(rawText: String): String {
        val withoutTags = rewriteAnchors(rawText)
            .replace(BR_TAG_REGEX, "\n")
            .replace(ANY_TAG_REGEX, "")
        // &amp; decodes LAST among the named entities (right before the numeric pass),
        // not first - decoding it first would wrongly over-decode a correctly-escaped
        // "&amp;lt;" (meaning: display the literal text "&lt;") all the way to "<", since
        // the resulting "&lt;" would then match the &lt; replacement below. Only numeric
        // entities are known to need double-escape handling in practice (BUG-013) - named
        // entities like &lt;/&gt;/&quot; have no such real-world case here, so they should
        // only ever decode once.
        var decoded = withoutTags
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            // the named direction marks go before &amp; like every named entity, so "&amp;rlm;" stays literal
            .replace("&rlm;", "")
            .replace("&lrm;", "")
            .replace("&amp;", "&")
        decoded = NUMERIC_ENTITY_REGEX.replace(decoded) { match ->
            val hex = match.groupValues[1]
            (if (hex.isNotEmpty()) hex.toIntOrNull(16) else match.groupValues[2].toIntOrNull())
                ?.takeIf { it in 0..0x10FFFF }
                ?.let { String(Character.toChars(it)) }
                ?: match.value
        }
        // Also drops the characters themselves (a numeric reference decoded just above, or one already in the text).
        return DIRECTION_MARKS_REGEX.replace(decoded, "").trim()
    }

    // [\s\S]*? (not \s*) because the header div can carry a verified/scam/fake badge
    // <span> before the title's own <span> - the lazy skip plus find()'s backtracking lets
    // an empty badge span (no [^<]+ text) be passed over to reach the real title span.
    private val CHANNEL_TITLE_REGEX = Regex(
        """tgme_channel_info_header_title[^>]*>[\s\S]*?<span[^>]*>([^<]+)</span>"""
    )

    /**
     * Turns a fetched t.me/s/<channel> page into the same ArticleItem shape every other
     * feed type produces. A post's message text's first two lines become the title, the
     * rest (only) becomes the description - title text over the display cap spills to the start of
     * the description so nothing is lost or duplicated; a photo-only post with no caption text falls back to
     * [feedDisplayName] as its title instead of being silently dropped (an empty title
     * would otherwise be treated the same as a blank RSS <title> and skipped downstream).
     * URLs in the title lines never stay in the title: the label stays, the URL moves to the end
     * of the description (BUG-025, see below).
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
            // Live bug report: with only the first line as the title, the collapsed widget
            // row looked too thin for typical multi-line Telegram posts, since the rest of
            // the message stayed hidden until the row was expanded. Taking the first TWO
            // lines gives the collapsed row a fuller headline. Joined with a space (not a
            // newline) since the title is rendered through a Text/TextBitmapHelper call
            // that already wraps long text on its own - a literal "\n" here would force an
            // extra hard break instead of a natural wrap.
            val titleLineCount = minOf(lines.size, 2)
            // BUG-025: a URL in the title (e.g. N12_News' trailing "קישור לכתבה (https://...)")
            // is a long unbroken word that made the whole headline bitmap auto-shrink to a
            // tiny size. Keep URLs OUT of the title lines: the label text stays, the URL moves
            // to the end of the description (one per line, de-duplicated, order kept) where it
            // renders in the body and does not shrink anything. URLs further down the post
            // (description lines) stay inline where the author put them.
            val titleUrls = mutableListOf<String>()
            val titleLines = lines.take(titleLineCount).mapNotNull { line ->
                val (clean, urls) = pullOutUrls(line)
                titleUrls += urls
                clean.ifBlank { null }
            }
            val joined = titleLines.joinToString(" ")
            val remainder = lines.drop(titleLineCount).joinToString("\n")
            // The description is ONLY what the title does not show (no repeated headline):
            // the rest of the post, preceded by any title text that overflowed the display cap,
            // so title + description is the whole post exactly once. See splitTitleOverflow().
            val (title, bodyText) = when {
                // Nothing left of the title lines but URLs (a post that is just a link) gets the
                // same fallback as a photo-only post - the channel name - so the row is not
                // dropped for an empty title; the URL(s) become the description below.
                joined.isEmpty() -> feedDisplayName to remainder
                joined.length <= MAX_TITLE_CHARS -> joined to remainder
                else -> {
                    val (head, overflow) = splitTitleOverflow(joined)
                    head to (if (remainder.isEmpty()) overflow else overflow + "\n" + remainder)
                }
            }
            val description = appendMovedUrls(bodyText, titleUrls)
            ArticleItem(
                id = raw.id,
                feedId = feedId,
                feedName = feedDisplayName,
                title = title,
                articleUrl = raw.articleUrl,
                description = description,
                imageUrl = raw.imageUrl,
                publishedAt = raw.publishedAt,
                isRead = false,
            )
        }
    }

    /**
     * [body] followed by [urls] (de-duplicated, order kept, skipping any URL [body] already
     * contains), one per line, capped at [MAX_POST_CHARS]. The cap trims the body, never the
     * moved URLs, so a link is not lost on a post that already fills the description.
     */
    private fun appendMovedUrls(body: String, urls: List<String>): String {
        val extra = urls.distinct().filter { !body.contains(it) }
        if (extra.isEmpty()) return body.take(MAX_POST_CHARS)
        val block = extra.joinToString("\n")
        if (body.isEmpty()) return block.take(MAX_POST_CHARS)
        val room = (MAX_POST_CHARS - block.length - 1).coerceAtLeast(0)
        return (body.take(room).trimEnd() + "\n" + block).take(MAX_POST_CHARS)
    }

    /** The channel's own display name, shown as its header on the t.me/s/ page itself. */
    fun extractChannelTitle(html: String): String? =
        CHANNEL_TITLE_REGEX.find(html)?.groupValues?.get(1)?.let { stripTelegramHtml(it) }
}
