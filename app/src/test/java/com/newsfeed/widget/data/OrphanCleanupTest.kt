package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrphanCleanupTest {

    @Test
    fun `orphans are known ids that are not live`() {
        assertEquals(setOf(1, 3), orphanedIds(known = setOf(1, 2, 3), live = setOf(2)))
    }

    @Test
    fun `nothing is orphaned when every known id is live or nothing is known`() {
        assertTrue(orphanedIds(known = setOf(2, 4), live = setOf(2, 4, 9)).isEmpty())
        assertTrue(orphanedIds(known = emptySet(), live = setOf(2)).isEmpty())
    }

    @Test
    fun `an empty live set never orphans anything`() {
        // Safety guard: getAppWidgetIds can be empty transiently (boot, provider lookup failure).
        // An unknown live set must delete nothing rather than every widget's saved state.
        assertTrue(orphanedIds(known = setOf(5, 6), live = emptySet()).isEmpty())
        assertTrue(orphanedIds(known = emptySet(), live = emptySet()).isEmpty())
    }

    @Test
    fun `a live id is never orphaned no matter how many stores know it`() {
        val known = setOf(7) + setOf(7) + setOf(7, 8)
        assertEquals(setOf(8), orphanedIds(known, live = setOf(7)))
    }

    @Test
    fun `widget id is parsed from a config key`() {
        assertEquals(42, widgetIdFromConfigKey("widget_42"))
        assertNull(widgetIdFromConfigKey("widget_"))
        assertNull(widgetIdFromConfigKey("widget_x"))
        assertNull(widgetIdFromConfigKey("articles_json"))
    }

    @Test
    fun `widget id is parsed from a Glance state file name`() {
        assertEquals(17, widgetIdFromStateFileName("appWidget-17.preferences_pb"))
        assertNull(widgetIdFromStateFileName("appWidget-17.preferences_pb.tmp"))
        assertNull(widgetIdFromStateFileName("appWidget-.preferences_pb"))
        assertNull(widgetIdFromStateFileName("newsfeed_config.preferences_pb"))
    }

    @Test
    fun `widget id is parsed from a Glance layout cache file name`() {
        assertEquals(16, widgetIdFromLayoutFileName("appWidgetLayout-16.preferences_pb"))
        assertEquals(16, widgetIdFromLayoutFileName("appWidgetLayout-16"))
        assertNull(widgetIdFromLayoutFileName("appWidgetLayout-"))
        assertNull(widgetIdFromLayoutFileName("appWidgetLayout-x.preferences_pb"))
        assertNull(widgetIdFromLayoutFileName("appWidgetLayout-16.tmp"))
        assertNull(widgetIdFromLayoutFileName("../appWidgetLayout-16"))
        assertNull(widgetIdFromLayoutFileName("appWidgetLayout-1/../2"))
        assertNull(widgetIdFromLayoutFileName("appWidget-16.preferences_pb"))
    }

    @Test
    fun `either Glance file kind yields its id, anything else null`() {
        assertEquals(3, widgetIdFromGlanceFileName("appWidget-3.preferences_pb"))
        assertEquals(4, widgetIdFromGlanceFileName("appWidgetLayout-4.preferences_pb"))
        assertNull(widgetIdFromGlanceFileName("newsfeed_config.preferences_pb"))
    }

    @Test
    fun `layout file ids of live widgets are never orphaned`() {
        val known = setOf(16, 18, 19) // ids seen from state/layout file scan
        assertEquals(setOf(18, 19), orphanedIds(known, live = setOf(16)))
        assertTrue(orphanedIds(known, live = emptySet()).isEmpty())
    }
}
