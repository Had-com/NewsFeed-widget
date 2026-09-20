package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleDissolveTest {

    private val readAt = 1_000_000L

    @Test
    fun `stage boundaries are increasing and all inside the grace period`() {
        assertTrue(DISSOLVE_STAGE_STARTS_MS.isNotEmpty())
        assertTrue(DISSOLVE_STAGE_STARTS_MS.first() > 0L)
        assertEquals(DISSOLVE_STAGE_STARTS_MS.sorted(), DISSOLVE_STAGE_STARTS_MS)
        assertEquals(DISSOLVE_STAGE_STARTS_MS.distinct(), DISSOLVE_STAGE_STARTS_MS)
        assertTrue(DISSOLVE_STAGE_STARTS_MS.last() < UNREAD_GRACE_PERIOD_MS)
        assertEquals(DISSOLVE_STAGE_STARTS_MS.size, DISSOLVE_KEEP_FRACTIONS.size)
    }

    @Test
    fun `stage is 0 for a null readAt`() {
        assertEquals(0, dissolveStage(null, readAt + 4_500L))
    }

    @Test
    fun `stage 0 until 2_5s then 1`() {
        assertEquals(0, dissolveStage(readAt, readAt))
        assertEquals(0, dissolveStage(readAt, readAt + 2_499L))
        assertEquals(1, dissolveStage(readAt, readAt + 2_500L))
    }

    @Test
    fun `stage 1 until 3_33s then 2, then 3 from 4_17s`() {
        assertEquals(1, dissolveStage(readAt, readAt + 3_332L))
        assertEquals(2, dissolveStage(readAt, readAt + 3_333L))
        assertEquals(2, dissolveStage(readAt, readAt + 4_166L))
        assertEquals(3, dissolveStage(readAt, readAt + 4_167L))
    }

    @Test
    fun `stage stays 3 up to and past the 5s removal point`() {
        assertEquals(3, dissolveStage(readAt, readAt + 4_999L))
        assertEquals(3, dissolveStage(readAt, readAt + 5_000L))
        assertEquals(3, dissolveStage(readAt, readAt + 9_000L))
    }

    @Test
    fun `stage is 0 when the clock is before readAt`() {
        assertEquals(0, dissolveStage(readAt, readAt - 500L))
    }

    @Test
    fun `stage 0 leaves text unchanged`() {
        assertEquals("Hello world", dissolveText("Hello world", 0))
    }

    @Test
    fun `stage 1 turns every non-space character into a dot keeping spaces and length`() {
        assertEquals("···· ·····", dissolveText("abcd efghi", 1))
        assertEquals("·· ·\n··", dissolveText("ab c\ncd", 1))
    }

    @Test
    fun `stage 2 keeps the first two thirds of the dotted text`() {
        // 9 chars -> 6 kept
        assertEquals("······", dissolveText("abcdefghi", 2))
    }

    @Test
    fun `stage 3 keeps the first third of the dotted text`() {
        assertEquals("···", dissolveText("abcdefghi", 3))
    }

    @Test
    fun `dots are erased from the end so length shrinks stage by stage`() {
        val text = "one two three four five six"
        val lens = (1..3).map { dissolveText(text, it).length }
        assertEquals(text.length, lens[0])
        assertTrue(lens[0] > lens[1])
        assertTrue(lens[1] > lens[2])
    }

    @Test
    fun `no dangling trailing space after cutting`() {
        // 8 chars, keep 5 -> "ab cd" ... cut lands right after a space
        val out = dissolveText("abc def g", 3) // 9 chars keep 3 -> "abc" -> "···"
        assertEquals("···", out)
        val out2 = dissolveText("ab cdefgh", 3) // keep 3 -> "ab " -> trimmed
        assertEquals("··", out2)
    }

    @Test
    fun `a non-empty title never becomes empty in stages 1 to 3`() {
        for (stage in 1..3) {
            assertEquals("·", dissolveText("a", stage))
            assertEquals("·", dissolveText("ab", stage).take(1))
            assertTrue(dissolveText("  x", stage).isNotEmpty())
        }
    }

    @Test
    fun `hebrew text works`() {
        assertEquals("···· ···· ···", dissolveText("שלום עולם יפה", 1))
        val s3 = dissolveText("שלום עולם יפה", 3)
        assertTrue(s3.isNotEmpty())
        assertTrue(s3.all { it == '·' || it == ' ' })
    }

    @Test
    fun `surrogate pairs are one character`() {
        assertEquals("····", dissolveText("a😀b😀", 1))
        assertEquals("··", dissolveText("a😀b😀", 2)) // 4 code points, keep 2
        assertTrue(dissolveText("a😀b😀", 3).all { it == '·' })
    }

    @Test
    fun `empty and blank strings stay as they are`() {
        assertEquals("", dissolveText("", 1))
        assertEquals("", dissolveText("", 3))
        assertEquals("   ", dissolveText("   ", 2))
    }

    @Test
    fun `dissolveArticle dissolves title and description at each stage`() {
        val a = ArticleItem(id = "a", feedId = "f", feedName = "F", title = "abcdefghi",
            description = "abcdefghi", publishedAt = 0L, isRead = true, readAt = readAt)
        assertEquals(a, dissolveArticle(a, readAt + 1_000L))
        assertEquals("·········", dissolveArticle(a, readAt + 2_600L).title)
        assertEquals("······", dissolveArticle(a, readAt + 3_500L).title)
        val d = dissolveArticle(a, readAt + 4_500L)
        assertEquals("···", d.title)
        assertEquals("···", d.description)
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
    fun `graceRefreshDelays are every stage boundary then the removal, each with a buffer`() {
        assertEquals(
            DISSOLVE_STAGE_STARTS_MS.map { it + 100L } + (UNREAD_GRACE_PERIOD_MS + 100L),
            graceRefreshDelays(),
        )
        assertEquals(4, graceRefreshDelays().size)
        assertEquals(graceRefreshDelays().sorted(), graceRefreshDelays())
    }

    @Test
    fun `remainingRefreshDelays right at the mark returns all four delays`() {
        assertEquals(graceRefreshDelays(), remainingRefreshDelays(readAt, readAt))
    }

    @Test
    fun `remainingRefreshDelays counts from the mark, not from when it was called`() {
        // 1.15s late (a slow render): every remaining wait is shortened by exactly that.
        val late = remainingRefreshDelays(readAt, readAt + 1_150L)
        assertEquals(graceRefreshDelays().map { it - 1_150L }, late)
    }

    @Test
    fun `remainingRefreshDelays past a boundary fires one catch-up now plus the future ones`() {
        // 3_000ms: stage-1 boundary (2_600) passed; 3_433 / 4_267 / 5_100 still ahead.
        assertEquals(listOf(0L, 433L, 1_267L, 2_100L), remainingRefreshDelays(readAt, readAt + 3_000L))
        // 4_000ms: stages 1 and 2 passed -> still just ONE catch-up, not two.
        assertEquals(listOf(0L, 267L, 1_100L), remainingRefreshDelays(readAt, readAt + 4_000L))
    }

    @Test
    fun `remainingRefreshDelays past everything is a single immediate update`() {
        assertEquals(listOf(0L), remainingRefreshDelays(readAt, readAt + 5_100L))
        assertEquals(listOf(0L), remainingRefreshDelays(readAt, readAt + 60_000L))
    }

    @Test
    fun `remainingRefreshDelays is never negative even if the clock is before the mark`() {
        val r = remainingRefreshDelays(readAt, readAt - 500L)
        assertTrue(r.all { it >= 0L })
        assertEquals(4, r.size)
    }
}
