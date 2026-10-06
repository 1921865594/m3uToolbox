package com.m3utoolbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelEditPersistenceTest {

    @Test
    fun overwriteFrom_replacesHeadersAndKeepsOriginalIdentity() {
        val original = Channel(displayName = "CCTV", originalUrl = "https://example.com/live.m3u8")
        original.setRequestHeader("User-Agent", "old-agent")
        original.setRequestHeader("Origin", "https://old.example")
        original.setRequestHeader("X-Old", "old")

        val edited = Channel(displayName = "CCTV", originalUrl = "https://example.com/live.m3u8")
        edited.setRequestHeader("User-Agent", "new-agent")
        edited.setRequestHeader("Referer", "https://new.example/")
        edited.setRequestHeader("X-New", "new")

        val selectedReference: Channel = original
        original.overwriteFrom(edited)

        assertSame(selectedReference, original)
        val headers = original.getRequestHeaders()
        assertEquals("new-agent", headers["User-Agent"])
        assertEquals("https://new.example/", headers["Referer"])
        assertEquals("new", headers["X-New"])
        assertTrue(headers.keys.none { it.equals("Origin", ignoreCase = true) })
        assertTrue(headers.keys.none { it.equals("X-Old", ignoreCase = true) })
    }

    @Test
    fun overwriteFrom_deletingBuiltInHeaderDoesNotRestoreLegacyValue() {
        val original = Channel(displayName = "Test", originalUrl = "https://example.com/a.m3u8")
        original.userAgent = "legacy-agent"
        original.setRequestHeader("User-Agent", "legacy-agent")
        original.setRequestHeader("Origin", "https://example.com")

        val edited = Channel(displayName = "Test", originalUrl = "https://example.com/a.m3u8")
        edited.userAgent = null
        edited.referer = null

        original.overwriteFrom(edited)
        val headers = original.getRequestHeaders()

        assertTrue(headers.keys.none { it.equals("User-Agent", ignoreCase = true) })
        assertTrue(headers.keys.none { it.equals("Origin", ignoreCase = true) })
    }
}
