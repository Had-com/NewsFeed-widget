package com.newsfeed.widget.glance

import androidx.glance.material3.ColorProviders as buildColorProviders
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
    fun `rawColorSchemeFor custom light derives primary, primaryContainer and surfaceVariant from the two picked colors`() {
        val scheme = WidgetThemes.rawColorSchemeFor(
            theme = "custom", variant = "light",
            customFont = "#111111", customBackground = "#EEEEEE",
        )
        // Light variant: resolvedText is the font color, resolvedBackground is the background color.
        val resolvedText = WidgetThemes.parseHexColor("#111111")!!
        val resolvedBackground = WidgetThemes.parseHexColor("#EEEEEE")!!
        assertEquals(resolvedText, scheme.primary)
        assertEquals(resolvedBackground, scheme.onPrimary)
        assertEquals(resolvedText.copy(alpha = 0.15f), scheme.primaryContainer)
        assertEquals(resolvedText, scheme.onPrimaryContainer)
        assertEquals(resolvedText.copy(alpha = 0.12f), scheme.surfaceVariant)
    }

    @Test
    fun `rawColorSchemeFor custom dark derives primary, primaryContainer and surfaceVariant from the swapped colors`() {
        val scheme = WidgetThemes.rawColorSchemeFor(
            theme = "custom", variant = "dark",
            customFont = "#111111", customBackground = "#EEEEEE",
        )
        // Dark variant swaps background/font, so resolvedText is the background color here.
        val resolvedText = WidgetThemes.parseHexColor("#EEEEEE")!!
        val resolvedBackground = WidgetThemes.parseHexColor("#111111")!!
        assertEquals(resolvedText, scheme.primary)
        assertEquals(resolvedBackground, scheme.onPrimary)
        assertEquals(resolvedText.copy(alpha = 0.15f), scheme.primaryContainer)
        assertEquals(resolvedText, scheme.onPrimaryContainer)
        assertEquals(resolvedText.copy(alpha = 0.12f), scheme.surfaceVariant)
    }

    @Test
    fun `colorProvidersFor custom wraps the same scheme rawColorSchemeFor produces`() {
        // ColorProvider.getColor() needs a real Context (no Robolectric in this project - see
        // parseHexColor's comment), so we can't call it here. Instead compare the ColorProviders
        // objects directly: androidx.glance.color.ColorProviders overrides equals() to compare
        // every field, and androidx.glance.material3.ColorProviders(light, dark) turns each
        // ColorScheme slot into a plain data-class ColorProvider wrapping that slot's color long
        // - so two calls built from equal-valued schemes compare equal without touching Android.
        val actual = WidgetThemes.colorProvidersFor(
            theme = "custom", variant = "light",
            customFont = "#111111", customBackground = "#EEEEEE",
        )
        val expectedScheme = WidgetThemes.rawColorSchemeFor(
            theme = "custom", variant = "light",
            customFont = "#111111", customBackground = "#EEEEEE",
        )
        val expected = buildColorProviders(light = expectedScheme, dark = expectedScheme)
        assertEquals(expected, actual)
    }

    @Test
    fun `surfaceColorFor custom dark matches rawColorSchemeFor's surface after the swap`() {
        val surface = WidgetThemes.surfaceColorFor(
            theme = "custom", variant = "dark",
            customFont = "#111111", customBackground = "#EEEEEE",
        )
        // Dark variant swaps background/font, so surface becomes the font color.
        assertEquals(WidgetThemes.parseHexColor("#111111"), surface)
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
