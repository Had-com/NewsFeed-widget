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
