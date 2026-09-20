package com.newsfeed.widget.glance

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.newsfeed.widget.data.TapMode
import com.newsfeed.widget.data.WidgetStateKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `resetTapState clears exactly the four transient keys and nothing else`() {
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
        assertFalse(prefs.contains(WidgetStateKey.focusScale))
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
}
