package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ReportAttachmentTest {

    // -- planImageSize --

    @Test fun `a large landscape photo is scaled to a long edge of 1600 keeping the aspect`() {
        assertEquals(ImageSize(1600, 1200), planImageSize(4000, 3000))
        assertEquals(ImageSize(1200, 1600), planImageSize(3000, 4000))
        assertEquals(ImageSize(1600, 900), planImageSize(1920, 1080))
    }

    @Test fun `a picture within the limit is never upscaled`() {
        assertEquals(ImageSize(800, 600), planImageSize(800, 600))
        assertEquals(ImageSize(1600, 1000), planImageSize(1600, 1000))
        assertEquals(ImageSize(1, 1), planImageSize(1, 1))
    }

    @Test fun `a very thin picture keeps at least one pixel on the short edge`() {
        assertEquals(ImageSize(1600, 1), planImageSize(100_000, 10))
        assertEquals(ImageSize(1, 1600), planImageSize(10, 100_000))
    }

    @Test fun `non positive sizes become 1 px instead of dividing by zero`() {
        assertEquals(ImageSize(1, 1), planImageSize(0, 0))
        assertEquals(ImageSize(1, 3), planImageSize(-5, 3))
    }

    @Test fun `a different max edge is honoured`() {
        assertEquals(ImageSize(160, 120), planImageSize(4000, 3000, maxEdge = 160))
    }

    // -- inSampleSizeFor --

    @Test fun `the sample size is a power of two and keeps the long edge at most twice the target`() {
        for ((w, h) in listOf(1000 to 800, 3000 to 4000, 4000 to 3000, 12_000 to 9000, 100 to 100, 8000 to 100, 3199 to 10, 3200 to 10, 3201 to 10)) {
            val s = inSampleSizeFor(w, h, 1600)
            assertTrue("power of two for $w x $h: $s", s >= 1 && (s and (s - 1)) == 0)
            val decodedLong = maxOf(w, h) / s
            assertTrue("$w x $h /$s = $decodedLong must be <= 3200", decodedLong <= 3200)
            assertTrue("$w x $h /$s = $decodedLong must not drop below the target when possible", s == 1 || decodedLong >= 1600)
        }
    }

    @Test fun `known sample sizes`() {
        assertEquals(1, inSampleSizeFor(1600, 1200, 1600))
        assertEquals(1, inSampleSizeFor(3199, 100, 1600))
        assertEquals(2, inSampleSizeFor(3200, 100, 1600))
        assertEquals(2, inSampleSizeFor(4000, 3000, 1600))
        assertEquals(4, inSampleSizeFor(8000, 6000, 1600))
        assertEquals(8, inSampleSizeFor(12_800, 9600, 1600))
        assertEquals(16, inSampleSizeFor(4000, 3000, 160))
    }

    @Test fun `bad sizes give sample size 1`() {
        assertEquals(1, inSampleSizeFor(0, 0, 1600))
        assertEquals(1, inSampleSizeFor(-1, 100, 1600))
        assertEquals(1, inSampleSizeFor(4000, 3000, 0))
    }

    // -- compression ladder --

    @Test fun `the ladder starts at quality 80 and steps down 70 60 50 at full size`() {
        assertEquals(CompressionStep(80, 1.0f), nextCompressionStep(0))
        assertEquals(CompressionStep(70, 1.0f), nextCompressionStep(1))
        assertEquals(CompressionStep(60, 1.0f), nextCompressionStep(2))
        assertEquals(CompressionStep(50, 1.0f), nextCompressionStep(3))
    }

    @Test fun `after quality 50 the picture is scaled by 0_75 and the qualities are tried again`() {
        assertEquals(CompressionStep(80, 0.75f), nextCompressionStep(4))
        assertEquals(CompressionStep(50, 0.75f), nextCompressionStep(7))
        assertEquals(CompressionStep(80, 0.5f), nextCompressionStep(8))
    }

    @Test fun `the ladder ends and never goes below quality 40`() {
        var attempt = 0
        var last: CompressionStep? = null
        while (true) {
            val s = nextCompressionStep(attempt) ?: break
            assertTrue("quality ${s.quality}", s.quality >= PICTURE_MIN_QUALITY)
            assertTrue(s.scale > 0f && s.scale <= 1f)
            last = s
            attempt++
            assertTrue("ladder must end", attempt < 100)
        }
        assertEquals(CompressionStep(40, 0.5f), last)
        assertNull(nextCompressionStep(attempt))
        assertNull(nextCompressionStep(-1))
    }

    @Test fun `step sizes follow the scale but never go below 800 on the long edge`() {
        assertEquals(ImageSize(1600, 1200), planStepSize(4000, 3000, CompressionStep(80, 1.0f)))
        assertEquals(ImageSize(1200, 900), planStepSize(4000, 3000, CompressionStep(80, 0.75f)))
        assertEquals(ImageSize(800, 600), planStepSize(4000, 3000, CompressionStep(80, 0.5f)))
        // 1000 px wide: half would be 500, the floor keeps 800
        assertEquals(ImageSize(800, 400), planStepSize(1000, 500, CompressionStep(80, 0.5f)))
        // a picture already smaller than the floor is never upscaled
        assertEquals(ImageSize(600, 300), planStepSize(600, 300, CompressionStep(40, 0.5f)))
    }

    // -- compressToBudget --

    private fun fakeBytes(n: Int) = ByteArray(n)

    @Test fun `a picture that fits at quality 80 is encoded once`() {
        val calls = mutableListOf<Triple<Int, Int, Int>>()
        val r = compressToBudget(4000, 3000) { w, h, q -> calls += Triple(w, h, q); fakeBytes(500_000) }
        assertTrue(r is PictureEncoding.Ok)
        r as PictureEncoding.Ok
        assertEquals(500_000, r.bytes.size)
        assertEquals(1600, r.width); assertEquals(1200, r.height); assertEquals(80, r.quality)
        assertEquals(listOf(Triple(1600, 1200, 80)), calls)
    }

    @Test fun `a big result lowers the quality step by step`() {
        val sizes = mapOf(80 to 1_200_000, 70 to 1_000_000, 60 to 880_000)
        val qualities = mutableListOf<Int>()
        val r = compressToBudget(4000, 3000) { _, _, q -> qualities += q; fakeBytes(sizes.getValue(q)) } as PictureEncoding.Ok
        assertEquals(listOf(80, 70, 60), qualities)
        assertEquals(60, r.quality)
        assertEquals(880_000, r.bytes.size)
        assertEquals(1600, r.width)
    }

    @Test fun `after quality 50 it scales down by 0_75`() {
        val r = compressToBudget(4000, 3000) { w, _, _ -> if (w == 1600) fakeBytes(1_100_000) else fakeBytes(800_000) } as PictureEncoding.Ok
        assertEquals(1200, r.width); assertEquals(900, r.height); assertEquals(80, r.quality)
    }

    @Test fun `when nothing reaches 900 KB the smallest result up to 1_4 MB is accepted`() {
        val r = compressToBudget(4000, 3000) { _, _, q -> fakeBytes(1_000_000 + q) } as PictureEncoding.Ok
        assertEquals(1_000_040, r.bytes.size)
        assertEquals(40, r.quality)
    }

    @Test fun `still over 1_4 MB at the end of the ladder is a hard failure`() {
        val calls = mutableListOf<Int>()
        val r = compressToBudget(4000, 3000) { _, _, q -> calls += q; fakeBytes(PICTURE_HARD_MAX_BYTES + 1) }
        assertEquals(PictureEncoding.TooLarge, r)
        assertTrue(calls.all { it >= PICTURE_MIN_QUALITY })
    }

    @Test fun `an encoder failure stops at once`() {
        var calls = 0
        val r = compressToBudget(4000, 3000) { _, _, _ -> calls++; null }
        assertEquals(PictureEncoding.Failed, r)
        assertEquals(1, calls)
    }

    @Test fun `a small picture does not repeat identical encodes`() {
        val seen = mutableSetOf<Triple<Int, Int, Int>>()
        compressToBudget(300, 200) { w, h, q ->
            assertTrue("duplicate $w x $h q$q", seen.add(Triple(w, h, q)))
            fakeBytes(2_000_000)
        }
    }

    // -- EXIF orientation --

    @Test fun `exif orientations map to a rotation and a mirror`() {
        assertEquals(OrientationFix(0, false), orientationFix(1))
        assertEquals(OrientationFix(0, true), orientationFix(2))
        assertEquals(OrientationFix(180, false), orientationFix(3))
        assertEquals(OrientationFix(180, true), orientationFix(4))
        assertEquals(OrientationFix(90, true), orientationFix(5))
        assertEquals(OrientationFix(90, false), orientationFix(6))
        assertEquals(OrientationFix(270, true), orientationFix(7))
        assertEquals(OrientationFix(270, false), orientationFix(8))
        assertEquals(OrientationFix(0, false), orientationFix(0))
        assertEquals(OrientationFix(0, false), orientationFix(99))
    }

    // -- names and cleanup --

    @Test fun `attachment file name uses local time with seconds`() {
        assertEquals("attachment-20231114-223000.jpg", attachmentFileName(1_700_001_000_000L, ZoneId.of("UTC")))
        assertEquals("attachment-20231115-003007.jpg", attachmentFileName(1_700_001_007_000L, ZoneId.of("Asia/Jerusalem")))
    }

    @Test fun `report cache file names are recognised`() {
        assertTrue(isReportCacheFileName("newsfeed-report-20231114-223000.txt"))
        assertTrue(isReportCacheFileName("attachment-20231114-223000.jpg"))
        assertFalse(isReportCacheFileName("attachment-20231114-223000.png"))
        assertFalse(isReportCacheFileName("newsfeed-report-1.zip"))
        assertFalse(isReportCacheFileName("other.jpg"))
        assertFalse(isReportCacheFileName("attachment.jpg"))
    }

    @Test fun `the seven day cleanup also removes old attachment pictures and nothing else`() {
        val now = 1_700_000_000_000L
        val day = 24L * 3600 * 1000
        val files = listOf(
            ReportFileInfo("attachment-old.jpg", now - 8 * day),
            ReportFileInfo("attachment-new.jpg", now - 6 * day),
            ReportFileInfo("attachment-future.jpg", now + day),
            ReportFileInfo("newsfeed-report-old.txt", now - 8 * day),
            ReportFileInfo("photo.jpg", now - 30 * day),
            ReportFileInfo("attachment-old.png", now - 30 * day),
        )
        assertEquals(listOf("attachment-old.jpg", "newsfeed-report-old.txt"), staleReportFiles(files, now))
    }

    // -- texts --

    @Test fun `size text is in whole kilobytes`() {
        assertEquals("812 KB", pictureSizeText(812_345))
        assertEquals("1 KB", pictureSizeText(1))
        assertEquals("1 KB", pictureSizeText(0))
        assertEquals("900 KB", pictureSizeText(900_000))
        assertEquals("1400 KB", pictureSizeText(1_400_000))
    }

    @Test fun `ui texts`() {
        assertEquals("Picture attached · 812 KB", pictureAttachedText(812_345))
        assertEquals(
            "The picture is re-saved without location data. It may still show private things — check it before you send or share.",
            PICTURE_PRIVACY_HINT,
        )
    }

    @Test fun `limits`() {
        assertEquals(1600, PICTURE_MAX_EDGE)
        assertEquals(900_000, PICTURE_TARGET_BYTES)
        assertEquals(1_400_000, PICTURE_HARD_MAX_BYTES)
        assertEquals(160, PICTURE_THUMB_EDGE)
        assertEquals(80, PICTURE_START_QUALITY)
        assertEquals(40, PICTURE_MIN_QUALITY)
        assertEquals(800, PICTURE_MIN_EDGE)
    }
}
