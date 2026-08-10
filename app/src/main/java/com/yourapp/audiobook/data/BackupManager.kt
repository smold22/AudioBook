package com.yourapp.audiobook.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.yourapp.audiobook.source.api.Book
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BackupData(
    val version: Int = 1,
    val favorites: List<Book>? = null,
    val history: List<HistoryEntry>? = null,
    val progress: Map<String, String>? = null,
    val theme: String? = null,
)

class BackupManager {

    private val gson: Gson = GsonBuilder().create()

    suspend fun backup(
        dirPath: String,
        favorites: List<Book>,
        history: List<HistoryEntry>,
        progress: Map<String, String>,
        theme: String,
    ): String? {
        return withContext(Dispatchers.IO) {
            val payload = gson.toJson(
                BackupData(
                    version = 1,
                    favorites = favorites,
                    history = history,
                    progress = progress,
                    theme = theme,
                ),
            )
            val format = SimpleDateFormat("'AudioBook_backup_'yyyy_MM_dd_HH_mm_ss", Locale.UK)
            val file = File(dirPath, format.format(Date()) + ".json")
            try {
                FileWriter(file).use { it.write(payload) }
                file.absolutePath
            } catch (e: IOException) {
                null
            }
        }
    }

    suspend fun restore(filePath: String): BackupData {
        return withContext(Dispatchers.IO) {
            val text = File(filePath).readText(Charsets.UTF_8)
            runCatching { gson.fromJson(text, BackupData::class.java) }
                .getOrElse { throw IOException("Выбранный файл не является резервной копией") }
                ?: throw IOException("Выбранный файл не является резервной копией")
        }
    }
}