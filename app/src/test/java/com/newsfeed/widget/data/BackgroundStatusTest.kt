package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BUG-048: tell the user when Android's battery rules stop refreshes while the screen is off. */
class BackgroundStatusTest {

    private fun st(exempt: Boolean?, restricted: Boolean?, maker: String = "Google") =
        BackgroundStatus(exempt, restricted, maker)

    @Test
    fun `exempt and not restricted is OK`() {
        assertEquals(BackgroundAdvice.OK, backgroundAdvice(st(true, false)))
    }

    @Test
    fun `exempt with no restriction info (older Android) is OK`() {
        assertEquals(BackgroundAdvice.OK, backgroundAdvice(st(true, null)))
    }

    @Test
    fun `not exempt needs the exemption`() {
        assertEquals(BackgroundAdvice.NEEDS_EXEMPTION, backgroundAdvice(st(false, false)))
        assertEquals(BackgroundAdvice.NEEDS_EXEMPTION, backgroundAdvice(st(false, null)))
    }

    @Test
    fun `restricted wins over everything else`() {
        assertEquals(BackgroundAdvice.RESTRICTED, backgroundAdvice(st(true, true)))
        assertEquals(BackgroundAdvice.RESTRICTED, backgroundAdvice(st(false, true)))
        assertEquals(BackgroundAdvice.RESTRICTED, backgroundAdvice(st(null, true)))
    }

    @Test
    fun `unreadable state is UNKNOWN, never a false alarm`() {
        assertEquals(BackgroundAdvice.UNKNOWN, backgroundAdvice(st(null, null)))
        assertEquals(BackgroundAdvice.UNKNOWN, backgroundAdvice(st(null, false)))
    }

    @Test
    fun `texts for each advice`() {
        assertEquals("Background refresh is allowed for NewsFeed.", backgroundAdviceText(BackgroundAdvice.OK, "Google"))
        assertEquals(
            "NewsFeed is restricted in the background. Open its battery setting and choose Unrestricted.",
            backgroundAdviceText(BackgroundAdvice.RESTRICTED, "Google"),
        )
        assertEquals("", backgroundAdviceText(BackgroundAdvice.UNKNOWN, "samsung"))
    }

    @Test
    fun `needs exemption text on a non-Samsung phone has no Samsung path`() {
        val t = backgroundAdviceText(BackgroundAdvice.NEEDS_EXEMPTION, "Google")
        assertEquals(
            "Android may pause NewsFeed when the screen is off, so the widget can go stale overnight. " +
                "Allow background activity for NewsFeed.", t,
        )
        assertFalse(t.contains("Samsung"))
        assertEquals(t, backgroundAdviceText(BackgroundAdvice.NEEDS_EXEMPTION, ""))
    }

    @Test
    fun `needs exemption text on Samsung adds the exact path in any casing`() {
        val path = "On Samsung: Settings → Battery → Background usage limits → Never sleeping apps → add NewsFeed. " +
            "Also set Settings → Apps → NewsFeed → Battery → Unrestricted."
        for (m in listOf("samsung", "Samsung", "SAMSUNG", " samsung ")) {
            val t = backgroundAdviceText(BackgroundAdvice.NEEDS_EXEMPTION, m)
            assertTrue(m, t.startsWith("Android may pause NewsFeed when the screen is off"))
            assertTrue(m, t.endsWith(path))
        }
    }

    @Test
    fun `restricted and OK texts do not change on Samsung`() {
        assertEquals(backgroundAdviceText(BackgroundAdvice.OK, "Google"), backgroundAdviceText(BackgroundAdvice.OK, "samsung"))
        assertEquals(backgroundAdviceText(BackgroundAdvice.RESTRICTED, "Google"), backgroundAdviceText(BackgroundAdvice.RESTRICTED, "samsung"))
    }

    @Test
    fun `yesNoUnknown words`() {
        assertEquals("yes", yesNoUnknown(true))
        assertEquals("no", yesNoUnknown(false))
        assertEquals("unknown", yesNoUnknown(null))
    }

    @Test
    fun `report lines name the state and the advice`() {
        assertEquals(
            listOf(
                "Battery optimization exempt: no",
                "Background restricted: unknown",
                "Manufacturer: samsung",
                "Background advice: NEEDS_EXEMPTION",
            ),
            backgroundReportLines(st(false, null, "samsung")),
        )
        assertEquals(
            listOf(
                "Battery optimization exempt: unknown",
                "Background restricted: unknown",
                "Manufacturer: unknown",
                "Background advice: UNKNOWN",
            ),
            backgroundReportLines(st(null, null, "")),
        )
    }
}
