package com.newsfeed.widget.glance

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.TapMode
import com.newsfeed.widget.data.WidgetStateKey
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapRoutingTest {

    // ---- routing matrix: 2 modes x (description / no description) ----

    @Test
    fun `expand mode with a description toggles expand`() =
        assertEquals(TapAction.TOGGLE_EXPAND, tapActionFor(TapMode.EXPAND, hasDescription = true))

    @Test
    fun `expand mode without a description is a no-op tap`() =
        assertEquals(TapAction.NO_OP, tapActionFor(TapMode.EXPAND, hasDescription = false))

    @Test
    fun `focus mode always sets focus even without a description`() {
        assertEquals(TapAction.SET_FOCUS, tapActionFor(TapMode.FOCUS, hasDescription = true))
        assertEquals(TapAction.SET_FOCUS, tapActionFor(TapMode.FOCUS, hasDescription = false))
    }

    // ---- mode-switch detection ----

    @Test
    fun `tapModeChanged compares modes not raw strings`() {
        assertTrue(tapModeChanged("expand", "focus"))
        assertTrue(tapModeChanged("focus", "expand"))
        assertFalse(tapModeChanged("expand", "expand"))
        assertFalse(tapModeChanged("focus", "focus"))
        // an unknown stored value is treated as expand, so expand -> "junk" is not a switch
        assertFalse(tapModeChanged("junk", "expand"))
    }

    // ---- resetTapState ----

    @Test
    fun `resetTapState clears the three transient id keys and keeps the focus zoom`() {
        val prefs = mutablePreferencesOf().also {
            it[WidgetStateKey.expandedArticleId] = "a"
            it[WidgetStateKey.focusedArticleId] = "b"
            it[WidgetStateKey.lastTappedArticleId] = "c"
            it[WidgetStateKey.focusScale] = 2.0f
            it[WidgetStateKey.articles] = """[{"id":"a"}]"""
            it[WidgetStateKey.configJson] = """{"widgetId":1}"""
            it[WidgetStateKey.fullArticleId] = "a"
            it[WidgetStateKey.visibleArticleCount] = 30
        }
        resetTapState(prefs)
        assertFalse(prefs.contains(WidgetStateKey.expandedArticleId))
        assertFalse(prefs.contains(WidgetStateKey.focusedArticleId))
        assertFalse(prefs.contains(WidgetStateKey.lastTappedArticleId))
        // BUG-024: focusScale is a persistent per-widget preference, so a tap-mode switch no
        // longer wipes it (this assertion used to be assertFalse(contains(focusScale))).
        assertEquals(2.0f, prefs[WidgetStateKey.focusScale]!!, 0.0f)
        // read flags live inside articles_json: it must be byte-for-byte untouched
        assertEquals("""[{"id":"a"}]""", prefs[WidgetStateKey.articles])
        assertEquals("""{"widgetId":1}""", prefs[WidgetStateKey.configJson])
        assertEquals("a", prefs[WidgetStateKey.fullArticleId])
        assertEquals(30, prefs[WidgetStateKey.visibleArticleCount])
    }

    @Test
    fun `resetTapState on empty prefs is harmless`() {
        val prefs = mutablePreferencesOf()
        resetTapState(prefs)
        assertTrue(prefs.asMap().isEmpty())
    }

    // ---- rowFontScale: only the focused row in focus mode is ever scaled ----

    @Test
    fun `focused row in focus mode gets the focus scale`() =
        assertEquals(2.0f, rowFontScale(isFocusMode = true, isFocusedRow = true, anyFocused = true, focusScale = 2.0f), 0.0f)

    @Test
    fun `non-focused rows are exactly normal size in every state`() {
        // something else is focused
        assertEquals(1.0f, rowFontScale(true, isFocusedRow = false, anyFocused = true, focusScale = 2.5f), 0.0f)
        // nothing focused
        assertEquals(1.0f, rowFontScale(true, isFocusedRow = false, anyFocused = false, focusScale = 2.5f), 0.0f)
        assertEquals(1.0f, rowFontScale(true, isFocusedRow = true, anyFocused = false, focusScale = 2.5f), 0.0f)
    }

    @Test
    fun `expand mode ignores a stale focused id`() =
        assertEquals(1.0f, rowFontScale(isFocusMode = false, isFocusedRow = true, anyFocused = true, focusScale = 2.5f), 0.0f)

    // ---- applyFocusTap: the focus zoom is a persistent preference (BUG-024) ----

    private fun article(id: String, isRead: Boolean = false, readAt: Long? = null) = ArticleItem(
        id = id, feedId = "f", feedName = "Feed", title = "T$id",
        publishedAt = 1L, isRead = isRead, readAt = readAt,
    )

    private fun focusPrefs(focused: String? = null, scale: Float? = null, vararg articles: ArticleItem) =
        mutablePreferencesOf().also {
            if (focused != null) it[WidgetStateKey.focusedArticleId] = focused
            if (scale != null) it[WidgetStateKey.focusScale] = scale
            if (articles.isNotEmpty()) it[WidgetStateKey.articles] = Json.encodeToString(articles.toList())
        }

    private fun storedArticles(prefs: androidx.datastore.preferences.core.Preferences) =
        Json.decodeFromString<List<ArticleItem>>(prefs[WidgetStateKey.articles]!!)

    @Test
    fun `focusing a different article keeps a previously adjusted zoom`() {
        val prefs = focusPrefs("a", 1.85f, article("a"), article("b"))
        applyFocusTap(prefs, "b", now = 100L)
        assertEquals("b", prefs[WidgetStateKey.focusedArticleId])
        assertEquals(1.85f, prefs[WidgetStateKey.focusScale]!!, 0.0f)
    }

    @Test
    fun `re-tapping the focused article clears focus but keeps the zoom`() {
        val prefs = focusPrefs("a", 0.9f, article("a"))
        val losing = applyFocusTap(prefs, "a", now = 100L)
        assertNull(losing)
        assertEquals("", prefs[WidgetStateKey.focusedArticleId])
        assertEquals(0.9f, prefs[WidgetStateKey.focusScale]!!, 0.0f)
    }

    @Test
    fun `first focus from none keeps an adjusted zoom and does not invent one`() {
        val withScale = focusPrefs(scale = 2.2f, articles = arrayOf(article("a")))
        applyFocusTap(withScale, "a", now = 100L)
        assertEquals("a", withScale[WidgetStateKey.focusedArticleId])
        assertEquals(2.2f, withScale[WidgetStateKey.focusScale]!!, 0.0f)

        val noScale = focusPrefs(articles = arrayOf(article("a")))
        applyFocusTap(noScale, "a", now = 100L)
        assertEquals("a", noScale[WidgetStateKey.focusedArticleId])
        assertFalse(noScale.contains(WidgetStateKey.focusScale))
    }

    @Test
    fun `zoom survives a whole focus, switch, clear, refocus sequence and a mode switch`() {
        val prefs = focusPrefs(scale = 1.55f, articles = arrayOf(article("a"), article("b")))
        applyFocusTap(prefs, "a", 1L)
        applyFocusTap(prefs, "b", 2L)
        applyFocusTap(prefs, "b", 3L)
        applyFocusTap(prefs, "a", 4L)
        resetTapState(prefs)
        assertEquals(1.55f, prefs[WidgetStateKey.focusScale]!!, 0.0f)
    }

    @Test
    fun `moving focus flags only the unread article losing focus and returns its id`() {
        val prefs = focusPrefs("a", articles = arrayOf(article("a"), article("b")))
        val losing = applyFocusTap(prefs, "b", now = 100L)
        assertEquals("a", losing)
        val list = storedArticles(prefs)
        assertTrue(list.first { it.id == "a" }.isRead)
        assertEquals(100L, list.first { it.id == "a" }.readAt)
        assertFalse(list.first { it.id == "b" }.isRead)
    }

    @Test
    fun `losing focus on an already-read article keeps its readAt and returns null`() {
        val prefs = focusPrefs("a", articles = arrayOf(article("a", isRead = true, readAt = 5L), article("b")))
        val before = prefs[WidgetStateKey.articles]
        assertNull(applyFocusTap(prefs, "b", now = 100L))
        assertEquals(before, prefs[WidgetStateKey.articles])
    }

    @Test
    fun `focus tap with unreadable articles json still moves focus and marks nothing`() {
        val prefs = focusPrefs("a")
        prefs[WidgetStateKey.articles] = "not json"
        assertNull(applyFocusTap(prefs, "b", now = 100L))
        assertEquals("b", prefs[WidgetStateKey.focusedArticleId])
        assertEquals("not json", prefs[WidgetStateKey.articles])
    }

    // ---- header -/+ placeholders (BUG-032: the header must keep the same views focused or not) ----

    @Test
    fun `header buttons carry the real step only while a row is focused`() {
        assertEquals(AdjustFocusScaleCallback.STEP, AdjustFocusScaleCallback.headerStep(true, AdjustFocusScaleCallback.STEP), 0f)
        assertEquals(-AdjustFocusScaleCallback.STEP, AdjustFocusScaleCallback.headerStep(true, -AdjustFocusScaleCallback.STEP), 0f)
        assertEquals(0f, AdjustFocusScaleCallback.headerStep(false, AdjustFocusScaleCallback.STEP), 0f)
        assertEquals(0f, AdjustFocusScaleCallback.headerStep(false, -AdjustFocusScaleCallback.STEP), 0f)
    }

    @Test
    fun `a zero step is inert and a real step is not`() {
        assertTrue(AdjustFocusScaleCallback.isInertStep(AdjustFocusScaleCallback.headerStep(false, AdjustFocusScaleCallback.STEP)))
        assertFalse(AdjustFocusScaleCallback.isInertStep(AdjustFocusScaleCallback.STEP))
        assertFalse(AdjustFocusScaleCallback.isInertStep(-AdjustFocusScaleCallback.STEP))
        // and a zero step would leave the stored zoom exactly where it is anyway
        assertEquals(1.4f, AdjustFocusScaleCallback.adjustedScale(1.4f, 0f), 0f)
    }

    // ---- adjustedScale: the -/+ buttons ----

    @Test
    fun `adjustedScale steps by the delta`() {
        assertEquals(1.40f, AdjustFocusScaleCallback.adjustedScale(1.25f, AdjustFocusScaleCallback.STEP), 0.0001f)
        assertEquals(1.10f, AdjustFocusScaleCallback.adjustedScale(1.25f, -AdjustFocusScaleCallback.STEP), 0.0001f)
    }

    @Test
    fun `adjustedScale clamps at both ends`() {
        assertEquals(AdjustFocusScaleCallback.MAX_SCALE, AdjustFocusScaleCallback.adjustedScale(2.45f, 0.15f), 0.0f)
        assertEquals(AdjustFocusScaleCallback.MAX_SCALE, AdjustFocusScaleCallback.adjustedScale(2.5f, 0.15f), 0.0f)
        assertEquals(AdjustFocusScaleCallback.MIN_SCALE, AdjustFocusScaleCallback.adjustedScale(0.8f, -0.15f), 0.0f)
        assertEquals(AdjustFocusScaleCallback.MIN_SCALE, AdjustFocusScaleCallback.adjustedScale(0.75f, -0.15f), 0.0f)
    }
}
