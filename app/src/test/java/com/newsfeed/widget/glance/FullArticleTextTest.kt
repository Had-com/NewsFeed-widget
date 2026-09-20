package com.newsfeed.widget.glance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullArticleTextTest {
    private val url = "https://example.com/a"

    @Test fun `uses fetched text when it is a real improvement`() =
        assertEquals("Much longer real body", chooseFullArticleText("short", "Much longer real body", url))

    @Test fun `keeps current when fetched is null`() = assertEquals("cur", chooseFullArticleText("cur", null, url))
    @Test fun `keeps current when fetched is blank`() = assertEquals("cur", chooseFullArticleText("cur", "  \n", url))
    @Test fun `keeps current when fetched is an error string`() =
        assertEquals("cur", chooseFullArticleText("cur", "Could not load article: HTTP 404", url))

    @Test fun `keeps current when fetched is under half of a long current`() {
        val cur = "a".repeat(300)
        assertEquals(cur, chooseFullArticleText(cur, "b".repeat(100), url))
    }

    @Test fun `short current does not apply the half rule`() =
        assertEquals("tiny", chooseFullArticleText("longer cur", "tiny", url))

    @Test fun `keeps current for a telegram host even if fetched looks fine`() =
        assertEquals("cur", chooseFullArticleText("cur", "Download Context Embed View In Channel", "https://t.me/ch/1"))

    @Test fun `returns fetched when current is blank`() =
        assertEquals("body", chooseFullArticleText("", "body", url))

    @Test fun `canLoadFullArticle is false for telegram posts`() {
        assertFalse(canLoadFullArticle("https://t.me/ch/1"))
        assertTrue(canLoadFullArticle("https://example.com/a"))
        assertFalse(canLoadFullArticle(""))
    }
}
