package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendConfirmationTest {

    private fun all(previewed: Boolean, addresses: Boolean) =
        sendConfirmationParagraphs(previewedAlready = previewed, includeAddresses = addresses)

    @Test fun `the confirmation never tells where the report goes`() {
        for (previewed in listOf(false, true)) for (addresses in listOf(false, true)) {
            val text = all(previewed, addresses).joinToString("\n") { it.text }.lowercase()
            assertFalse("where it goes", text.contains("where it goes"))
            assertFalse("https", text.contains("https"))
            assertFalse("host name", text.contains(".org") || text.contains(".com") || text.contains("duckdns"))
        }
    }

    @Test fun `the feed address warning appears only when the checkbox is on`() {
        val off = all(previewed = false, addresses = false)
        assertTrue(off.none { it.isWarning })
        assertFalse(off.joinToString("\n") { it.text }.lowercase().contains("feed address"))

        val on = all(previewed = false, addresses = true)
        val warnings = on.filter { it.isWarning }
        assertEquals(1, warnings.size)
        assertEquals(
            "Warning: your own feed names and addresses are included in this report (passwords and tokens are removed). " +
                "Check the preview first if any of your feeds is private.",
            warnings[0].text,
        )
    }

    @Test fun `the warning says passwords and tokens are removed`() {
        val warning = all(previewed = true, addresses = true).first { it.isWarning }.text
        assertTrue(warning.contains("passwords and tokens are removed"))
    }

    @Test fun `the first paragraph depends on whether the report was already previewed`() {
        assertTrue(all(previewed = true, addresses = false)[0].text.contains("you just previewed"))
        assertTrue(all(previewed = false, addresses = false)[0].text.contains("Preview report"))
    }

    @Test fun `the first paragraph says only the app's own feeds are named`() {
        for (previewed in listOf(false, true)) for (addresses in listOf(false, true)) {
            assertTrue(all(previewed, addresses)[0].text.contains("the names of the app's own feeds (not yours)"))
        }
    }

    @Test fun `the report contents are always described and nothing is sent automatically`() {
        for (previewed in listOf(false, true)) for (addresses in listOf(false, true)) {
            val paragraphs = all(previewed, addresses)
            assertTrue(paragraphs[0].text.contains("app version"))
            assertTrue(paragraphs[0].text.contains("your note"))
            assertTrue(paragraphs.last().text.contains("Nothing is sent automatically"))
            assertFalse(paragraphs.last().isWarning)
        }
    }

    @Test fun `without the checkbox the first paragraph stays free of any feed address wording`() {
        val first = all(previewed = false, addresses = false)[0].text
        assertFalse(first.contains("address", ignoreCase = true))
    }
}
