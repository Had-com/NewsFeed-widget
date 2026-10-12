package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** The app blocks cleartext traffic, so every http:// feed address is requested as https://. */
class FeedUrlTest {

    @Test
    fun `http is upgraded to https`() {
        assertEquals("https://www.thehindu.com/sci-tech/technology/internet/?service=rss",
            upgradeToHttps("http://www.thehindu.com/sci-tech/technology/internet/?service=rss"))
    }

    @Test
    fun `https is untouched`() {
        assertEquals("https://a.com/rss", upgradeToHttps("https://a.com/rss"))
    }

    @Test
    fun `scheme case is ignored and the rest keeps its case`() {
        assertEquals("https://A.com/Feed", upgradeToHttps("HTTP://A.com/Feed"))
        assertEquals("https://a.com", upgradeToHttps("HtTp://a.com"))
    }

    @Test
    fun `port query and fragment are kept`() {
        assertEquals("https://a.com:8080/x?y=1&z=http://b.com#frag", upgradeToHttps("http://a.com:8080/x?y=1&z=http://b.com#frag"))
    }

    @Test
    fun `only the scheme is changed, http inside the address stays`() {
        assertEquals("https://a.com/redirect?to=http://b.com", upgradeToHttps("http://a.com/redirect?to=http://b.com"))
    }

    @Test
    fun `other schemes are never touched`() {
        assertEquals("ftp://a.com/feed", upgradeToHttps("ftp://a.com/feed"))
        assertEquals("file:///sdcard/feed.xml", upgradeToHttps("file:///sdcard/feed.xml"))
        assertEquals("httpx://a.com", upgradeToHttps("httpx://a.com"))
        assertEquals("plant://0", upgradeToHttps("plant://0"))
    }

    @Test
    fun `malformed and empty input is returned as is and never throws`() {
        assertEquals("", upgradeToHttps(""))
        assertEquals("   ", upgradeToHttps("   "))
        assertEquals("http:/a.com", upgradeToHttps("http:/a.com"))
        assertEquals("http//a.com", upgradeToHttps("http//a.com"))
        assertEquals("a.com/rss", upgradeToHttps("a.com/rss"))
        assertEquals("@N12_News", upgradeToHttps("@N12_News"))
        assertEquals("https://", upgradeToHttps("http://"))
        assertEquals("http", upgradeToHttps("http"))
    }

    @Test
    fun `whitespace around the address is kept, only an address that starts with http is upgraded`() {
        assertEquals(" http://a.com", upgradeToHttps(" http://a.com"))
    }

    @Test
    fun `isUpgradedFeedUrl tells whether the request address differs from the stored one`() {
        assertTrue(isUpgradedFeedUrl("http://a.com/rss"))
        assertTrue(isUpgradedFeedUrl("HTTP://a.com/rss"))
        assertFalse(isUpgradedFeedUrl("https://a.com/rss"))
        assertFalse(isUpgradedFeedUrl(""))
        assertFalse(isUpgradedFeedUrl("ftp://a.com"))
    }

    @Test
    fun `requestUrlFor trims and upgrades the stored address`() {
        assertEquals("https://a.com/rss", requestUrlFor("http://a.com/rss"))
        assertEquals("https://a.com/rss", requestUrlFor("  http://a.com/rss \n"))
        assertEquals("https://a.com/rss", requestUrlFor("https://a.com/rss"))
        assertEquals("https://t.me/s/N12_News", requestUrlFor("https://t.me/s/N12_News"))
    }

    // -- failure detail of an upgraded feed --

    private fun classified(error: Throwable) = classifyFeedLog(0, error)

    @Test
    fun `an ssl or connect failure of an upgraded feed says so`() {
        val ssl = describeUpgradeFailure("http://a.com/rss", classified(SSLHandshakeException("Handshake failed")))
        assertEquals(FeedLogStatus.NETWORK_ERROR, ssl.status)
        assertEquals("https upgrade of http feed: SSLHandshakeException: Handshake failed", ssl.detail)
        val conn = describeUpgradeFailure("http://a.com/rss", classified(ConnectException("Connection refused")))
        assertTrue(conn.detail, conn.detail.startsWith("https upgrade of http feed: ConnectException"))
    }

    @Test
    fun `a feed that was not upgraded keeps its detail`() {
        val c = classified(SSLHandshakeException("x"))
        assertEquals(c, describeUpgradeFailure("https://a.com/rss", c))
    }

    @Test
    fun `http errors, dns failures, successes and not-a-feed are not blamed on the upgrade`() {
        val http = classified(FeedHttpException(403))
        assertEquals(http, describeUpgradeFailure("http://a.com/rss", http))
        val dns = classified(UnknownHostException("a.com"))
        assertEquals(dns, describeUpgradeFailure("http://a.com/rss", dns))
        val ok = classifyFeedLog(5, null)
        assertEquals(ok, describeUpgradeFailure("http://a.com/rss", ok))
        val nf = classified(NotAFeedException())
        assertEquals(nf, describeUpgradeFailure("http://a.com/rss", nf))
        val other = classified(IOException("boom"))
        assertEquals(FeedLogStatus.NETWORK_ERROR, other.status)
    }

    @Test
    fun `the detail stays within the log limit`() {
        val long = classified(SSLHandshakeException("x".repeat(300)))
        assertTrue(describeUpgradeFailure("http://a.com", long).detail.length <= REFRESH_LOG_MAX_DETAIL)
    }
}
