package com.newsfeed.widget.glance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetThemesTest {

    @Test
    fun `parseHexColor parses a 6-digit hex with leading hash`() {
        val color = WidgetThemes.parseHexColor("#FF0000")
        assertEquals(0xFFFF0000.toInt(), color?.toArgb())
    }

    @Test
    fun `parseHexColor parses a 6-digit hex without leading hash`() {
        val color = WidgetThemes.parseHexColor("00FF00")
        assertEquals(0xFF00FF00.toInt(), color?.toArgb())
    }

    @Test
    fun `parseHexColor parses an 8-digit ARGB hex`() {
        val color = WidgetThemes.parseHexColor("#800000FF")
        assertEquals(0x800000FF.toInt(), color?.toArgb())
    }

    @Test
    fun `parseHexColor returns null for the wrong length`() {
        assertNull(WidgetThemes.parseHexColor("#ABC"))
        assertNull(WidgetThemes.parseHexColor(""))
    }

    @Test
    fun `parseHexColor returns null for non-hex characters`() {
        assertNull(WidgetThemes.parseHexColor("#GGGGGG"))
    }

    @Test
    fun `rawColorSchemeFor custom light uses background and font colors as picked`() {
        val scheme = WidgetThemes.rawColorSchemeFor(
            theme = "custom", variant = "light",
            customFont = "#111111", customBackground = "#EEEEEE",
        )
        assertEquals(WidgetThemes.parseHexColor("#EEEEEE"), scheme.background)
        assertEquals(WidgetThemes.parseHexColor("#111111"), scheme.onSurface)
    }

    @Test
    fun `rawColorSchemeFor custom dark swaps background and font colors`() {
        val scheme = WidgetThemes.rawColorSchemeFor(
            theme = "custom", variant = "dark",
            customFont = "#111111", customBackground = "#EEEEEE",
        )
        assertEquals(WidgetThemes.parseHexColor("#111111"), scheme.background)
        assertEquals(WidgetThemes.parseHexColor("#EEEEEE"), scheme.onSurface)
    }

    @Test
    fun `rawColorSchemeFor custom falls back to defaults for invalid hex`() {
        val scheme = WidgetThemes.rawColorSchemeFor(
            theme = "custom", variant = "light",
            customFont = "not-a-color", customBackground = "also-not-a-color",
        )
        // Falls back to the same defaults FeedConfig.kt's WidgetConfig fields use.
        assertEquals(WidgetThemes.parseHexColor("#FFFFFF"), scheme.background)
        assertEquals(WidgetThemes.parseHexColor("#1B1F27"), scheme.onSurface)
    }

    @Test
    fun `surfaceColorFor custom matches rawColorSchemeFor's surface`() {
        val surface = WidgetThemes.surfaceColorFor(
            theme = "custom", variant = "light",
            customFont = "#111111", customBackground = "#EEEEEE",
        )
        assertEquals(WidgetThemes.parseHexColor("#EEEEEE"), surface)
    }

    @Test
    fun `existing themes are unaffected by the new parameters' defaults`() {
        // A caller that never passes customFont/customBackground (every pre-existing call
        // site, until Tasks 3-4 update them) must still resolve exactly as before for any
        // non-custom theme - the new parameters must be inert unless theme == "custom".
        val before = WidgetThemes.rawColorSchemeFor("glamer", "dark")
        val after = WidgetThemes.rawColorSchemeFor("glamer", "dark", "#000000", "#FFFFFF")
        assertEquals(before.background, after.background)
        assertEquals(before.onSurface, after.onSurface)
    }
}
