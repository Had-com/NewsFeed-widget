package com.newsfeed.widget.glance

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlTokensTest {

    @Test
    fun `isUrlToken recognises http and https case-insensitively`() {
        assertEquals(true, UrlTokens.isUrlToken("https://www.mako.co.il/news/a.htm"))
        assertEquals(true, UrlTokens.isUrlToken("http://example.com"))
        assertEquals(true, UrlTokens.isUrlToken("HTTPS://EXAMPLE.COM"))
    }

    @Test
    fun `isUrlToken tolerates an opening bracket or quote before the scheme`() {
        // A legacy "label (URL)" with the paren glued to the URL still counts as a URL token.
        assertEquals(true, UrlTokens.isUrlToken("(https://example.com/x)"))
        assertEquals(true, UrlTokens.isUrlToken("\"https://example.com/x\""))
        assertEquals(true, UrlTokens.isUrlToken("<https://example.com/x>"))
    }

    @Test
    fun `isUrlToken rejects ordinary words and look-alikes`() {
        assertEquals(false, UrlTokens.isUrlToken("headline"))
        assertEquals(false, UrlTokens.isUrlToken("קישור"))
        assertEquals(false, UrlTokens.isUrlToken("www.example.com"))
        assertEquals(false, UrlTokens.isUrlToken("xhttps://example.com"))
        assertEquals(false, UrlTokens.isUrlToken("https:/example.com"))
        assertEquals(false, UrlTokens.isUrlToken(""))
    }

    // measure = 1 px per character keeps the expectations obvious.
    private val measure: (String) -> Float = { it.length.toFloat() }

    @Test
    fun `widestWordIgnoringUrls ignores a long URL and returns the widest ordinary word`() {
        val text = "short wordy https://www.mako.co.il/news-military/2026_q3/Article-abc123.htm"
        assertEquals(5f, UrlTokens.widestWordIgnoringUrls(text, measure), 0f)
    }

    @Test
    fun `widestWordIgnoringUrls still measures a long ordinary word`() {
        assertEquals(20f, UrlTokens.widestWordIgnoringUrls("a ${"x".repeat(20)} b https://u.example/z", measure), 0f)
    }

    @Test
    fun `widestWordIgnoringUrls handles URL-only, blank and multi-line text`() {
        assertEquals(0f, UrlTokens.widestWordIgnoringUrls("https://x.example/long/path", measure), 0f)
        assertEquals(0f, UrlTokens.widestWordIgnoringUrls("   \n ", measure), 0f)
        assertEquals(0f, UrlTokens.widestWordIgnoringUrls("", measure), 0f)
        assertEquals(6f, UrlTokens.widestWordIgnoringUrls("one\nhttps://x.example/p\nsecond", measure), 0f)
    }

    @Test
    fun `widestWordIgnoringUrls without any URL equals the plain widest word`() {
        assertEquals(7f, UrlTokens.widestWordIgnoringUrls("aa bbbbbbb c", measure), 0f)
    }
}
