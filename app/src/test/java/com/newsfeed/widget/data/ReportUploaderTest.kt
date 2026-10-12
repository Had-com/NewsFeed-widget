package com.newsfeed.widget.data

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ReportUploaderTest {

    private val cid = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val now = 1_700_001_000_000L

    private fun payload(report: String, name: String = "1.0") = buildUploadPayload(report, cid, 933, name, now)
    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    // -- payload --

    @Test
    fun `payload carries exactly the contract fields`() {
        val o = JSONObject(payload("hello"))
        assertEquals(setOf("schema", "clientId", "appVersionCode", "appVersionName", "createdAt", "report"), o.keys().asSequence().toSet())
        assertEquals(1, o.getInt("schema"))
        assertEquals(cid, o.getString("clientId"))
        assertEquals(933, o.getInt("appVersionCode"))
        assertEquals("1.0", o.getString("appVersionName"))
        assertEquals(now, o.getLong("createdAt"))
        assertEquals("hello", o.getString("report"))
    }

    @Test
    fun `quotes backslashes newlines tabs hebrew and emoji survive a round trip`() {
        val text = "He said \"hi\" \\ path\r\nline2\ttab\nשלום עולם — ידיעות 📰 𝄞 end"
        assertEquals(text, JSONObject(payload(text)).getString("report"))
    }

    @Test
    fun `disallowed control characters are stripped but newline return and tab stay`() {
        val dirty = "a\u0000b\u0001c\u0007d\u000Be\u000Cf\u001Fg\u007Fh\u0085i\n\r\tj"
        val out = JSONObject(payload(dirty)).getString("report")
        assertEquals("abcdefghi\n\r\tj", out)
    }

    @Test
    fun `lone surrogates are removed and pairs kept`() {
        val s = "a\uD83D b \uDCF0 c 📰 d"
        assertEquals("a b  c 📰 d", sanitizeUploadText(s))
    }

    @Test
    fun `version name is clamped to 40 characters without splitting a pair`() {
        assertEquals("v".repeat(40), JSONObject(payload("x", "v".repeat(100))).getString("appVersionName"))
        val emoji = "a".repeat(39) + "📰📰"
        val n = JSONObject(payload("x", emoji)).getString("appVersionName")
        assertEquals("a".repeat(39), n)
        assertEquals("1.0", JSONObject(payload("x", "1.0")).getString("appVersionName"))
    }

    @Test
    fun `a report within the limits is sent unchanged`() {
        val r = "x".repeat(UPLOAD_MAX_REPORT_CHARS)
        assertEquals(r, JSONObject(payload(r)).getString("report"))
    }

    @Test
    fun `a report over 60000 characters is cut in the middle with a marker`() {
        val r = "HEAD" + "m".repeat(70_000) + "TAIL"
        val out = JSONObject(payload(r)).getString("report")
        assertTrue(out.length <= UPLOAD_MAX_REPORT_CHARS)
        assertTrue(out.startsWith("HEAD"))
        assertTrue(out.endsWith("TAIL"))
        assertTrue(out.contains(UPLOAD_TRUNCATION_MARKER))
    }

    @Test
    fun `the cut never splits an emoji`() {
        val r = "📰".repeat(40_000)
        val out = JSONObject(payload(r)).getString("report")
        assertEquals(out, sanitizeUploadText(out)) // no lone surrogate left
        assertTrue(out.length <= UPLOAD_MAX_REPORT_CHARS)
    }

    @Test
    fun `body stays within 80000 bytes for hebrew text`() {
        // 59,000 Hebrew characters are only 59,000 chars but about 118,000 bytes
        val r = "ש".repeat(59_000)
        val body = payload(r)
        assertTrue("bytes ${bytes(body)}", bytes(body) <= UPLOAD_MAX_BODY_BYTES)
        val out = JSONObject(body).getString("report")
        assertTrue(out.startsWith("ש") && out.endsWith("ש"))
        assertTrue(out.contains(UPLOAD_TRUNCATION_MARKER))
    }

    @Test
    fun `body stays within 80000 bytes for emoji and for heavily escaped text`() {
        val emoji = "📰".repeat(29_000) // 58,000 chars, 116,000 bytes
        assertTrue(bytes(payload(emoji)) <= UPLOAD_MAX_BODY_BYTES)
        val escaped = "\"\n".repeat(29_000) // every char is two bytes in JSON
        assertTrue(bytes(payload(escaped)) <= UPLOAD_MAX_BODY_BYTES)
        val slashes = "/".repeat(59_000)
        assertTrue(bytes(payload(slashes)) <= UPLOAD_MAX_BODY_BYTES)
    }

    @Test
    fun `an ordinary report is a tiny fraction of the limit`() {
        assertTrue(bytes(payload("NewsFeed problem report\n".repeat(100))) < 5_000)
    }

    @Test
    fun `the report from the builder fits`() {
        val body = payload("a".repeat(BUG_REPORT_MAX_CHARS))
        assertTrue(bytes(body) <= UPLOAD_MAX_BODY_BYTES)
        assertEquals("a".repeat(BUG_REPORT_MAX_CHARS), JSONObject(body).getString("report"))
    }

    @Test
    fun `an empty report still makes a valid payload`() {
        assertEquals("", JSONObject(payload("")).getString("report"))
    }

    // -- response mapping --

    @Test
    fun `201 with an id is sent`() {
        assertEquals(UploadResult.Sent(42), mapResponse(201, """{"id":42}""", null))
        assertEquals(UploadResult.Sent(7), mapResponse(201, """ {"id": 7, "extra": true} """, null))
    }

    @Test
    fun `201 with a missing or malformed body is still sent but without an id`() {
        assertEquals(UploadResult.Sent(null), mapResponse(201, "", null))
        assertEquals(UploadResult.Sent(null), mapResponse(201, null, null))
        assertEquals(UploadResult.Sent(null), mapResponse(201, "<html>oops</html>", null))
        assertEquals(UploadResult.Sent(null), mapResponse(201, """{"id":"abc"}""", null))
        assertEquals(UploadResult.Sent(null), mapResponse(201, """{"id":-5}""", null))
        assertEquals(UploadResult.Sent(null), mapResponse(201, """{"id":1.5}""", null))
        assertEquals(UploadResult.Sent(null), mapResponse(201, """{"id":null}""", null))
        assertEquals(UploadResult.Sent(null), mapResponse(201, """[1,2]""", null))
        assertEquals(UploadResult.Sent(null), mapResponse(201, """{"id":""", null))
    }

    @Test
    fun `other status codes map to their result`() {
        assertEquals(UploadResult.Rejected(400), mapResponse(400, """{"error":"invalid_request"}""", null))
        assertEquals(UploadResult.Unauthorized, mapResponse(401, """{"error":"unauthorized"}""", null))
        assertEquals(UploadResult.Rejected(404), mapResponse(404, "", null))
        assertEquals(UploadResult.Rejected(405), mapResponse(405, "", null))
        assertEquals(UploadResult.TooLarge, mapResponse(413, """{"error":"too_large"}""", null))
        assertEquals(UploadResult.ServerError(500), mapResponse(500, "", null))
        assertEquals(UploadResult.ServerError(503), mapResponse(503, "", null))
        assertEquals(UploadResult.Rejected(302), mapResponse(302, "", null))
        assertEquals(UploadResult.Rejected(200), mapResponse(200, """{"id":1}""", null))
    }

    @Test
    fun `429 reads Retry-After seconds`() {
        assertEquals(UploadResult.RateLimited(3600), mapResponse(429, "", "3600"))
        assertEquals(UploadResult.RateLimited(90), mapResponse(429, "", " 90 "))
        assertEquals(UploadResult.RateLimited(null), mapResponse(429, "", null))
        assertEquals(UploadResult.RateLimited(null), mapResponse(429, "", ""))
        assertEquals(UploadResult.RateLimited(null), mapResponse(429, "", "soon"))
        assertEquals(UploadResult.RateLimited(null), mapResponse(429, "", "-5"))
        assertEquals(UploadResult.RateLimited(null), mapResponse(429, "", "99999999999999999999"))
        assertEquals(UploadResult.RateLimited(7 * 24 * 3600L), mapResponse(429, "", "99999999"))
    }

    @Test
    fun `429 reads a Retry-After date`() {
        // now = Tue, 14 Nov 2023 22:30:00 GMT
        val r = mapResponse(429, "", "Tue, 14 Nov 2023 23:30:00 GMT", now)
        assertEquals(UploadResult.RateLimited(3600), r)
        assertEquals(UploadResult.RateLimited(null), mapResponse(429, "", "Tue, 14 Nov 2023 21:30:00 GMT", now))
    }

    // -- messages --

    @Test
    fun `user messages`() {
        assertEquals("Sent. Report #42 — quote this number if you contact the developer.", uploadMessage(UploadResult.Sent(42)))
        assertEquals("Sent.", uploadMessage(UploadResult.Sent(null)))
        assertEquals("Too many reports right now. Try again in 60 minutes, or use Share report.", uploadMessage(UploadResult.RateLimited(3600)))
        assertEquals("Too many reports right now. Try again in 2 minutes, or use Share report.", uploadMessage(UploadResult.RateLimited(61)))
        assertEquals("Too many reports right now. Try again in 1 minute, or use Share report.", uploadMessage(UploadResult.RateLimited(5)))
        assertEquals("Too many reports right now. Try again in 1 minute, or use Share report.", uploadMessage(UploadResult.RateLimited(0)))
        assertEquals("Too many reports right now. Try again in about 12 hours, or use Share report.", uploadMessage(UploadResult.RateLimited(12 * 3600L)))
        assertEquals("Too many reports right now. Try again later, or use Share report.", uploadMessage(UploadResult.RateLimited(null)))
        assertEquals("Couldn't reach the server. Check your connection, or use Share report.", uploadMessage(UploadResult.NetworkError("IOException")))
        for (r in listOf(UploadResult.TooLarge, UploadResult.Unauthorized, UploadResult.Rejected(400), UploadResult.ServerError(503), UploadResult.Misconfigured)) {
            assertTrue(uploadMessage(r), uploadMessage(r).contains("Share report"))
        }
    }

    // -- endpoint --

    @Test
    fun `only https bases are accepted`() {
        assertTrue(checkReportBase("https://collector.example.org") is EndpointCheck.Ok)
        assertTrue(checkReportBase("  https://collector.example.org/  ") is EndpointCheck.Ok)
        assertTrue(checkReportBase("HTTPS://collector.example.org") is EndpointCheck.Ok)
        for (bad in listOf("", "   ", "http://collector.example.org", "ftp://x.org", "collector.example.org", "//collector.example.org",
            "https://", "https:///path", "https://user:pw@collector.example.org", "https://x.org?a=b", "https://x.org#f",
            "https://exa mple.org", "javascript:alert(1)", "https:/x.org")) {
            assertTrue("'$bad' should be rejected", checkReportBase(bad) is EndpointCheck.Bad)
        }
    }

    @Test
    fun `join is trailing slash safe and keeps a path prefix`() {
        fun url(b: String) = (checkReportBase(b) as EndpointCheck.Ok).url
        assertEquals("https://c.example.org/api/v1/reports", url("https://c.example.org"))
        assertEquals("https://c.example.org/api/v1/reports", url("https://c.example.org/"))
        assertEquals("https://c.example.org/api/v1/reports", url("https://c.example.org///"))
        assertEquals("https://c.example.org:8443/api/v1/reports", url("https://c.example.org:8443/"))
        assertEquals("https://c.example.org/collector/api/v1/reports", url("https://c.example.org/collector/"))
    }

    @Test
    fun `server host is shown without scheme path or key`() {
        assertEquals("reports-collector.example.org", reportServerHost("https://reports-collector.example.org/"))
        assertEquals("c.example.org:8443", reportServerHost("https://c.example.org:8443/x"))
        assertNull(reportServerHost("http://c.example.org"))
        assertNull(reportServerHost(""))
    }

    @Test
    fun `sending is configured only with an https base and a key`() {
        assertTrue(isReportSendConfigured("https://c.example.org", "k"))
        assertFalse(isReportSendConfigured("", "k"))
        assertFalse(isReportSendConfigured("https://c.example.org", ""))
        assertFalse(isReportSendConfigured("https://c.example.org", "   "))
        assertFalse(isReportSendConfigured("", ""))
        assertFalse(isReportSendConfigured("http://c.example.org", "k"))
    }

    // -- upload through a fake transport --

    private class FakeTransport(
        private val response: TransportResponse? = null,
        private val failure: Throwable? = null,
    ) : ReportTransport {
        var calls = 0
        var lastUrl: String? = null
        var lastHeaders: Map<String, String> = emptyMap()
        var lastBody: String? = null
        override suspend fun post(url: String, headers: Map<String, String>, body: String): TransportResponse {
            calls++; lastUrl = url; lastHeaders = headers; lastBody = body
            failure?.let { throw it }
            return response!!
        }
        override suspend fun postImage(url: String, headers: Map<String, String>, bytes: ByteArray): TransportResponse =
            throw AssertionError("the report tests never post a picture")
    }

    private val key = "SECRET-KEY-VALUE-123"

    @Test
    fun `upload posts to the endpoint with the two headers and maps 201`() = runBlocking {
        val t = FakeTransport(TransportResponse(201, """{"id":42}""", null))
        val r = uploadReport(t, "https://c.example.org/", key, payload("hi"))
        assertEquals(UploadResult.Sent(42), r)
        assertEquals("https://c.example.org/api/v1/reports", t.lastUrl)
        assertEquals("application/json", t.lastHeaders["Content-Type"])
        assertEquals(key, t.lastHeaders["X-App-Key"])
        assertEquals(2, t.lastHeaders.size)
        assertEquals("hi", JSONObject(t.lastBody!!).getString("report"))
    }

    @Test
    fun `upload maps 429 with Retry-After`() = runBlocking {
        val t = FakeTransport(TransportResponse(429, "{}", "120"))
        assertEquals(UploadResult.RateLimited(120), uploadReport(t, "https://c.example.org", key, "{}"))
    }

    @Test
    fun `upload never sends to a non-https or missing base and never calls the transport`() = runBlocking {
        val t = FakeTransport(TransportResponse(201, "{}", null))
        assertEquals(UploadResult.Misconfigured, uploadReport(t, "http://c.example.org", key, "{}"))
        assertEquals(UploadResult.Misconfigured, uploadReport(t, "", key, "{}"))
        assertEquals(UploadResult.Misconfigured, uploadReport(t, "https://c.example.org", "", "{}"))
        assertEquals(0, t.calls)
    }

    @Test
    fun `upload refuses a body over 80000 bytes`() = runBlocking {
        val t = FakeTransport(TransportResponse(201, "{}", null))
        assertEquals(UploadResult.TooLarge, uploadReport(t, "https://c.example.org", key, "x".repeat(UPLOAD_MAX_BODY_BYTES + 1)))
        assertEquals(0, t.calls)
    }

    @Test
    fun `io failures become a network error with only the class name`() = runBlocking {
        for (e in listOf(IOException("boom $key"), UnknownHostException("c.example.org $key"), SocketTimeoutException("timeout"), IllegalStateException("x $key"))) {
            val r = uploadReport(FakeTransport(failure = e), "https://c.example.org", key, "{}")
            assertEquals(UploadResult.NetworkError(e.javaClass.simpleName), r)
        }
    }

    @Test
    fun `cancellation is not swallowed`() {
        try {
            runBlocking { uploadReport(FakeTransport(failure = kotlinx.coroutines.CancellationException("cancelled")), "https://c.example.org", key, "{}") }
            fail("expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
    }

    // -- the key and the report never reach a log or a message --

    @Test
    fun `describeFailure and describeResult never contain the key or the message`() {
        val e = IOException("failed to connect to c.example.org with X-App-Key: $key and body secret-report-text")
        val d = describeFailure(e)
        assertEquals("IOException", d)
        assertFalse(d.contains(key))
        val results = listOf(
            UploadResult.Sent(42), UploadResult.RateLimited(60), UploadResult.TooLarge, UploadResult.Unauthorized,
            UploadResult.Rejected(404), UploadResult.ServerError(503), UploadResult.NetworkError(describeFailure(e)), UploadResult.Misconfigured,
        )
        for (r in results) {
            val text = describeResult(r)
            assertFalse(text, text.contains(key))
            assertFalse(text, text.contains("c.example.org"))
            assertFalse(text, text.contains("secret-report-text"))
            assertFalse(uploadMessage(r).contains(key))
        }
        assertEquals("sent id=42", describeResult(UploadResult.Sent(42)))
        assertEquals("rejected code=404", describeResult(UploadResult.Rejected(404)))
        assertEquals("server_error code=503", describeResult(UploadResult.ServerError(503)))
        assertEquals("network_error IOException", describeResult(UploadResult.NetworkError("IOException")))
    }

    @Test
    fun `a result with the key in its text cannot be built from an exception`() = runBlocking {
        val r = uploadReport(FakeTransport(failure = IOException("leak $key")), "https://c.example.org", key, "{}")
        assertNotNull(r)
        assertFalse(r.toString().contains(key))
        assertFalse(describeResult(r).contains(key))
        assertFalse(uploadMessage(r).contains(key))
    }
}
