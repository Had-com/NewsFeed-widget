package com.newsfeed.widget.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ReportImageUploadTest {

    private val cid = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val key = "SECRET-KEY-VALUE-123"
    private val base = "https://c.example.org"
    private val jpeg = ByteArray(1000) { (it % 251).toByte() }

    private fun ready(r: ImageRequest) = r as ImageRequest.Ready

    // -- request spec --

    @Test fun `the image url is the report endpoint plus id and image`() {
        assertEquals("https://c.example.org/api/v1/reports/42/image", ready(buildImageRequestSpec(base, key, 42, cid, 1000)).url)
    }

    @Test fun `the url join is trailing slash safe and keeps a path prefix and a port`() {
        fun url(b: String) = ready(buildImageRequestSpec(b, key, 7, cid, 10)).url
        assertEquals("https://c.example.org/api/v1/reports/7/image", url("https://c.example.org/"))
        assertEquals("https://c.example.org/api/v1/reports/7/image", url("https://c.example.org///"))
        assertEquals("https://c.example.org:8443/api/v1/reports/7/image", url("https://c.example.org:8443/"))
        assertEquals("https://c.example.org/collector/api/v1/reports/7/image", url("https://c.example.org/collector/"))
    }

    @Test fun `the image request has exactly the three contract headers`() {
        val spec = ready(buildImageRequestSpec(base, key, 42, cid, 1000))
        assertEquals("image/jpeg", spec.headers["Content-Type"])
        assertEquals(key, spec.headers["X-App-Key"])
        assertEquals(cid, spec.headers["X-Client-Id"])
        assertEquals(3, spec.headers.size)
    }

    @Test fun `only https bases and a key give a request`() {
        for (bad in listOf("http://c.example.org", "", "ftp://c.example.org", "c.example.org", "https://user:pw@c.example.org", "https://c.example.org?x=1")) {
            assertEquals("'$bad'", ImageRequest.Misconfigured, buildImageRequestSpec(bad, key, 42, cid, 1000))
        }
        assertEquals(ImageRequest.Misconfigured, buildImageRequestSpec(base, "", 42, cid, 1000))
        assertEquals(ImageRequest.Misconfigured, buildImageRequestSpec(base, "   ", 42, cid, 1000))
    }

    @Test fun `a bad report id or client id gives no request`() {
        assertEquals(ImageRequest.Misconfigured, buildImageRequestSpec(base, key, -1, cid, 1000))
        assertEquals(ImageRequest.Misconfigured, buildImageRequestSpec(base, key, 42, "", 1000))
        assertEquals(ImageRequest.Misconfigured, buildImageRequestSpec(base, key, 42, "not-a-uuid", 1000))
        assertEquals(ImageRequest.Misconfigured, buildImageRequestSpec(base, key, 42, "$cid\r\nX-Evil: 1", 1000))
    }

    @Test fun `the size guard is 1_400_000 bytes`() {
        assertTrue(buildImageRequestSpec(base, key, 42, cid, 1_400_000) is ImageRequest.Ready)
        assertEquals(ImageRequest.TooLarge, buildImageRequestSpec(base, key, 42, cid, 1_400_001))
        assertEquals(ImageRequest.TooLarge, buildImageRequestSpec(base, key, 42, cid, 1_500_000))
        assertEquals(ImageRequest.Empty, buildImageRequestSpec(base, key, 42, cid, 0))
        assertEquals(1_400_000, UPLOAD_IMAGE_MAX_BYTES)
    }

    // -- response mapping --

    @Test fun `201 is stored with the reported size`() {
        assertEquals(ImageUploadResult.Stored(812_345), mapImageResponse(201, """{"id":42,"imageBytes":812345}""", null))
        assertEquals(ImageUploadResult.Stored(null), mapImageResponse(201, "", null))
        assertEquals(ImageUploadResult.Stored(null), mapImageResponse(201, "not json", null))
        assertEquals(ImageUploadResult.Stored(null), mapImageResponse(201, """{"id":42,"imageBytes":-3}""", null))
        assertEquals(ImageUploadResult.Stored(null), mapImageResponse(201, """{"id":42,"imageBytes":"x"}""", null))
        assertEquals(ImageUploadResult.Stored(null), mapImageResponse(201, null, null))
    }

    @Test fun `the response table`() {
        assertEquals(ImageUploadResult.Rejected(400), mapImageResponse(400, """{"error":"invalid"}""", null))
        assertEquals(ImageUploadResult.Unauthorized, mapImageResponse(401, "{}", null))
        assertEquals(ImageUploadResult.ReportNotFound, mapImageResponse(404, """{"error":"not_found"}""", null))
        assertEquals(ImageUploadResult.Rejected(405), mapImageResponse(405, "", null))
        assertEquals(ImageUploadResult.AlreadyHasImage, mapImageResponse(409, """{"error":"image_exists"}""", null))
        assertEquals(ImageUploadResult.TooLarge, mapImageResponse(413, "{}", null))
        assertEquals(ImageUploadResult.Rejected(415), mapImageResponse(415, "{}", null))
        assertEquals(ImageUploadResult.ServerError(503), mapImageResponse(503, """{"error":"storage_full"}""", null))
        assertEquals(ImageUploadResult.ServerError(500), mapImageResponse(500, "", null))
        assertEquals(ImageUploadResult.Rejected(302), mapImageResponse(302, "", null))
        assertEquals(ImageUploadResult.Rejected(200), mapImageResponse(200, "", null))
    }

    @Test fun `429 reads Retry-After with and without the header`() {
        assertEquals(ImageUploadResult.RateLimited(3600), mapImageResponse(429, "{}", "3600"))
        assertEquals(ImageUploadResult.RateLimited(null), mapImageResponse(429, "{}", null))
        assertEquals(ImageUploadResult.RateLimited(null), mapImageResponse(429, "{}", "soon"))
        assertEquals(ImageUploadResult.RateLimited(null), mapImageResponse(429, "{}", ""))
    }

    // -- fake transport --

    private class Fake(
        val reportResponse: TransportResponse? = TransportResponse(201, """{"id":42}""", null),
        val imageResponse: TransportResponse? = TransportResponse(201, """{"id":42,"imageBytes":1000}""", null),
        val reportFailure: Throwable? = null,
        val imageFailure: Throwable? = null,
    ) : ReportTransport {
        val calls = mutableListOf<String>()
        var reportUrl: String? = null
        var reportHeaders: Map<String, String> = emptyMap()
        var reportBody: String? = null
        var imageUrl: String? = null
        var imageHeaders: Map<String, String> = emptyMap()
        var imageBody: ByteArray? = null
        override suspend fun post(url: String, headers: Map<String, String>, body: String): TransportResponse {
            calls += "report"; reportUrl = url; reportHeaders = headers; reportBody = body
            reportFailure?.let { throw it }
            return reportResponse!!
        }
        override suspend fun postImage(url: String, headers: Map<String, String>, bytes: ByteArray): TransportResponse {
            calls += "image"; imageUrl = url; imageHeaders = headers; imageBody = bytes
            imageFailure?.let { throw it }
            return imageResponse!!
        }
    }

    private val payload = buildUploadPayload("hello", cid, 933, "1.0", 1_700_001_000_000L)

    private fun send(t: ReportTransport, picture: ByteArray? = jpeg, onStored: (UploadResult.Sent) -> Unit = {}, load: (suspend () -> ByteArray?)? = picture?.let { p -> suspend { p } }) =
        runBlocking { uploadReportWithPicture(t, base, key, payload, cid, load, onStored) }

    // -- uploadImage --

    @Test fun `uploadImage posts the raw bytes with the contract headers`() = runBlocking {
        val t = Fake()
        val r = uploadImage(t, "https://c.example.org/", key, 42, cid, jpeg)
        assertEquals(ImageUploadResult.Stored(1000), r)
        assertEquals("https://c.example.org/api/v1/reports/42/image", t.imageUrl)
        assertEquals("image/jpeg", t.imageHeaders["Content-Type"])
        assertEquals(key, t.imageHeaders["X-App-Key"])
        assertEquals(cid, t.imageHeaders["X-Client-Id"])
        assertArrayEquals(jpeg, t.imageBody)
        assertEquals(listOf("image"), t.calls)
    }

    @Test fun `uploadImage never calls the transport for a bad base a too large or an empty picture`() = runBlocking {
        val t = Fake()
        assertEquals(ImageUploadResult.Misconfigured, uploadImage(t, "http://c.example.org", key, 42, cid, jpeg))
        assertEquals(ImageUploadResult.Misconfigured, uploadImage(t, base, "", 42, cid, jpeg))
        assertEquals(ImageUploadResult.TooLarge, uploadImage(t, base, key, 42, cid, ByteArray(1_400_001)))
        assertEquals(ImageUploadResult.Rejected(400), uploadImage(t, base, key, 42, cid, ByteArray(0)))
        assertTrue(t.calls.isEmpty())
    }

    @Test fun `io failures become offline with only the class name`() = runBlocking {
        for (e in listOf(IOException("boom $key"), UnknownHostException("c.example.org $key"), SocketTimeoutException("t"), IllegalStateException("x $key"))) {
            val r = uploadImage(Fake(imageFailure = e), base, key, 42, cid, jpeg)
            assertEquals(ImageUploadResult.Offline(e.javaClass.simpleName), r)
        }
    }

    @Test fun `cancellation during the picture upload is not swallowed`() {
        try {
            runBlocking { uploadImage(Fake(imageFailure = kotlinx.coroutines.CancellationException("c")), base, key, 42, cid, jpeg) }
            fail("expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
    }

    // -- the two-step flow --

    @Test fun `report ok and image ok`() {
        val t = Fake()
        val o = send(t)
        assertEquals(UploadResult.Sent(42), o.report)
        assertEquals(ImageUploadResult.Stored(1000), o.image)
        assertTrue(o.fullySent)
        assertEquals(listOf("report", "image"), t.calls)
        assertEquals("Sent. Report #42 with your picture — quote this number if you contact the developer.", sendOutcomeMessage(o))
    }

    @Test fun `the picture goes to the id the server returned with the same client id`() {
        val t = Fake(reportResponse = TransportResponse(201, """{"id":977}""", null))
        send(t)
        assertEquals("https://c.example.org/api/v1/reports/977/image", t.imageUrl)
        assertEquals(cid, t.imageHeaders["X-Client-Id"])
        assertTrue(t.reportBody!!.contains(cid))
        assertEquals(key, t.imageHeaders["X-App-Key"])
    }

    @Test fun `no picture means no second request and the old message`() {
        val t = Fake()
        val o = send(t, picture = null, load = null)
        assertEquals(SendOutcome(UploadResult.Sent(42), null), o)
        assertEquals(listOf("report"), t.calls)
        assertTrue(o.fullySent)
        assertEquals("Sent. Report #42 — quote this number if you contact the developer.", sendOutcomeMessage(o))
    }

    @Test fun `report ok and image 404 keeps the report sent`() {
        val t = Fake(imageResponse = TransportResponse(404, """{"error":"not_found"}""", null))
        var stored: UploadResult.Sent? = null
        val o = send(t, onStored = { stored = it })
        assertEquals(UploadResult.Sent(42), o.report)
        assertEquals(ImageUploadResult.ReportNotFound, o.image)
        assertTrue(o.reportStored)
        assertFalse(o.fullySent)
        assertEquals(UploadResult.Sent(42), stored)
        assertEquals(
            "Report #42 was sent, but the picture could not be uploaded (the server does not accept pictures yet (404/405)). " +
                "You can use Share report to send the picture yourself.",
            sendOutcomeMessage(o),
        )
    }

    @Test fun `report ok and image 429 says too many pictures today`() {
        val o = send(Fake(imageResponse = TransportResponse(429, "{}", "3600")))
        assertEquals(ImageUploadResult.RateLimited(3600), o.image)
        assertEquals(
            "Report #42 was sent, but the picture could not be uploaded (too many pictures today, try later). " +
                "You can use Share report to send the picture yourself.",
            sendOutcomeMessage(o),
        )
    }

    @Test fun `report ok and image 413 or offline`() {
        assertEquals(
            "Report #42 was sent, but the picture could not be uploaded (too large). You can use Share report to send the picture yourself.",
            sendOutcomeMessage(send(Fake(imageResponse = TransportResponse(413, "{}", null)))),
        )
        assertEquals(
            "Report #42 was sent, but the picture could not be uploaded (offline). You can use Share report to send the picture yourself.",
            sendOutcomeMessage(send(Fake(imageFailure = IOException("down")))),
        )
    }

    @Test fun `report failing means no picture upload is attempted and the picture is not even read`() {
        for (resp in listOf(TransportResponse(429, "{}", "60"), TransportResponse(413, "{}", null), TransportResponse(401, "{}", null),
            TransportResponse(400, "{}", null), TransportResponse(503, "{}", null))) {
            val t = Fake(reportResponse = resp)
            var loaded = false
            val o = send(t, onStored = { fail("must not record a send") }, load = suspend { loaded = true; jpeg })
            assertNull(o.image)
            assertFalse(o.reportStored)
            assertFalse(o.fullySent)
            assertEquals(listOf("report"), t.calls)
            assertFalse(loaded)
        }
        val t = Fake(reportFailure = IOException("down"))
        val o = send(t)
        assertTrue(o.report is UploadResult.NetworkError)
        assertEquals(listOf("report"), t.calls)
        assertEquals(uploadMessage(o.report), sendOutcomeMessage(o))
    }

    @Test fun `the report result is recorded before the picture goes out`() {
        val order = mutableListOf<String>()
        val t = object : ReportTransport {
            override suspend fun post(url: String, headers: Map<String, String>, body: String): TransportResponse {
                order += "report"; return TransportResponse(201, """{"id":42}""", null)
            }
            override suspend fun postImage(url: String, headers: Map<String, String>, bytes: ByteArray): TransportResponse {
                order += "image"; throw IOException("down")
            }
        }
        send(t, onStored = { order += "recorded" })
        assertEquals(listOf("report", "recorded", "image"), order)
    }

    @Test fun `a report answer without a number cannot get a picture`() {
        val t = Fake(reportResponse = TransportResponse(201, "{}", null))
        val o = send(t)
        assertEquals(UploadResult.Sent(null), o.report)
        assertEquals(ImageUploadResult.Rejected(0), o.image)
        assertEquals(listOf("report"), t.calls)
        assertEquals(
            "The report was sent, but the picture could not be uploaded (the server did not accept it). You can use Share report to send the picture yourself.",
            sendOutcomeMessage(o),
        )
    }

    @Test fun `an unreadable picture file is reported without calling the server`() {
        val t = Fake()
        val o = send(t, load = suspend { null })
        assertEquals(ImageUploadResult.Unreadable, o.image)
        assertEquals(listOf("report"), t.calls)
        assertTrue(sendOutcomeMessage(o).startsWith("Report #42 was sent, but the picture could not be uploaded (the picture file could not be read)."))
    }

    @Test fun `a too large picture is refused locally`() {
        val t = Fake()
        val o = send(t, picture = ByteArray(1_400_001))
        assertEquals(ImageUploadResult.TooLarge, o.image)
        assertEquals(listOf("report"), t.calls)
    }

    @Test fun `a missing key means neither request is made`() = runBlocking {
        val t = Fake()
        val o = uploadReportWithPicture(t, base, "", payload, cid, suspend { jpeg })
        assertEquals(UploadResult.Misconfigured, o.report)
        assertNull(o.image)
        assertTrue(t.calls.isEmpty())
    }

    // -- messages --

    @Test fun `every failure reason is a short phrase and the message always offers Share report`() {
        val failures = listOf(
            ImageUploadResult.TooLarge, ImageUploadResult.RateLimited(null), ImageUploadResult.RateLimited(5), ImageUploadResult.ReportNotFound,
            ImageUploadResult.AlreadyHasImage, ImageUploadResult.Rejected(400), ImageUploadResult.Rejected(405), ImageUploadResult.Rejected(415),
            ImageUploadResult.Unauthorized, ImageUploadResult.ServerError(503), ImageUploadResult.Offline("IOException"),
            ImageUploadResult.Unreadable, ImageUploadResult.Misconfigured,
        )
        for (f in failures) {
            val reason = imageFailureReason(f)
            assertTrue(f.toString(), reason.isNotBlank() && reason.length < 70)
            val m = sendOutcomeMessage(SendOutcome(UploadResult.Sent(42), f))
            assertTrue(m, m.startsWith("Report #42 was sent, but the picture could not be uploaded ($reason)."))
            assertTrue(m, m.endsWith("You can use Share report to send the picture yourself."))
        }
        assertTrue(imageFailureReason(ImageUploadResult.Rejected(405)).contains("404/405"))
    }

    @Test fun `without an id the success and failure messages still read well`() {
        assertEquals("Sent, with your picture.", sendOutcomeMessage(SendOutcome(UploadResult.Sent(null), ImageUploadResult.Stored(1))))
    }

    @Test fun `the key and the picture never appear in any message or log helper`() {
        val e = IOException("failed X-App-Key: $key https://c.example.org/api/v1/reports/42/image")
        val results = listOf<ImageUploadResult>(
            ImageUploadResult.Stored(1000), ImageUploadResult.TooLarge, ImageUploadResult.RateLimited(60), ImageUploadResult.ReportNotFound,
            ImageUploadResult.AlreadyHasImage, ImageUploadResult.Rejected(415), ImageUploadResult.Unauthorized, ImageUploadResult.ServerError(503),
            ImageUploadResult.Offline(describeFailure(e)), ImageUploadResult.Unreadable, ImageUploadResult.Misconfigured,
        )
        for (r in results) {
            for (text in listOf(describeImageResult(r), imageFailureReason(r), sendOutcomeMessage(SendOutcome(UploadResult.Sent(42), r)), r.toString())) {
                assertFalse(text, text.contains(key))
                assertFalse(text, text.contains("c.example.org"))
                assertFalse(text, text.contains("X-App-Key"))
            }
        }
        assertEquals("image stored bytes=1000", describeImageResult(ImageUploadResult.Stored(1000)))
        assertEquals("image rejected code=415", describeImageResult(ImageUploadResult.Rejected(415)))
        assertEquals("image network_error IOException", describeImageResult(ImageUploadResult.Offline("IOException")))
        assertEquals("image rate_limited retry_after=60", describeImageResult(ImageUploadResult.RateLimited(60)))
    }

    @Test fun `an outcome built from a failing transport holds no key`() {
        val o = send(Fake(imageFailure = IOException("leak $key")))
        assertFalse(o.toString().contains(key))
        assertFalse(sendOutcomeMessage(o).contains(key))
        assertFalse(describeImageResult(o.image!!).contains(key))
    }
}
