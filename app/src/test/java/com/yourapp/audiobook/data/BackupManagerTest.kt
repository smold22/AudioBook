package com.yourapp.audiobook.data

import com.yourapp.audiobook.source.api.Book
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BackupManagerTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = createTempDir("backup-test")
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun roundTripPreservesProgressAndTheme() = runBlocking {
        val manager = BackupManager()
        val path = manager.backup(
            dirPath = tempDir.absolutePath,
            favorites = listOf(
                Book(sourceId = "test", id = "1", title = "Книга", url = "https://test/1"),
            ),
            watchlist = listOf(
                Book(sourceId = "test", id = "2", title = "Позже", url = "https://test/2"),
            ),
            history = emptyList(),
            progress = mapOf("book:123" to "2;60000", "book:456" to "0;1000"),
            theme = "dark",
        )!!
        val restored = manager.restore(path)
        assertEquals(2, restored.progress!!.size)
        assertEquals("2;60000", restored.progress["book:123"])
        assertEquals("dark", restored.theme)
        assertEquals(1, restored.favorites!!.size)
        assertEquals(1, restored.watchlist!!.size)
    }

    @Test
    fun emptyProgressAndNullTheme() = runBlocking {
        val manager = BackupManager()
        val path = manager.backup(tempDir.absolutePath, emptyList(), emptyList(), emptyList(), emptyMap(), "")!!
        val restored = manager.restore(path)
        assertTrue(restored.progress!!.isEmpty())
        assertTrue(restored.theme.isNullOrEmpty())
    }

    @Test(expected = IOException::class)
    fun corruptInputThrows() = runBlocking<Unit> {
        val file = File(tempDir, "bad.json")
        file.writeText("not json at all")
        BackupManager().restore(file.absolutePath)
    }

    @Test(expected = IOException::class)
    fun unsupportedVersionThrows() = runBlocking<Unit> {
        val file = File(tempDir, "future.json")
        file.writeText("{\"version\":99}")
        BackupManager().restore(file.absolutePath)
    }
}
