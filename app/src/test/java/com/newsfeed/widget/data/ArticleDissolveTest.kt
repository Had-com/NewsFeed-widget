package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleDissolveTest {

    private val readAt = 1_000_000L

    @Test
    fun `dissolve stages fit inside the grace period and in order`() {
        assertTrue(DISSOLVE_STAGE1_MS > 0L)
        assertTrue(DISSOLVE_STAGE1_MS < DISSOLVE_STAGE2_MS)
        assertTrue(DISSOLVE_STAGE2_MS < UNREAD_GRACE_PERIOD_MS)
    }

    @Test
    fun `stage is 0 for a null readAt`() {
        assertEquals(0, dissolveStage(null, readAt + 4_500L))
    }

    @Test
    fun `stage is 0 just under 2_5s and 1 at 2_5s`() {
        assertEquals(0, dissolveStage(readAt, readAt))
        assertEquals(0, dissolveStage(readAt, readAt + 2_499L))
        assertEquals(1, dissolveStage(readAt, readAt + 2_500L))
    }

    @Test
    fun `stage is 1 just under 4s and 2 at 4s`() {
        assertEquals(1, dissolveStage(readAt, readAt + 3_999L))
        assertEquals(2, dissolveStage(readAt, readAt + 4_000L))
    }

    @Test
    fun `stage stays 2 up to and past the 5s removal point`() {
        assertEquals(2, dissolveStage(readAt, readAt + 4_999L))
        assertEquals(2, dissolveStage(readAt, readAt + 5_000L))
        assertEquals(2, dissolveStage(readAt, readAt + 9_000L))
    }

    @Test
    fun `stage is 0 when the clock is before readAt`() {
        assertEquals(0, dissolveStage(readAt, readAt - 500L))
    }

    @Test
    fun `stage 0 leaves text unchanged`() {
        assertEquals("Hello world", dissolveText("Hello world", 0, "id"))
    }

    @Test
    fun `stage 1 keeps spaces and length and changes some but not all characters`() {
        val text = "Breaking news from the capital"
        val out = dissolveText(text, 1, "a1")
        assertEquals(text.length, out.length)
        for (i in text.indices) if (text[i] == ' ') assertEquals(' ', out[i])
        val dots = out.count { it == '·' }
        val nonSpace = text.count { it != ' ' }
        assertTrue(dots > 0)
        assertTrue(dots < nonSpace)
        assertNotEquals(text, out)
    }

    @Test
    fun `stage 1 is deterministic for the same seed`() {
        val text = "Breaking news from the capital"
        assertEquals(dissolveText(text, 1, "a1"), dissolveText(text, 1, "a1"))
    }

    @Test
    fun `stage 2 replaces every non-space character with a dot`() {
        assertEquals("···· ·····", dissolveText("abcd efghi", 2, "x"))
    }

    @Test
    fun `stage 2 also treats tabs and newlines as spaces`() {
        assertEquals("·· ·\n··", dissolveText("ab c\ncd", 2, "x"))
    }

    @Test
    fun `hebrew text is dissolved per character keeping spaces`() {
        val text = "שלום עולם יפה"
        val s2 = dissolveText(text, 2, "h")
        assertEquals("···· ···· ···", s2)
        val s1 = dissolveText(text, 1, "h")
        assertEquals(text.length, s1.length)
        assertTrue(s1.contains('·'))
        assertTrue(s1.any { it in 'א'..'ת' })
    }

    @Test
    fun `surrogate pairs are treated as one character`() {
        val text = "a😀b😀"
        assertEquals("····", dissolveText(text, 2, "e"))
        val s1 = dissolveText(text, 1, "e")
        // Never a lone surrogate half left behind.
        var i = 0
        while (i < s1.length) {
            val cp = s1.codePointAt(i)
            assertTrue(cp == '·'.code || cp == 'a'.code || cp == 'b'.code || cp == 0x1F600)
            i += Character.charCount(cp)
        }
    }

    @Test
    fun `empty string stays empty`() {
        assertEquals("", dissolveText("", 1, "x"))
        assertEquals("", dissolveText("", 2, "x"))
    }

    @Test
    fun `dissolveArticle only touches title and description at a stage above 0`() {
        val a = ArticleItem(id = "a", feedId = "f", feedName = "F", title = "Some title",
            description = "Some description", publishedAt = 0L, isRead = true, readAt = readAt)
        assertEquals(a, dissolveArticle(a, readAt + 1_000L))
        val d = dissolveArticle(a, readAt + 4_500L)
        assertEquals("···· ·····", d.title)
        assertEquals("···· ···········", d.description)
        assertEquals(a.id, d.id)
        assertEquals(a.feedName, d.feedName)
    }

    @Test
    fun `dissolveArticle ignores an unread article`() {
        val a = ArticleItem(id = "a", feedId = "f", feedName = "F", title = "T",
            publishedAt = 0L, isRead = false, readAt = readAt)
        assertEquals(a, dissolveArticle(a, readAt + 4_500L))
    }

    @Test
    fun `graceRefreshDelays are the two dissolve stages then the removal, each with a buffer`() {
        assertEquals(
            listOf(DISSOLVE_STAGE1_MS + 100L, DISSOLVE_STAGE2_MS + 100L, UNREAD_GRACE_PERIOD_MS + 100L),
            graceRefreshDelays(),
        )
    }
}
