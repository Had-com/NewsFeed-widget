package com.newsfeed.widget.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraceRefreshRegistryTest {

    @Test
    fun `first register wins and a second one for the same key is refused`() {
        val r = GraceRefreshRegistry()
        val key = GraceRefreshRegistry.key("w1", "a", 1_000L)
        assertTrue(r.tryRegister(key))
        assertFalse(r.tryRegister(key))
    }

    @Test
    fun `a key can be registered again after it is released`() {
        val r = GraceRefreshRegistry()
        val key = GraceRefreshRegistry.key("w1", "a", 1_000L)
        assertTrue(r.tryRegister(key))
        r.release(key)
        assertTrue(r.tryRegister(key))
    }

    @Test
    fun `a different readAt, article or widget is a different key`() {
        val r = GraceRefreshRegistry()
        assertTrue(r.tryRegister(GraceRefreshRegistry.key("w1", "a", 1_000L)))
        assertTrue(r.tryRegister(GraceRefreshRegistry.key("w1", "a", 2_000L)))
        assertTrue(r.tryRegister(GraceRefreshRegistry.key("w1", "b", 1_000L)))
        assertTrue(r.tryRegister(GraceRefreshRegistry.key("w2", "a", 1_000L)))
    }

    @Test
    fun `releasing an unknown key is harmless`() {
        val r = GraceRefreshRegistry()
        r.release("nope")
        assertTrue(r.tryRegister("nope"))
    }

    @Test
    fun `isDissolving is true only for a read article past the first dissolve boundary`() {
        val a = ArticleItem(id = "a", feedId = "f", feedName = "F", title = "T",
            publishedAt = 0L, isRead = true, readAt = 1_000L)
        assertFalse(isDissolving(a, 1_000L + 2_499L))
        assertTrue(isDissolving(a, 1_000L + 2_500L))
        assertTrue(isDissolving(a, 1_000L + 4_900L))
        assertFalse(isDissolving(a.copy(isRead = false), 1_000L + 3_000L))
        assertFalse(isDissolving(a.copy(readAt = null), 1_000L + 3_000L))
    }
}
