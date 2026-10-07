package com.newsfeed.widget.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetConfigGroupStoriesTest {

    @Test
    fun `config saved before groupStories existed reads as on`() {
        // Default Json (no ignoreUnknownKeys), exactly what WidgetContent uses.
        val cfg = Json.decodeFromString<WidgetConfig>("""{"widgetId":7,"fontSize":1.5,"tapMode":"focus"}""")
        assertTrue(cfg.groupStories)
    }

    @Test
    fun `the default is on and a default config does not write the field`() {
        val cfg = WidgetConfig(widgetId = 3)
        assertTrue(cfg.groupStories)
        // encodeDefaults is off in every Json this app uses, so a `true` is simply absent ...
        val json = Json.encodeToString(cfg)
        assertFalse(json.contains("groupStories"))
        // ... and reads back as true.
        assertTrue(Json.decodeFromString<WidgetConfig>(json).groupStories)
    }

    @Test
    fun `off survives a round trip with both the default and the lenient Json`() {
        val json = Json.encodeToString(WidgetConfig(widgetId = 3, groupStories = false))
        assertTrue(json.contains("\"groupStories\":false"))
        assertFalse(Json.decodeFromString<WidgetConfig>(json).groupStories)
        // WidgetConfigStore / ConfigBackup decode with ignoreUnknownKeys = true
        val lenient = Json { ignoreUnknownKeys = true }
        assertFalse(lenient.decodeFromString<WidgetConfig>(json).groupStories)
        assertEquals(false, lenient.decodeFromString<WidgetConfig>(lenient.encodeToString(WidgetConfig(widgetId = 1, groupStories = false))).groupStories)
    }

    @Test
    fun `turning it off then on again leaves a config identical to the default one`() {
        val on = WidgetConfig(widgetId = 3)
        val toggled = on.copy(groupStories = false).copy(groupStories = true)
        assertEquals(Json.encodeToString(on), Json.encodeToString(toggled))
    }
}
