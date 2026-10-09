package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `stripTelegramHtml keeps a link's URL alongside its label text`() {
        // BUG-023: the old behaviour ("See this article") silently discarded the href,
        // leaving no way to reach the linked page. Chosen format: "label (URL)".
        assertEquals(
            "See this article (https://example.com)",
            TelegramFeedParser.stripTelegramHtml("""See <a href="https://example.com">this article</a>"""),
        )
    }

    @Test
    fun `stripTelegramHtml preserves a real Telegram post's trailing link on its own line`() {
        // Verified live against https://t.me/s/N12_News (BUG-023): a post ending in a <br>
        // then a "קישור לכתבה" (link to the article) anchor. Both the label and the URL must
        // survive, on the line the <br> started.
        val raw = "עדכון מהשטח.<br/><a href=\"https://www.mako.co.il/news-military/2026_q3/Article-abc123.htm\">קישור לכתבה</a>"
        assertEquals(
            "עדכון מהשטח.\nקישור לכתבה (https://www.mako.co.il/news-military/2026_q3/Article-abc123.htm)",
            TelegramFeedParser.stripTelegramHtml(raw),
        )
    }

    @Test
    fun `stripTelegramHtml does not duplicate an auto-linked bare URL`() {
        // Telegram auto-links a bare URL typed in a post, so label == href verbatim.
        // "https://x.com/y (https://x.com/y)" would be a useless duplicate.
        assertEquals(
            "See https://x.com/y for details",
            TelegramFeedParser.stripTelegramHtml(
                """See <a href="https://x.com/y">https://x.com/y</a> for details"""
            ),
        )
    }

    @Test
    fun `stripTelegramHtml inserts the bare URL when the link text is empty or whitespace`() {
        assertEquals(
            "See https://example.com for details",
            TelegramFeedParser.stripTelegramHtml(
                """See <a href="https://example.com">   </a> for details"""
            ),
        )
    }

    @Test
    fun `stripTelegramHtml preserves every URL when a post has multiple links`() {
        assertEquals(
            "First (https://a.example) and Second (https://b.example)",
            TelegramFeedParser.stripTelegramHtml(
                """<a href="https://a.example">First</a> and <a href="https://b.example">Second</a>"""
            ),
        )
    }

    @Test
    fun `stripTelegramHtml strips a formatting tag inside link text but keeps the label and URL`() {
        assertEquals(
            "text (https://example.com)",
            TelegramFeedParser.stripTelegramHtml(
                """<a href="https://example.com"><b>text</b></a>"""
            ),
        )
    }

    @Test
    fun `stripTelegramHtml leaves the rest of the message intact when an anchor has no closing tag`() {
        // Malformed markup (missing </a>): the anchor regex requires a closing tag and simply
        // doesn't match, so this falls through to the generic tag-strip pass, which removes
        // just the opening <a href="..."> tag itself (like any other unrecognized tag) and
        // keeps every character that follows it - never throws, never corrupts the rest.
        assertEquals(
            "Before after",
            TelegramFeedParser.stripTelegramHtml("""Before <a href="https://example.com">after"""),
        )
    }

    @Test
    fun `stripTelegramHtml leaves link text alone when the anchor has no href`() {
        // Malformed/unsupported markup (no href attribute): the anchor regex doesn't match,
        // so this behaves exactly like the old bold/italic case - tag stripped, text kept,
        // no URL to preserve since none was given.
        assertEquals(
            "See this article",
            TelegramFeedParser.stripTelegramHtml("""See <a class="internal">this article</a>"""),
        )
    }

    @Test
    fun `stripTelegramHtml drops a channel's own self-link signature but keeps the handle`() {
        // BUG-025: N12chat posts end with <a href="https://t.me/N12chat">@N12chat</a>. After
        // BUG-023 that became "@N12chat (https://t.me/N12chat)" - a pointless signature. The
        // channel linking to itself carries no information, so only the handle stays.
        assertEquals(
            "Update here\n@N12chat",
            TelegramFeedParser.stripTelegramHtml(
                """Update here<br/><a href="https://t.me/N12chat">@N12chat</a>"""
            ),
        )
    }

    @Test
    fun `stripTelegramHtml recognises the self-link case-insensitively and with a trailing slash`() {
        assertEquals(
            "@N12chat",
            TelegramFeedParser.stripTelegramHtml("""<a href="https://T.ME/n12CHAT/">@N12chat</a>"""),
        )
        assertEquals(
            "@N12chat",
            TelegramFeedParser.stripTelegramHtml("""<a href="http://telegram.me/N12chat">@N12chat</a>"""),
        )
    }

    @Test
    fun `stripTelegramHtml keeps the URL when an at-handle links to a different channel or a post`() {
        assertEquals(
            "@other (https://t.me/N12chat)",
            TelegramFeedParser.stripTelegramHtml("""<a href="https://t.me/N12chat">@other</a>"""),
        )
        assertEquals(
            "@N12chat (https://t.me/N12chat/123)",
            TelegramFeedParser.stripTelegramHtml("""<a href="https://t.me/N12chat/123">@N12chat</a>"""),
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

    // BUG-3 (QA 14.ah): an N12chat title kept the literal text "&rlm;". Invisible direction marks
    // (LRM U+200E, RLM U+200F, ALM U+061C) are dropped entirely, in any spelling, so they neither
    // show up as text nor take part in token matching.
    @Test
    fun `stripTelegramHtml drops named direction marks`() {
        assertEquals("ענבר טויזר: שריפה", TelegramFeedParser.stripTelegramHtml("ענבר טויזר&rlm;: שריפה"))
        assertEquals("abc", TelegramFeedParser.stripTelegramHtml("&lrm;abc&lrm;"))
        assertEquals("שלום עולם", TelegramFeedParser.stripTelegramHtml("&rlm;שלום&rlm; עולם&lrm;"))
    }

    @Test
    fun `stripTelegramHtml drops numeric direction marks decimal and hex`() {
        assertEquals("שלום עולם", TelegramFeedParser.stripTelegramHtml("&#8207;שלום&#8206; עולם"))
        assertEquals("שלום עולם", TelegramFeedParser.stripTelegramHtml("&#x200F;שלום&#x200e; עולם&#1564;"))
    }

    @Test
    fun `stripTelegramHtml drops direction mark characters that are already in the text`() {
        assertEquals("שלום עולם", TelegramFeedParser.stripTelegramHtml("\u200Fשלום\u200E עולם\u061C"))
    }

    @Test
    fun `stripTelegramHtml keeps an escaped direction mark entity as the literal text it names`() {
        // "&amp;rlm;" means "display the text &rlm;" - the same single-pass rule as "&amp;lt;"
        assertEquals("&rlm;", TelegramFeedParser.stripTelegramHtml("&amp;rlm;"))
    }

    @Test
    fun `stripTelegramHtml keeps other format characters and decodes hex numeric entities generally`() {
        // only the three direction MARKS go; a zero width joiner (emoji sequences) stays
        assertEquals("a\u200Db", TelegramFeedParser.stripTelegramHtml("a&#8205;b"))
        assertEquals("🔴", TelegramFeedParser.stripTelegramHtml("&#x1F534;"))
    }

    @Test
    fun `parseArticles leaves no direction marks or entity text in the title or description`() {
        val a = parseOne("ענבר טויזר&rlm;: שריפה בגבעתיים&lrm;<br/>פרטים נוספים&#8207; בהמשך")
        assertEquals("ענבר טויזר: שריפה בגבעתיים פרטים נוספים בהמשך", a.title)
        assertFalse(a.title.contains("&"))
        val b = parseOne("כותרת<br/>שורה שנייה<br/>&rlm;גוף&#8207; ההודעה&lrm;")
        assertEquals("גוף ההודעה", b.description)
    }

    // Telegram split (restored after the c718e74 regression): title = first two lines joined,
    // description = the REST of the post only, so expanding a row never repeats the headline.
    // Nothing is lost: a title over the 200-char display cap is cut at a word boundary with an
    // ellipsis and its overflow moves to the START of the description. Posts with no remainder
    // keep an empty description (no expand row), as originally.
    private fun postHtml(id: Int, body: String) = """
        <div class="tgme_widget_message" data-post="testchannel/$id" data-view="abc">
            <div class="tgme_widget_message_text js-message_text" dir="auto">$body</div>
            <time class="time" datetime="2026-09-07T12:00:00+00:00">12:00</time>
        </div>
    """.trimIndent()

    private fun parseOne(body: String) =
        TelegramFeedParser.parseArticles("f", "Ch", postHtml(300, body), 10)[0]

    @Test
    fun `parseArticles joins first two lines as title when the message has exactly two lines`() {
        val articles = TelegramFeedParser.parseArticles(
            feedId = "https://t.me/s/testchannel",
            feedDisplayName = "Test Channel",
            html = sampleHtml,
            maxItems = 10,
        )
        assertEquals("First line of post 101 Second line with more detail.", articles[0].title)
        // Empty description again: the whole post is the title, no expand row, no duplication.
        assertEquals("", articles[0].description)
    }

    @Test
    fun `parseArticles gives a one-line post its full text as title and an empty description`() {
        val a = parseOne("Only line")
        assertEquals("Only line", a.title)
        assertEquals("", a.description)
    }

    @Test
    fun `parseArticles keeps a title of exactly 200 chars uncut`() {
        val line = "y".repeat(200)
        val a = parseOne(line)
        assertEquals(line, a.title)
        assertEquals("", a.description)
    }

    @Test
    fun `parseArticles description is only the remainder when three or more lines`() {
        val a = parseOne("Line one<br/>Line two<br/>Line three<br/>Line four<br/>Line five")
        assertEquals("Line one Line two", a.title)
        assertEquals("Line three\nLine four\nLine five", a.description)
    }

    @Test
    fun `parseArticles moves title overflow to the start of the description on a word boundary`() {
        val words = (1..60).joinToString(" ") { "word$it" } // well over 200 chars, one line
        val a = parseOne("$words<br/>Second<br/>Third")
        assertEquals(true, a.title.length <= 200)
        assertEquals(true, a.title.endsWith("…"))
        val head = a.title.removeSuffix("…")
        // cut on a word boundary: the title never ends mid-word
        assertEquals(true, words.startsWith(head))
        assertEquals(' ', words[head.length])
        // overflow first, then the remaining lines
        assertEquals(true, a.description.endsWith("\nThird"))
        val overflow = a.description.substringBefore("\n")
        assertEquals(words.substring(head.length).trim() + " Second", overflow)
    }

    @Test
    fun `parseArticles cuts a single unbroken over-long first line without losing characters`() {
        val a = parseOne("z".repeat(500))
        assertEquals(true, a.title.length <= 200)
        assertEquals(500, a.title.removeSuffix("…").length + a.description.length)
    }

    @Test
    fun `parseArticles caps a 4500 char post at 4096 in the description`() {
        val a = parseOne("Head<br/>Second<br/>" + "x".repeat(4500))
        assertEquals("Head Second", a.title)
        assertEquals(4096, a.description.length)
    }

    @Test
    fun `parseArticles never duplicates text between title and description`() {
        // BUG-025 note: the one deliberate exception to "text stays where it is" is a URL in
        // the title lines - it is MOVED (not copied) to the end of the description, while its
        // label stays in the title. So the label and the URL each still appear exactly once.
        val lines = listOf("Alpha headline", "Bravo second", "Charlie body", "Delta body")
        val a = parseOne(lines.joinToString("<br/>"))
        val combined = a.title + "\n" + a.description
        lines.forEach { assertEquals(1, Regex(Regex.escape(it)).findAll(combined).count()) }
        val withLink = parseOne("Alpha headline <a href=\"https://example.com/p\">the label</a><br/>Bravo second<br/>Charlie body")
        val both = withLink.title + "\n" + withLink.description
        assertEquals(1, Regex(Regex.escape("the label")).findAll(both).count())
        assertEquals(1, Regex(Regex.escape("https://example.com/p")).findAll(both).count())
        assertEquals(false, withLink.title.contains("https://"))
        // and with a cut title: every word of the post appears exactly once, in order
        val words = (1..80).joinToString(" ") { "w$it" }
        val b = parseOne("$words<br/>tail")
        val all = (b.title.removeSuffix("…") + " " + b.description.replace("\n", " ")).split(" ").filter { it.isNotBlank() }
        assertEquals((1..80).map { "w$it" } + "tail", all)
    }

    @Test
    fun `parseArticles keeps a post's link URL alive through title and description`() {
        // BUG-023 end-to-end: the URL must survive stripTelegramHtml AND the title/description
        // split, landing somewhere in the stored article, not just in the intermediate string.
        val a = parseOne(
            "Breaking news headline<br/><a href=\"https://example.com/story\">קישור לכתבה</a>"
        )
        val combined = a.title + "\n" + a.description
        assertEquals(true, combined.contains("https://example.com/story"))
        // still never duplicates: the URL appears exactly once across title+description
        assertEquals(1, Regex(Regex.escape("https://example.com/story")).findAll(combined).count())
    }

    // ---- BUG-025: URLs never in the headline; they move to the description ----

    private val MAKO = "https://www.mako.co.il/news-military/2026_q3/Article-abc123.htm"

    @Test
    fun `parseArticles keeps a trailing link's URL out of the title and puts it in the description`() {
        // (a) short post with a trailing link, as N12_News posts them.
        val a = parseOne("עדכון מהשטח.<br/><a href=\"$MAKO\">קישור לכתבה</a>")
        assertEquals("עדכון מהשטח. קישור לכתבה", a.title)
        assertEquals(MAKO, a.description)
    }

    @Test
    fun `parseArticles removes a URL from a one-line title and keeps the label`() {
        val a = parseOne("Breaking <a href=\"https://example.com/s\">read more</a>")
        assertEquals("Breaking read more", a.title)
        assertEquals("https://example.com/s", a.description)
    }

    @Test
    fun `parseArticles appends title URLs after the existing description in order and dedupes`() {
        val a = parseOne(
            "<a href=\"https://a.example/1\">one</a> and <a href=\"https://b.example/2\">two</a><br/>" +
                "<a href=\"https://a.example/1\">again</a><br/>Body line<br/>Last line"
        )
        assertEquals("one and two again", a.title)
        assertEquals("Body line\nLast line\nhttps://a.example/1\nhttps://b.example/2", a.description)
    }

    @Test
    fun `parseArticles leaves a link in the description body inline`() {
        // (b) long multi-line post, link in the middle: only TITLE lines are cleaned; the body
        // keeps its text exactly as is (the body renders without shrinking, see TextBitmapHelper).
        val a = parseOne(
            "Head<br/>Second<br/>Third <a href=\"https://example.com/mid\">mid link</a> text<br/>Fourth"
        )
        assertEquals("Head Second", a.title)
        assertEquals("Third mid link (https://example.com/mid) text\nFourth", a.description)
    }

    @Test
    fun `parseArticles does not re-append a title URL the description already contains`() {
        val a = parseOne(
            "Head <a href=\"https://example.com/x\">link</a><br/>Second<br/>See https://example.com/x here"
        )
        assertEquals("Head link Second", a.title)
        assertEquals("See https://example.com/x here", a.description)
    }

    @Test
    fun `parseArticles handles an auto-linked bare URL in the title`() {
        val a = parseOne("Read <a href=\"https://x.com/y\">https://x.com/y</a> now")
        assertEquals("Read now", a.title)
        assertEquals("https://x.com/y", a.description)
    }

    @Test
    fun `parseArticles handles a plain-text URL that Telegram did not anchor`() {
        val a = parseOne("Look https://x.com/y?a=1&amp;b=2.")
        assertEquals("Look .", a.title)
        assertEquals("https://x.com/y?a=1&b=2", a.description)
    }

    @Test
    fun `parseArticles falls back to the channel name for a post whose only content is a bare URL`() {
        // (d) nothing but a link: the title would be empty (and dropped downstream), so it
        // falls back to the channel name exactly like a photo-only post; the URL is the body.
        val a = parseOne("<a href=\"https://x.com/only\">https://x.com/only</a>")
        assertEquals("Ch", a.title)
        assertEquals("https://x.com/only", a.description)
        val b = parseOne("https://x.com/plain")
        assertEquals("Ch", b.title)
        assertEquals("https://x.com/plain", b.description)
    }

    @Test
    fun `parseArticles keeps a bare URL on its own second line out of the title`() {
        val a = parseOne("Headline<br/>https://x.com/z<br/>Body")
        assertEquals("Headline", a.title)
        assertEquals("Body\nhttps://x.com/z", a.description)
    }

    @Test
    fun `parseArticles keeps a channel self-link signature out of the description's URLs`() {
        // (c) N12chat: one line + signature -> the handle is in the title, there is no
        // description, so the row is NOT expandable (no URL to show).
        val a = parseOne("Short update<br/><a href=\"https://t.me/N12chat\">@N12chat</a>")
        assertEquals("Short update @N12chat", a.title)
        assertEquals("", a.description)
        // With a 3rd line the signature is just the description line, exactly as before BUG-023.
        val b = parseOne(
            "Line one<br/>Line two<br/><a href=\"https://t.me/N12chat\">@N12chat</a>"
        )
        assertEquals("Line one Line two", b.title)
        assertEquals("@N12chat", b.description)
    }

    @Test
    fun `parseArticles never lets a moved URL be cut by the 4096 cap`() {
        val a = parseOne(
            "Head <a href=\"$MAKO\">link</a><br/>Second<br/>" + "x".repeat(5000)
        )
        assertEquals(4096, a.description.length)
        assertEquals(true, a.description.endsWith("\n$MAKO"))
        assertEquals(false, a.title.contains("https://"))
    }

    @Test
    fun `parseArticles caps a post at 4096 even after a link is expanded to include its URL`() {
        val a = parseOne("Head<br/>Second<br/>" + "x".repeat(4400) + """<a href="https://example.com/very-long-story-path">link</a>""")
        assertEquals("Head Second", a.title)
        assertEquals(4096, a.description.length)
    }

    @Test
    fun `isTelegramPostUrl recognises post permalinks only`() {
        assertEquals(true, TelegramFeedParser.isTelegramPostUrl("https://t.me/N12_News/47114"))
        assertEquals(true, TelegramFeedParser.isTelegramPostUrl("t.me/ch/123"))
        assertEquals(true, TelegramFeedParser.isTelegramPostUrl("https://telegram.me/ch/5/"))
        assertEquals(true, TelegramFeedParser.isTelegramPostUrl("http://t.me/ch/123?single"))
        assertEquals(false, TelegramFeedParser.isTelegramPostUrl("https://t.me/s/ch"))
        assertEquals(false, TelegramFeedParser.isTelegramPostUrl("https://t.me/ch"))
        assertEquals(false, TelegramFeedParser.isTelegramPostUrl("https://example.com/ch/123"))
        assertEquals(false, TelegramFeedParser.isTelegramPostUrl("https://nott.me/ch/123"))
        assertEquals(false, TelegramFeedParser.isTelegramPostUrl(""))
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
