package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class ReportSendStoreTest {

    private class MemoryStore : ReportKeyValueStore {
        val map = mutableMapOf<String, Any>()
        override fun getString(key: String): String? = map[key] as? String
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getLong(key: String, default: Long): Long = map[key] as? Long ?: default
        override fun putLong(key: String, value: Long) { map[key] = value }
    }

    private val v4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    // -- client id --

    @Test
    fun `a new install gets a random version 4 uuid and keeps it`() {
        val store = MemoryStore()
        val first = resolveClientId(store)
        assertTrue(first, v4.matches(first))
        assertEquals(first, store.getString("client_id"))
        assertEquals(first, resolveClientId(store))
        assertEquals(first, resolveClientId(store))
    }

    @Test
    fun `two installs get different ids`() {
        assertNotEquals(resolveClientId(MemoryStore()), resolveClientId(MemoryStore()))
    }

    @Test
    fun `a damaged stored id is replaced`() {
        val store = MemoryStore().also { it.putString("client_id", "not-a-uuid") }
        val id = resolveClientId(store)
        assertTrue(v4.matches(id))
        assertEquals(id, store.getString("client_id"))
    }

    @Test
    fun `the id comes only from the generator`() {
        val store = MemoryStore()
        assertEquals("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", resolveClientId(store) { "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d" })
    }

    @Test
    fun `uuid validation`() {
        assertTrue(isValidClientId("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"))
        assertFalse(isValidClientId("0A1B2C3D-4E5F-4A6B-8C7D-9E0F1A2B3C4D"))
        assertFalse(isValidClientId("0a1b2c3d-4e5f-1a6b-8c7d-9e0f1a2b3c4d"))
        assertFalse(isValidClientId("0a1b2c3d-4e5f-4a6b-cc7d-9e0f1a2b3c4d"))
        assertFalse(isValidClientId(""))
    }

    // -- last sent --

    @Test
    fun `last sent is stored and read back`() {
        val s = MemoryStore()
        assertNull(readLastSent(s))
        recordLastSent(s, 1_700_001_000_000L, 42L)
        assertEquals(LastSent(1_700_001_000_000L, 42L), readLastSent(s))
        recordLastSent(s, 1_700_002_000_000L, null)
        assertEquals(LastSent(1_700_002_000_000L, null), readLastSent(s))
    }

    @Test
    fun `last sent text shows time and id`() {
        val zone = ZoneId.of("UTC")
        // 1_700_001_000_000 = 2023-11-14 22:30 UTC
        assertEquals("Last sent: Nov 14, 22:30 (#42)", lastSentText(LastSent(1_700_001_000_000L, 42L), zone, Locale.ENGLISH))
        assertEquals("Last sent: Nov 14, 22:30", lastSentText(LastSent(1_700_001_000_000L, null), zone, Locale.ENGLISH))
        assertEquals("Last sent: Nov 15, 00:30 (#7)", lastSentText(LastSent(1_700_001_000_000L, 7L), ZoneId.of("Asia/Jerusalem"), Locale.ENGLISH))
    }

    // -- cooldown --

    @Test
    fun `cooldown counts down from the last send`() {
        val t = 1_000_000L
        assertEquals(60_000L, cooldownRemainingMs(t, t))
        assertEquals(30_000L, cooldownRemainingMs(t, t + 30_000))
        assertEquals(1L, cooldownRemainingMs(t, t + 59_999))
        assertEquals(0L, cooldownRemainingMs(t, t + 60_000))
        assertEquals(0L, cooldownRemainingMs(t, t + 10 * 60_000))
    }

    @Test
    fun `no cooldown without a previous send or after a clock change`() {
        assertEquals(0L, cooldownRemainingMs(0L, 5_000_000L))
        assertEquals(0L, cooldownRemainingMs(-1L, 5_000_000L))
        // the stored time is far in the future (clock moved back): do not lock the button for days
        assertEquals(0L, cooldownRemainingMs(5_000_000L + 3_600_000L, 5_000_000L))
    }

    @Test
    fun `cooldown seconds are rounded up for the message`() {
        assertEquals(1, cooldownSeconds(1L))
        assertEquals(1, cooldownSeconds(1_000L))
        assertEquals(2, cooldownSeconds(1_001L))
        assertEquals(60, cooldownSeconds(60_000L))
        assertEquals(
            "You just sent a report. Please wait 30 seconds before sending another, or use Share report.",
            cooldownMessage(30_000L),
        )
    }
}
