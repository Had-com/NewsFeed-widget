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
}
