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

    // -- attached picture --

    private val pictureWarning = "A picture is attached and will be sent too. Check it first: it may show private information."

    @Test fun `without a picture there is no picture paragraph`() {
        for (previewed in listOf(false, true)) for (addresses in listOf(false, true)) {
            assertFalse(all(previewed, addresses).any { it.text.contains("picture", ignoreCase = true) })
            assertEquals(all(previewed, addresses), sendConfirmationParagraphs(previewed, addresses, hasPicture = false))
        }
    }

    @Test fun `with a picture a warning paragraph is added after the contents and before nothing is sent automatically`() {
        for (previewed in listOf(false, true)) for (addresses in listOf(false, true)) {
            val p = sendConfirmationParagraphs(previewed, addresses, hasPicture = true)
            val idx = p.indexOfFirst { it.text == pictureWarning }
            assertTrue("picture paragraph present", idx > 0)
            assertTrue(p[idx].isWarning)
            assertTrue(p[0].text.startsWith("What is sent:"))
            assertTrue(idx > 0)
            assertEquals(p.size - 2, idx)
            assertTrue(p.last().text.contains("Nothing is sent automatically"))
            assertFalse(p.last().isWarning)
        }
    }

    @Test fun `the picture warning comes after the feed address warning when both apply`() {
        val p = sendConfirmationParagraphs(previewedAlready = false, includeAddresses = true, hasPicture = true)
        assertEquals(4, p.size)
        assertTrue(p[1].text.startsWith("Warning: your own feed names"))
        assertEquals(pictureWarning, p[2].text)
        assertEquals(2, p.count { it.isWarning })
    }

    @Test fun `the picture paragraph does not tell where the report goes`() {
        val text = sendConfirmationParagraphs(previewedAlready = true, includeAddresses = false, hasPicture = true).joinToString(" ") { it.text }.lowercase()
        assertFalse(text.contains("https"))
        assertFalse(text.contains("where it goes"))
    }
}
