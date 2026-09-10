package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogStoreTest {

    private fun record(
        timestamp: Long,
        versionCode: Int,
        exceptionType: String = "java.lang.IllegalStateException",
        message: String = "boom",
    ) = CrashLogStore.CrashRecord(
        timestamp = timestamp,
        versionCode = versionCode,
        exceptionType = exceptionType,
        message = message,
        stackTrace = "stack trace text",
    )

    @Test
    fun `summarize returns empty list for no records`() {
        assertEquals(0, CrashLogStore.summarize(emptyList(), currentVersionCode = 110).size)
    }

    @Test
    fun `summarize groups identical exception type and message as one bug`() {
        val records = listOf(
            record(timestamp = 1000L, versionCode = 108),
            record(timestamp = 2000L, versionCode = 108),
            record(timestamp = 3000L, versionCode = 109),
        )
        val summaries = CrashLogStore.summarize(records, currentVersionCode = 109)
        assertEquals(1, summaries.size)
        assertEquals(3, summaries[0].occurrenceCount)
    }

    @Test
    fun `summarize treats different exception types as separate bugs`() {
        val records = listOf(
            record(timestamp = 1000L, versionCode = 108, exceptionType = "java.lang.NullPointerException"),
            record(timestamp = 2000L, versionCode = 108, exceptionType = "java.lang.IllegalStateException"),
        )
        assertEquals(2, CrashLogStore.summarize(records, currentVersionCode = 109).size)
    }

    @Test
    fun `summarize treats different messages of the same exception type as separate bugs`() {
        val records = listOf(
            record(timestamp = 1000L, versionCode = 108, message = "first message"),
            record(timestamp = 2000L, versionCode = 108, message = "second message"),
        )
        assertEquals(2, CrashLogStore.summarize(records, currentVersionCode = 109).size)
    }

    @Test
    fun `summarize marks a bug unsolved when its latest occurrence matches the current version`() {
        val records = listOf(record(timestamp = 1000L, versionCode = 109))
        val summary = CrashLogStore.summarize(records, currentVersionCode = 109).single()
        assertEquals(false, summary.isSolved)
    }

    @Test
    fun `summarize marks a bug solved when its latest occurrence is an older version than current`() {
        val records = listOf(record(timestamp = 1000L, versionCode = 108))
        val summary = CrashLogStore.summarize(records, currentVersionCode = 109).single()
        assertEquals(true, summary.isSolved)
    }

    @Test
    fun `summarize uses the most recent occurrence to decide solved status, not the oldest`() {
        // First seen on an old version, but it also happened again on the CURRENT version -
        // still unsolved, since it's still actively happening now.
        val records = listOf(
            record(timestamp = 1000L, versionCode = 105),
            record(timestamp = 2000L, versionCode = 109),
        )
        val summary = CrashLogStore.summarize(records, currentVersionCode = 109).single()
        assertEquals(false, summary.isSolved)
        assertEquals(109, summary.lastSeenVersionCode)
        assertEquals(2000L, summary.lastSeenAt)
    }

    @Test
    fun `summarize sorts results by most recently seen first`() {
        val records = listOf(
            record(timestamp = 1000L, versionCode = 108, exceptionType = "OlderException"),
            record(timestamp = 5000L, versionCode = 108, exceptionType = "NewerException"),
        )
        val summaries = CrashLogStore.summarize(records, currentVersionCode = 109)
        assertEquals("NewerException", summaries[0].exceptionType)
        assertEquals("OlderException", summaries[1].exceptionType)
    }

    @Test
    fun `summarize returns empty list for empty input, matching readAll's empty-file contract`() {
        // No Context available in a plain JUnit test, so readAll() itself (missing file case)
        // is exercised on-device in Task 4. This just documents that summarize() handles an
        // empty input the same way readAll() would return it, so the two compose correctly.
        assertTrue(CrashLogStore.summarize(emptyList(), currentVersionCode = 1).isEmpty())
    }

    @Test
    fun `recordsToJson then recordsFromJson round-trips an empty list`() {
        val json = CrashLogStore.recordsToJson(emptyList())
        assertEquals("[]", json)
        assertEquals(emptyList<CrashLogStore.CrashRecord>(), CrashLogStore.recordsFromJson(json))
    }

    @Test
    fun `recordsToJson then recordsFromJson round-trips a single record with all fields intact`() {
        val original = record(timestamp = 1234567890L, versionCode = 42)
        val json = CrashLogStore.recordsToJson(listOf(original))
        val roundTripped = CrashLogStore.recordsFromJson(json)
        assertEquals(listOf(original), roundTripped)
    }

    @Test
    fun `recordsToJson then recordsFromJson preserves quotes, newlines, and unicode in message and stackTrace`() {
        val original = CrashLogStore.CrashRecord(
            timestamp = 1000L,
            versionCode = 1,
            exceptionType = "java.lang.RuntimeException",
            message = "Failed to parse \"config.json\": unexpected token éà中文 👾",
            stackTrace = "java.lang.RuntimeException: \"quoted\"\n\tat Foo.bar(Foo.java:1)\n" +
                "Caused by: java.io.IOException: disk full ☃\n\tat Baz.qux(Baz.java:2)",
        )
        val json = CrashLogStore.recordsToJson(listOf(original))
        val roundTripped = CrashLogStore.recordsFromJson(json)
        assertEquals(listOf(original), roundTripped)
    }

    @Test
    fun `recordsFromJson returns empty list for malformed json instead of throwing`() {
        assertEquals(emptyList<CrashLogStore.CrashRecord>(), CrashLogStore.recordsFromJson("not valid json{{{"))
    }
}
