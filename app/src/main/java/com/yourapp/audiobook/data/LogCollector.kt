package com.yourapp.audiobook.data

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogCollector {
    private const val LOG_DIR = "logs"
    private const val MAX_FILES = 3

    fun collect(context: Context): File {
        val dir = File(context.cacheDir, LOG_DIR).apply { mkdirs() }
        dir.listFiles()
            ?.sortedBy { it.lastModified() }
            ?.dropLast(MAX_FILES - 1)
            ?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "app_logs_$stamp.txt")
        val sb = StringBuilder()
        sb.appendLine("=== AudioBook ===")
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            sb.appendLine("Версия: ${info.versionName} (${info.versionCode})")
        }
        sb.appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("=== Логи приложения ===")
        val pid = Process.myPid()
        val log = runCatching {
            val p = ProcessBuilder(
                "logcat", "-d", "-b", "main", "-b", "crash",
                "-v", "threadtime", "--pid=$pid",
            ).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull() ?: "logcat недоступен"
        sb.appendLine(if (log.isBlank()) "(пусто)" else log)
        file.writeText(sb.toString())
        return file
    }
}
