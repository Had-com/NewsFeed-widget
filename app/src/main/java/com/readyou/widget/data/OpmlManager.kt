package com.readyou.widget.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser

object OpmlManager {

    /** Generates an OPML 2.0 XML string from the given feed list. */
    fun export(feeds: List<FeedConfig>): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<opml version="2.0">""")
        appendLine("""  <head><title>ReadYou Widget Feeds</title></head>""")
        appendLine("""  <body>""")
        for (feed in feeds) {
            if (feed.feedUrl.isNotBlank()) {
                val name = feed.displayName.xmlEscape()
                val url  = feed.feedUrl.xmlEscape()
                appendLine("""    <outline type="rss" text="$name" title="$name" xmlUrl="$url"/>""")
            }
        }
        appendLine("""  </body>""")
        append("""</opml>""")
    }

    /**
     * Parses an OPML document and returns a list of (displayName, feedUrl) pairs.
     * Handles flat OPML and grouped OPML (folders) transparently.
     */
    fun parse(xml: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        runCatching {
            val parser = Xml.newPullParser()
            parser.setInput(xml.reader())
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name.equals("outline", true)) {
                    val xmlUrl = parser.getAttributeValue(null, "xmlUrl")
                        ?: parser.getAttributeValue(null, "xmlurl")
                    if (!xmlUrl.isNullOrBlank()) {
                        val title = parser.getAttributeValue(null, "text")
                            ?: parser.getAttributeValue(null, "title")
                            ?: xmlUrl
                        result += title to xmlUrl
                    }
                }
                event = try { parser.next() } catch (_: Exception) { break }
            }
        }
        return result
    }

    private fun String.xmlEscape() = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
