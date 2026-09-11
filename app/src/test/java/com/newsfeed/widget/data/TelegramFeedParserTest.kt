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
    fun `canonicalize recognizes an uppercase T dot me domain`() {
        assertEquals(
            "https://t.me/s/testchannel",
            TelegramFeedParser.canonicalize("https://T.me/testchannel"),
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
    fun `canonicalize returns null for an already-canonical preview URL`() {
        // t.me/s/<channel> is already the canonical preview URL this parser produces, not
        // a channel reference a user would type in - not something to re-canonicalize.
        assertEquals(null, TelegramFeedParser.canonicalize("https://t.me/s/testchannel"))
    }

    @Test
    fun `canonicalize returns null for a joinchat invite link`() {
        // An invite link, not a public channel handle.
        assertEquals(null, TelegramFeedParser.canonicalize("https://t.me/joinchat/abc123"))
    }

    @Test
    fun `canonicalize returns null for a plus-style invite link`() {
        assertEquals(null, TelegramFeedParser.canonicalize("https://t.me/+abc123"))
    }

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
        val messages = TelegramFeedParser.extractRawMessages(sampleHtml)
        assertEquals(3, messages.size)
        assertEquals(false, messages.any { it.id.endsWith("/104") })
    }

    @Test
    fun `extractRawMessages captures permalink as both id and articleUrl`() {
        val messages = TelegramFeedParser.extractRawMessages(sampleHtml)
        assertEquals("https://t.me/testchannel/101", messages[0].id)
        assertEquals("https://t.me/testchannel/101", messages[0].articleUrl)
    }

    @Test
    fun `extractRawMessages captures photo url when present`() {
        val messages = TelegramFeedParser.extractRawMessages(sampleHtml)
        assertEquals("https://cdn.example.com/photo101.jpg", messages[0].imageUrl)
    }

    @Test
    fun `extractRawMessages leaves imageUrl blank when no photo`() {
        val messages = TelegramFeedParser.extractRawMessages(sampleHtml)
        assertEquals("https://t.me/testchannel/102", messages[1].id)
        assertEquals("", messages[1].imageUrl)
    }

    @Test
    fun `extractRawMessages captures photo-only post with blank rawText`() {
        val messages = TelegramFeedParser.extractRawMessages(sampleHtml)
        assertEquals("https://t.me/testchannel/103", messages[2].id)
        assertEquals("", messages[2].rawText)
        assertEquals("https://cdn.example.com/photo103.jpg", messages[2].imageUrl)
    }

    @Test
    fun `extractRawMessages captures raw text with inline tags intact`() {
        val messages = TelegramFeedParser.extractRawMessages(sampleHtml)
        assertEquals(true, messages[0].rawText.contains("<br/>"))
        assertEquals(true, messages[0].rawText.contains("First line of post 101"))
    }

    @Test
    fun `extractRawMessages parses ISO-8601 datetime to correct epoch millis`() {
        val messages = TelegramFeedParser.extractRawMessages(sampleHtml)
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.set(2026, java.util.Calendar.SEPTEMBER, 7, 10, 15, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        assertEquals(cal.timeInMillis, messages[0].publishedAt)
    }

    @Test
    fun `extractRawMessages returns empty list for html with no messages`() {
        assertEquals(0, TelegramFeedParser.extractRawMessages("<html><body>channel is private</body></html>").size)
    }

    @Test
    fun `extractRawMessages captures full text even with a nested div inside it`() {
        val htmlWithNestedDiv = """
            <div class="tgme_widget_message_wrap">
            <div class="tgme_widget_message" data-post="testchannel/201" data-view="abc">
                <div class="tgme_widget_message_text js-message_text" dir="auto">Reacting to this: <div class="tgme_widget_message_reply">quoted text here</div> and here's my actual comment after the quote.</div>
                <time class="time" datetime="2026-09-07T11:00:00+00:00">11:00</time>
            </div>
            </div>
        """.trimIndent()
        val messages = TelegramFeedParser.extractRawMessages(htmlWithNestedDiv)
        assertEquals(1, messages.size)
        assertEquals(
            """Reacting to this: <div class="tgme_widget_message_reply">quoted text here</div> and here's my actual comment after the quote.""",
            messages[0].rawText,
        )
    }

    @Test
    fun `stripTelegramHtml converts br tags to newlines`() {
        assertEquals(
            "Line one\nLine two",
            TelegramFeedParser.stripTelegramHtml("Line one<br/>Line two"),
        )
    }

    @Test
    fun `stripTelegramHtml removes bold and italic tags but keeps their text`() {
        assertEquals(
            "Some bold and italic text",
            TelegramFeedParser.stripTelegramHtml("Some <b>bold</b> and <i>italic</i> text"),
        )
    }

    @Test
    fun `stripTelegramHtml removes link tags but keeps their text`() {
        assertEquals(
            "See this article",
            TelegramFeedParser.stripTelegramHtml("""See <a href="https://example.com">this article</a>"""),
        )
    }

    @Test
    fun `stripTelegramHtml decodes basic HTML entities`() {
        assertEquals(
            """Tom & Jerry said "hi" <ok>""",
            TelegramFeedParser.stripTelegramHtml("Tom &amp; Jerry said &quot;hi&quot; &lt;ok&gt;"),
        )
    }

    @Test
    fun `stripTelegramHtml decodes a numeric entity to the real emoji`() {
        assertEquals("🔴 breaking", TelegramFeedParser.stripTelegramHtml("&#128308; breaking"))
    }

    @Test
    fun `stripTelegramHtml decodes a double-escaped numeric entity`() {
        // Confirmed live on rotter.net's RSS feed (BUG-013, commit 88ea007): some sources
        // double-escape numeric entities. The tag-strip pass doesn't touch & at all, so
        // decodeHtmlEntities's own sequential &amp;-then-numeric replacement handles this
        // the same way already fixed for RSS titles.
        assertEquals("🔴🔴", TelegramFeedParser.stripTelegramHtml("&amp;#128308;&amp;#128308;"))
    }

    @Test
    fun `stripTelegramHtml trims surrounding whitespace`() {
        assertEquals("hello", TelegramFeedParser.stripTelegramHtml("  hello  \n"))
    }

    @Test
    fun `stripTelegramHtml does not over-decode a correctly-escaped named entity`() {
        // "&amp;lt;" means "display the literal text &lt;" - decoding &amp; first would
        // wrongly turn this into "<", which is a different (and wrong) result.
        assertEquals("&lt;", TelegramFeedParser.stripTelegramHtml("&amp;lt;"))
    }

    @Test
    fun `stripTelegramHtml falls back to literal text for a numeric entity outside valid Unicode range`() {
        // 9999999 fits in Int but exceeds the real Unicode ceiling (0x10FFFF = 1114111) -
        // Character.toChars() would throw IllegalArgumentException for it uncaught.
        assertEquals("&#9999999;", TelegramFeedParser.stripTelegramHtml("&#9999999;"))
    }

    @Test
    fun `parseArticles joins first two lines as title when the message has exactly two lines`() {
        // Live bug report: a collapsed widget row built from only the first line looked
        // too thin for a typical multi-line Telegram post. Message 101 has exactly two
        // lines, so both are now consumed by the title and none are left for the
        // description.
        val articles = TelegramFeedParser.parseArticles(
            feedId = "https://t.me/s/testchannel",
            feedDisplayName = "Test Channel",
            html = sampleHtml,
            maxItems = 10,
        )
        assertEquals("First line of post 101 Second line with more detail.", articles[0].title)
        assertEquals("", articles[0].description)
    }

    @Test
    fun `parseArticles takes only the first two lines as title when more lines remain`() {
        val html = """
            <div class="tgme_widget_message_wrap">
            <div class="tgme_widget_message" data-post="testchannel/301" data-view="abc">
                <div class="tgme_widget_message_text js-message_text" dir="auto">Line one of post 301<br/>Line two of post 301<br/>Line three of post 301</div>
                <time class="time" datetime="2026-09-07T12:00:00+00:00">12:00</time>
            </div>
            </div>
        """.trimIndent()
        val articles = TelegramFeedParser.parseArticles(
            feedId = "https://t.me/s/testchannel",
            feedDisplayName = "Test Channel",
            html = html,
            maxItems = 10,
        )
        assertEquals("Line one of post 301 Line two of post 301", articles[0].title)
        assertEquals("Line three of post 301", articles[0].description)
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

    @Test
    fun `extractChannelTitle skips an empty verified-badge span to find the real title span`() {
        val html = """
            <div class="tgme_channel_info_header_title" dir="auto"><span class="verified-icon"></span><span dir="auto">Test Channel</span></div>
        """.trimIndent()
        assertEquals("Test Channel", TelegramFeedParser.extractChannelTitle(html))
    }
}
