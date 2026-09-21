package com.newsfeed.widget.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraceRefreshPolicyTest {

    @Test
    fun `only the Unread only filter key schedules grace refreshes`() {
        assertTrue(shouldScheduleGraceRefresh(FilterMode.UNREAD.key))
        assertFalse(shouldScheduleGraceRefresh(FilterMode.ALL.key))
        assertFalse(shouldScheduleGraceRefresh(FilterMode.READ.key))
    }

    @Test
    fun `null or unknown filter key does not match the Unread only key`() {
        assertFalse(shouldScheduleGraceRefresh(null))
        assertFalse(shouldScheduleGraceRefresh(""))
        assertFalse(shouldScheduleGraceRefresh("garbage"))
        assertFalse(shouldScheduleGraceRefresh("UNREAD"))
    }

    private fun configJson(filter: String) =
        Json.encodeToString(WidgetConfig(widgetId = 1, filter = filter))

    @Test
    fun `saved config json is read for its filter`() {
        assertTrue(shouldScheduleGraceRefreshForConfig(configJson("unread")))
        assertFalse(shouldScheduleGraceRefreshForConfig(configJson("all")))
        assertFalse(shouldScheduleGraceRefreshForConfig(configJson("read")))
    }

    @Test
    fun `missing or undecodable config falls back to scheduling`() {
        assertTrue(shouldScheduleGraceRefreshForConfig(null))
        assertTrue(shouldScheduleGraceRefreshForConfig("not json"))
        assertTrue(shouldScheduleGraceRefreshForConfig("{\"widgetId\":"))
    }
}
