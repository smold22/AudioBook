package com.yourapp.audiobook.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BackupManagerTest {

    @Test
    fun roundTripPreservesProgressAndTheme() {
        val payload = BackupManager.buildPayload(
            progress = mapOf("book:123" to "2;60000", "book:456" to "0;1000"),
            themeMode = "dark",
        )
        val restored = BackupManager.parsePayload(payload)
        assertEquals(2, restored.progress.size)
        assertEquals("2;60000", restored.progress["book:123"])
        assertEquals("dark", restored.theme)
    }

    @Test
    fun emptyProgressAndNullTheme() {
        val payload = BackupManager.buildPayload(emptyMap(), "")
        val restored = BackupManager.parsePayload(payload)
        assertTrue(restored.progress.isEmpty())
        assertNull(restored.theme)
    }

    @Test
    fun payloadIsStableJson() {
        val payload = BackupManager.buildPayload(mapOf("book:1" to "0;0"), "light")
        assertTrue(payload.contains("\"version\":1"))
        assertTrue(payload.contains("\"theme\":\"light\""))
        assertTrue(payload.contains("\"progress\":{\"book:1\":\"0;0\"}"))
    }

    @Test(expected = IOException::class)
    fun corruptInputThrows() {
        BackupManager.parsePayload("not json at all")
    }

    @Test(expected = IOException::class)
    fun malformedJsonThrows() {
        BackupManager.parsePayload("{\"version\":1")
    }
}