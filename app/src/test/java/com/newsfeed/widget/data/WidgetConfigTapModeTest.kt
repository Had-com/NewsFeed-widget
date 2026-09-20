package com.newsfeed.widget.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetConfigTapModeTest {

    @Test
    fun `config saved before tapMode existed reads as expand`() {
        // Default Json (no ignoreUnknownKeys), exactly what WidgetContent uses.
        val cfg = Json.decodeFromString<WidgetConfig>("""{"widgetId":7,"fontSize":1.5}""")
        assertEquals(TapMode.EXPAND.key, cfg.tapMode)
        assertEquals(TapMode.EXPAND, TapMode.fromKey(cfg.tapMode))
    }

    @Test
    fun `config that still carries focusBackgroundScale decodes with the default Json`() {
        val cfg = Json.decodeFromString<WidgetConfig>("""{"widgetId":7,"focusBackgroundScale":0.25}""")
        assertEquals(7, cfg.widgetId)
        assertEquals(TapMode.EXPAND.key, cfg.tapMode)
    }

    @Test
    fun `focus mode survives a JSON round trip`() {
        val json = Json.encodeToString(WidgetConfig(widgetId = 3, tapMode = TapMode.FOCUS.key))
        val back = Json.decodeFromString<WidgetConfig>(json)
        assertEquals(TapMode.FOCUS.key, back.tapMode)
        assertEquals(TapMode.FOCUS, TapMode.fromKey(back.tapMode))
    }

    @Test
    fun `fromKey maps known keys and degrades anything else to expand`() {
        assertEquals(TapMode.EXPAND, TapMode.fromKey("expand"))
        assertEquals(TapMode.FOCUS, TapMode.fromKey("focus"))
        assertEquals(TapMode.EXPAND, TapMode.fromKey(null))
        assertEquals(TapMode.EXPAND, TapMode.fromKey(""))
        assertEquals(TapMode.EXPAND, TapMode.fromKey("zoom"))
    }
}
